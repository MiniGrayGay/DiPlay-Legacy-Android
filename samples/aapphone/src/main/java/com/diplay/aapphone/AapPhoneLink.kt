package com.diplay.aapphone

import com.shilapi.xcertplay.aap.AapIdentity
import com.shilapi.xcertplay.aap.AapInputMessages
import com.shilapi.xcertplay.aap.AapMediaMessages
import com.shilapi.xcertplay.aap.AapMessage
import com.shilapi.xcertplay.aap.AapMessageIds
import com.shilapi.xcertplay.aap.AapSensorMessages
import com.shilapi.xcertplay.aap.AapSensorType
import com.shilapi.xcertplay.aap.AapServiceDiscovery
import com.shilapi.xcertplay.aap.TcpTransport
import com.shilapi.xcertplay.aap.phone.AapPhoneSession
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's end of a live session: transport, protocol, and the encoder that feeds it.
 *
 * This is what a real phone app does around [AapPhoneSession], which is only the protocol. The part
 * worth reading is the flow control: the head unit says how many frames may be in flight, an ack
 * frees one, and a frame that arrives when the window is full is **dropped**. Buffering instead
 * would turn a slow link into growing latency, which in a car is worse than a stutter.
 */
class AapPhoneLink(
    private val identity: AapIdentity,
    /**
     * The H.264 profile the head unit asked for. Vertical phones project a tall screen; the head
     * unit here advertises 800x480, so the encoder matches whatever it announced.
     */
    private var encodeWidth: Int = 800,
    private var encodeHeight: Int = 480,
) {

    interface Listener {
        fun onStatus(text: String)
        fun onDiscovery(summary: String)
        fun onTouch(x: Int, y: Int)
        fun onNightMode(night: Boolean)
        fun onStats(fps: Int, dropped: Int, inFlight: Int)
        fun onClosed(reason: String)
    }

    var listener: Listener? = null

    private var session: AapPhoneSession? = null
    private var encoder: AapPhoneEncoder? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    private var videoChannel = 0
    private var audioChannel = 0
    private var inputChannel = 0
    private var sensorChannel = 0
    private var videoConfigIndex = 0
    private var videoSessionId = 1

    private val maxUnacked = AtomicInteger(4)
    private val inFlight = AtomicInteger(0)
    private val dropped = AtomicInteger(0)

    /** Frames actually handed to the session, which is what the rate line reports. */
    private val framesSent = AtomicInteger(0)

    /** The rendered image, exposed so the activity can preview what the car is being sent. */
    val renderer: TestPatternRenderer?
        get() = encoder?.renderer

    fun connect(host: String, port: Int) {
        if (running) return
        running = true
        worker = Thread({ run(host, port) }, "aap-phone").apply {
            isDaemon = true
            start()
        }
    }

    fun disconnect() {
        running = false
        encoder?.stop()
        encoder = null
        session?.close()
        session = null
        worker?.interrupt()
        worker = null
        inFlight.set(0)
    }

    private fun run(host: String, port: Int) {
        status("正在连接 $host:$port")
        val transport = try {
            TcpTransport.connect(host, port)
        } catch (e: Exception) {
            status("连接失败：${e.message}")
            listener?.onClosed("连接失败：${e.message}")
            return
        }
        val created = AapPhoneSession(
            transport = transport,
            identity = identity,
            deviceName = "DiPlay AAP 模拟手机",
            label = "模拟手机",
            log = { status(it) },
        ).apply { listener = sessionListener }
        session = created

        val opened = try {
            created.connect()
        } catch (e: Exception) {
            status("会话失败：${e.message}")
            false
        }
        if (!opened) {
            val reason = "会话未能建立"
            status(reason)
            listener?.onClosed(reason)
            created.close()
            return
        }
        status("已连接，等待车机开启视频通道")
        while (running && created.pump()) {
            // pump dispatches into sessionListener.
        }
        created.close()
        encoder?.stop()
        encoder = null
        inFlight.set(0)
        status("会话已结束")
        listener?.onClosed("会话已结束")
    }

    // ------------------------------------------------------------------ protocol callbacks

    private val sessionListener = object : AapPhoneSession.Listener {
        override fun onDiscovery(discovery: AapServiceDiscovery.Discovery) {
            videoChannel = discovery.video?.channel ?: 0
            audioChannel = discovery.audio.firstOrNull()?.channel ?: 0
            inputChannel = discovery.input?.channel ?: 0
            sensorChannel = discovery.sensors?.channel ?: 0
            discovery.video?.videoConfigs?.firstOrNull()?.let {
                encodeWidth = it.resolution.width
                encodeHeight = it.resolution.height
            }
            status("车机：${discovery.displayName}，${discovery.services.size} 个服务")
            listener?.onDiscovery(
                buildString {
                    append("车机 ").append(discovery.displayName.ifEmpty { "Android Auto" })
                    append(" · ").append(discovery.services.size).append(" 个服务")
                    discovery.video?.let { append(" · 视频 ").append(it.videoConfigs.firstOrNull() ?: "") }
                },
            )
        }

        override fun onChannelOpen(channel: Int, service: AapServiceDiscovery.Service) {
            when (service.kind) {
                AapServiceDiscovery.Kind.VIDEO ->
                    // The head unit waits for SETUP before it configures its decoder.
                    session?.send(
                        channel,
                        AapMessageIds.Media.SETUP,
                        AapMediaMessages.encodeSetup(com.shilapi.xcertplay.aap.AapCodec.VIDEO_H264_BP),
                    )

                AapServiceDiscovery.Kind.AUDIO ->
                    session?.send(
                        channel,
                        AapMessageIds.Media.SETUP,
                        AapMediaMessages.encodeSetup(com.shilapi.xcertplay.aap.AapCodec.AUDIO_PCM),
                    )

                AapServiceDiscovery.Kind.SENSOR -> {
                    session?.send(
                        channel,
                        AapMessageIds.Sensor.REQUEST,
                        AapSensorMessages.encodeRequest(AapSensorType.NIGHT_MODE),
                    )
                    session?.send(
                        channel,
                        AapMessageIds.Sensor.REQUEST,
                        AapSensorMessages.encodeRequest(AapSensorType.DRIVING_STATUS),
                    )
                }

                else -> Unit
            }
        }

        override fun onServiceMessage(channel: Int, message: AapMessage) {
            when (channel) {
                videoChannel -> onVideoMessage(message)
                sensorChannel -> if (message.id == AapMessageIds.Sensor.BATCH) {
                    AapSensorMessages.decodeNightMode(message.body)?.let { listener?.onNightMode(it) }
                }

                inputChannel -> if (message.id == AapMessageIds.Input.INPUT_REPORT) {
                    // The touch the car sent, shown where it landed: this is the visible proof that
                    // the input path works, and it is what a reviewer looks for on screen.
                    AapInputMessages.decodeTouch(message.body)?.let { touch ->
                        renderer?.touchX = touch.x
                        renderer?.touchY = touch.y
                        listener?.onTouch(touch.x, touch.y)
                    }
                }

                else -> Unit
            }
        }

        override fun onClosed(reason: String, cause: Throwable?) {
            status("断开：$reason")
            running = false
        }
    }

    private fun onVideoMessage(message: AapMessage) {
        when (message.id) {
            AapMessageIds.Media.CONFIG -> {
                val config = parseConfig(message.body)
                maxUnacked.set(config.maxUnacked)
                videoConfigIndex = config.configurationIndices.firstOrNull() ?: 0
                startVideo()
            }

            AapMessageIds.Media.ACK -> {
                val ack = parseAck(message.body)
                inFlight.updateAndGet { current -> (current - ack).coerceAtLeast(0) }
            }

            AapMessageIds.Media.VIDEO_FOCUS_NOTIFICATION -> {
                val focus = AapMediaMessages.decodeVideoFocusNotification(message.body)
                if (focus.projected) {
                    // Focus returning means the decoder restarted: parameter sets first, then a key
                    // frame, or the car shows a mosaic until the next scheduled I-frame.
                    encoder?.requestKeyFrame()
                }
            }

            AapMessageIds.Media.VIDEO_FOCUS_REQUEST -> {
                val mode = AapMediaMessages.decodeVideoFocusRequestMode(message.body)
                sentVideoFocus(mode == AapMediaMessages.VideoFocusMode.PROJECTED)
            }

            else -> Unit
        }
    }

    private fun startVideo() {
        if (encoder != null) return
        val created = AapPhoneEncoder(
            width = encodeWidth,
            height = encodeHeight,
            fps = FRAMES_PER_SECOND,
            onCodecConfig = { config ->
                session?.sendCodecConfig(videoChannel, config)
                renderer?.subtitle = "已推送 ${encodeWidth}x$encodeHeight · $FRAMES_PER_SECOND fps"
            },
            onFrame = { bytes, pts ->
                if (inFlight.get() < maxUnacked.get()) {
                    inFlight.incrementAndGet()
                    session?.sendMediaData(videoChannel, pts, bytes)
                    framesSent.incrementAndGet()
                } else {
                    dropped.incrementAndGet()
                }
            },
            log = { status(it) },
        )
        encoder = created
        created.start()
        session?.send(
            videoChannel,
            AapMessageIds.Media.START,
            AapMediaMessages.encodeStart(videoSessionId, videoConfigIndex),
        )
        // Ask to be shown; the head unit answers with a focus notification and then the frames matter.
        session?.send(
            videoChannel,
            AapMessageIds.Media.VIDEO_FOCUS_REQUEST,
            AapMediaMessages.encodeVideoFocusRequest(AapMediaMessages.VideoFocusMode.PROJECTED),
        )
        status("视频已开始 ${encodeWidth}x$encodeHeight@$FRAMES_PER_SECOND")
        reportStats()
    }

    private fun sentVideoFocus(projected: Boolean) {
        session?.send(
            videoChannel,
            AapMessageIds.Media.VIDEO_FOCUS_NOTIFICATION,
            AapMediaMessages.encodeVideoFocusNotification(
                if (projected) AapMediaMessages.VideoFocusMode.PROJECTED else AapMediaMessages.VideoFocusMode.NATIVE,
            ),
        )
    }

    private fun reportStats() {
        // A one-second tick is enough for a diagnostics line and cheap enough to leave running.
        // The rate is measured, not the target: a window that stays full shows up here as a real
        // number falling short, which is the whole point of the line.
        Thread({
            var lastCount = framesSent.get()
            while (running && encoder != null) {
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val count = framesSent.get()
                listener?.onStats(count - lastCount, dropped.get(), inFlight.get())
                lastCount = count
            }
        }, "aap-stats").apply { isDaemon = true }.start()
    }

    private fun status(text: String) {
        listener?.onStatus(text)
    }

    /** The head unit's window, read straight from the protocol layer. */
    private fun parseConfig(bytes: ByteArray): AapMediaMessages.Config = AapMediaMessages.decodeConfig(bytes)

    /** `Ack { session_id = 1; ack = 2 }`; a missing count means one. */
    private fun parseAck(bytes: ByteArray): Int = AapMediaMessages.decodeAck(bytes)

    private companion object {
        /** Matched to the head unit's advertised 30 fps with room for the emulator's encoder. */
        const val FRAMES_PER_SECOND = 20
    }
}
