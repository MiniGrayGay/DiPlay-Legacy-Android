package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.Surface
import com.shilapi.xcertplay.aap.AapAudioStream
import com.shilapi.xcertplay.aap.AapAudioStreamType
import com.shilapi.xcertplay.aap.AapIdentity
import com.shilapi.xcertplay.aap.AapInputMessages
import com.shilapi.xcertplay.aap.AapMediaMessages
import com.shilapi.xcertplay.aap.AapMessage
import com.shilapi.xcertplay.aap.AapMessageIds
import com.shilapi.xcertplay.aap.AapResolution
import com.shilapi.xcertplay.aap.AapSensorMessages
import com.shilapi.xcertplay.aap.AapSensorType
import com.shilapi.xcertplay.aap.AapServiceSet
import com.shilapi.xcertplay.aap.AapSession
import com.shilapi.xcertplay.aap.AapTcpServer
import com.shilapi.xcertplay.aap.AapTouchScreen
import com.shilapi.xcertplay.aap.AapVideoConfig
import com.shilapi.xcertplay.aap.AapTransport
import com.shilapi.xcertplay.aap.AoaIdentity
import com.shilapi.xcertplay.aap.TcpTransport
import com.shilapi.xcertplay.aap.UsbAccessoryTransport
import com.shilapi.xcertplay.aap.UsbAoaHandshake
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Runs one Android Auto session end to end on the head unit: connect, negotiate, decode video, play
 * audio, send input back.
 *
 * Split out of the activity so the activity is only a view. Everything here happens on a worker
 * thread except [sendTouch] and [setSurface], which are called from the UI thread and hand off.
 *
 * The media-channel handshake is the part that is easy to get subtly wrong, so it is spelled out:
 *
 * ```
 * phone sends SETUP          -> we answer CONFIG (READY, window, which configs we accept)
 * phone sends START          -> we remember its session id and the config it chose
 * phone sends CODEC_CONFIG   -> straight to the decoder, it carries SPS and PPS
 * phone sends DATA           -> decode, then ACK or the phone stops after `max_unacked` frames
 * ```
 *
 * An unacknowledged frame is not a lost frame: the phone stops sending entirely once the window
 * fills, which from the outside looks like a frozen picture rather than a stalled protocol.
 */
class AapProjectionController(
    context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onStatus(text: String)
        fun onVideoSize(width: Int, height: Int)

        /** The phone has opened its video channel; frames have not arrived yet. */
        fun onReady()

        /**
         * The first frame has actually been drawn.
         *
         * Kept separate from [onReady] on purpose: hiding the diagnostics overlay when the channel
         * opens leaves a black screen with no explanation on it when the stream then fails to
         * decode, which is the one moment the overlay is worth reading.
         */
        fun onFirstFrame() {}

        fun onEnded(reason: String)
    }

    /** What the head unit offers. Kept in one place so the diagnostics screen can print it. */
    val services: AapServiceSet = AapServiceSet(
        displayName = "DiPlay",
        video = listOf(
            // The one resolution that both suits a small car screen and keeps the emulator's
            // software encoder at a speed where latency is tolerable.
            DEFAULT_VIDEO,
        ),
        audio = listOf(AapAudioStream(AapAudioStreamType.MEDIA)),
        touchscreen = AapTouchScreen(DEFAULT_VIDEO.resolution.width, DEFAULT_VIDEO.resolution.height),
        sensors = listOf(AapSensorType.NIGHT_MODE, AapSensorType.DRIVING_STATUS),
        pingIntervalMs = 1_000,
        pingTimeoutMs = 5_000,
    )

    private val identity: AapIdentity = AapIdentityStore.load(context)
    private val decoder = AapVideoDecoder(log = { note(it) }, onFirstFrame = { listener.onFirstFrame() })
    private val audio = AapAudioPlayer { note(it) }

    @Volatile
    private var session: AapSession? = null
    @Volatile
    private var stopping = false
    private var worker: Thread? = null
    private var server: ServerSocket? = null
    private var surface: Surface? = null
    private var transport: AapTransport? = null

    /** Single thread for sends that start on the UI thread; see [sendFromUi]. */
    private val sender: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aap-send").apply { isDaemon = true }
    }

    private var videoSessionId = 0
    private var audioSessionId = 0
    private var videoConfigIndex = 0
    private var nightMode = false
    private var drivingStatus = 0

    /** Frames seen on the video channel, for the throttled progress line. */
    private var dataFrames = 0

    /** Size the phone announced in its START, which is what touch coordinates are relative to. */
    @Volatile
    var videoWidth: Int = DEFAULT_VIDEO.resolution.width
        private set

    @Volatile
    var videoHeight: Int = DEFAULT_VIDEO.resolution.height
        private set

    val isRunning: Boolean get() = session?.isOpen == true

    /** Fingerprint the phone will pin, shown so a driver can compare it on the phone's prompt. */
    val fingerprint: String get() = identity.fingerprint

    // ------------------------------------------------------------------ lifecycle

    /** Listens on [port] and serves whichever phone connects. Used by the TCP transport. */
    fun listen(port: Int = TcpTransport.DEFAULT_PORT, bindAddress: String? = null) {
        startWorker("aap-listen") {
            val socket = AapTcpServer.listen(port, bindAddress)
            server = socket
            notify("正在监听 $port 端口，等待手机连接")
            while (!stopping) {
                val accepted = TcpTransport.accept(socket, timeoutMillis = 2_000) ?: continue
                notify("手机已连接：${accepted.remote()}")
                serve(accepted)
                if (!stopping) notify("连接已断开，继续等待手机")
            }
        }
    }

    /**
     * Serves an Android phone over USB.
     *
     * Two cases, and the difference matters: a phone that has already re-enumerated as an accessory
     * (vendor `0x18D1`) is served directly, while a plain device is first walked through the AOA
     * handshake. The handshake makes the phone drop off the bus and come back as the accessory, so
     * the second device is the one worth opening — opening the first one again gets a device with
     * no bulk endpoints and looks like a protocol failure.
     */
    fun serveUsb(usb: UsbManager, device: UsbDevice) {
        startWorker("aap-usb") {
            notify("安卓手机已接入，准备 USB 会话")
            val accessory = if (UsbAoaHandshake.isAccessory(device)) {
                device
            } else {
                enterAccessoryMode(usb, device)
            }
            notify("正在建立 USB 传输")
            val transport = UsbAccessoryTransport.open(usb, accessory)
                ?: throw IOException("手机没有可用的批量端点，可能没有切换到配件模式")
            serve(transport)
        }
    }

    /** Runs the AOA handshake and waits for the phone to come back as an accessory. */
    private fun enterAccessoryMode(usb: UsbManager, device: UsbDevice): UsbDevice {
        val connection = usb.openDevice(device) ?: throw IOException("无法打开手机，检查 USB 权限")
        try {
            val version = UsbAoaHandshake.run(connection, AoaIdentity.diplay())
            if (version == 0) throw IOException("这台手机不支持 AOA（Android Open Accessory）")
            notify("AOA 版本 $version，等待手机重新枚举")
        } finally {
            runCatching { connection.close() }
        }
        // Re-enumeration is asynchronous: the accessory appears a moment after START is accepted.
        repeat(50) {
            Thread.sleep(100)
            val accessory = usb.deviceList.values.firstOrNull {
                UsbAoaHandshake.isAccessory(it) && it.productId != device.productId
            }
            if (accessory != null) return accessory
        }
        throw IOException("手机没有以配件模式重新出现")
    }

    /** Dials a phone or adapter that is already listening. Mirrors the server path. */
    fun connect(host: String, port: Int = TcpTransport.DEFAULT_PORT) {
        startWorker("aap-connect") {
            notify("正在连接 $host:$port")
            serve(TcpTransport.connect(host, port))
        }
    }

    /** Serves one already-open transport. Exposed so the USB path can reuse everything below. */
    fun serve(existing: AapTransport) {
        transport = existing
        val created = AapSession(
            transport = existing,
            identity = identity,
            services = services,
            log = { note(it) },
        ).apply { listener = this@AapProjectionController.sessionListener }
        session = created
        notify("正在协商协议…")
        if (!created.connect()) {
            // connect() already logged the reason; the UI only needs to know it failed.
            notify("协商失败，连接已关闭")
            listener.onEnded(lastFailure)
            session = null
            return
        }
        notify("已连接，等待手机开始投影")
        while (!stopping && created.pump()) {
            // pump dispatches into the session listener below.
        }
        created.close()
        session = null
        decoder.release()
        audio.stop()
        notify("会话已结束：$lastFailure")
        listener.onEnded(lastFailure)
    }

    fun setSurface(surface: Surface?) {
        this.surface = surface
        if (surface != null) decoder.setSurface(surface, videoWidth, videoHeight) else decoder.reset()
    }

    /** Sends a touch in **video** coordinates; the activity has already removed the letterbox. */
    fun sendTouch(x: Int, y: Int, action: Int) {
        sendFromUi("touch") { target ->
            // Down and up only: a drag would otherwise put a line in the log per pixel it crosses.
            if (action != AapInputMessages.Action.MOVED) note("touch action=$action at $x,$y")
            target.sendTouch(System.nanoTime() / 1_000, x, y, action)
        }
    }

    /** Sends a key the car's own hardware produced (steering wheel, panel buttons). */
    fun sendKey(keycode: Int, down: Boolean) {
        sendFromUi("key $keycode") { target -> target.sendKey(System.nanoTime() / 1_000, keycode, down) }
    }

    fun setNightMode(night: Boolean) {
        nightMode = night
        sendFromUi("night mode") { target -> target.sendNightMode(night) }
    }

    /**
     * Queues a send that originates on the UI thread.
     *
     * Writing to the socket straight from a touch handler throws `NetworkOnMainThreadException`
     * under StrictMode, and that exception surfaces inside the session as a write failure — which
     * closes the whole session on the driver's first tap. One thread also keeps the order of the
     * events intact, which matters for a drag.
     */
    private fun sendFromUi(what: String, body: (AapSession) -> Unit) {
        val target = session
        if (target == null) {
            note("$what dropped: no session")
            return
        }
        sender.execute {
            runCatching { body(target) }.onFailure { note("$what failed: ${it.javaClass.simpleName}") }
        }
    }

    fun stop() {
        stopping = true
        runCatching { server?.close() }
        runCatching { transport?.close() }
        session?.close()
        worker?.interrupt()
        worker = null
        decoder.release()
        audio.stop()
    }

    // ------------------------------------------------------------------ session callbacks

    private val sessionListener = object : AapSession.Listener {
        override fun onState(state: AapSession.State) {
            when (state) {
                AapSession.State.VERSION -> notify("协商协议版本")
                AapSession.State.HANDSHAKE -> notify("建立加密连接")
                AapSession.State.AUTH -> notify("校验手机证书")
                AapSession.State.DISCOVERY -> notify("交换能力列表")
                AapSession.State.RUNNING -> notify("通道已建立")
                else -> Unit
            }
        }

        override fun onPhoneDiscovered(request: com.shilapi.xcertplay.aap.AapControlMessages.ServiceDiscoveryRequest) {
            notify("手机：${request.display}")
        }

        override fun onReady(session: AapSession) {
            // Night mode first: the phone decides its palette from this before it draws anything.
            session.sendNightMode(nightMode)
            session.sendDrivingStatus(drivingStatus)
            notify("运行中，等待视频通道")
        }

        override fun onServiceMessage(channel: Int, message: AapMessage) {
            when (channel) {
                services.videoChannel -> onVideoMessage(message)
                services.inputChannel -> onInputMessage(message)
                services.sensorChannel -> onSensorMessage(message)
                else -> if (services.audioChannels.containsKey(channel)) onAudioMessage(channel, message)
            }
        }

        override fun onClosed(reason: String, cause: Throwable?) {
            lastFailure = reason
            note("closed: $reason")
        }
    }

    // ------------------------------------------------------------------ channels

    private fun onVideoMessage(message: AapMessage) {
        when (message.id) {
            AapMessageIds.Media.SETUP -> {
                val codec = AapMediaMessages.decodeSetupCodec(message.body)
                note("video SETUP codec=$codec")
                val config = AapMediaMessages.encodeConfig(
                    maxUnacked = MAX_UNACKED,
                    configurationIndices = services.video.indices.toList(),
                )
                session?.send(services.videoChannel, AapMessageIds.Media.CONFIG, config)
            }

            AapMessageIds.Media.START -> {
                val start = AapMediaMessages.decodeStart(message.body)
                videoSessionId = start.sessionId
                videoConfigIndex = start.configurationIndex.coerceIn(0, services.video.lastIndex)
                val resolution = services.video[videoConfigIndex].resolution
                videoWidth = resolution.width
                videoHeight = resolution.height
                note("video START $start at ${resolution.width}x${resolution.height}")
                surface?.let { decoder.setSurface(it, videoWidth, videoHeight) }
                listener.onVideoSize(videoWidth, videoHeight)
                notify("视频 ${resolution.width}x${resolution.height}")
                // The phone asked for focus by now in most builds; answering here as well is
                // harmless and covers the ones that wait for the car to speak first.
                session?.sendVideoFocus(AapMediaMessages.VideoFocusMode.PROJECTED)
                listener.onReady()
            }

            AapMessageIds.Media.STOP -> {
                note("video STOP")
                videoSessionId = 0
            }

            AapMessageIds.Media.CODEC_CONFIG ->
                decoder.setCodecConfig(message.body, 0, message.body.size)

            AapMessageIds.Media.DATA -> {
                if (message.body.size <= AapMediaMessages.DATA_HEADER) return
                val timestamp = AapMediaMessages.dataTimestamp(message.body)
                decoder.queue(
                    message.body,
                    AapMediaMessages.DATA_HEADER,
                    message.body.size - AapMediaMessages.DATA_HEADER,
                    timestamp,
                )
                // One line every thirty frames: enough to tell "no frames" from "frames that never
                // reach the screen" without turning a 20 fps stream into a log flood.
                if (++dataFrames % 30 == 1) {
                    note("video frame #$dataFrames ${message.body.size}B dropped=${decoder.dropped}")
                }
                // One ack per frame: the phone's window is what keeps it sending, and the ack also
                // tells it how far behind the car is.
                session?.ackVideo(videoSessionId, 1)
            }

            AapMessageIds.Media.VIDEO_FOCUS_REQUEST -> {
                val mode = AapMediaMessages.decodeVideoFocusRequestMode(message.body)
                note("phone wants video focus mode=$mode")
                session?.sendVideoFocus(
                    if (mode == AapMediaMessages.VideoFocusMode.NATIVE) {
                        AapMediaMessages.VideoFocusMode.NATIVE
                    } else {
                        AapMediaMessages.VideoFocusMode.PROJECTED
                    },
                )
            }

            AapMessageIds.Media.VIDEO_FOCUS_NOTIFICATION -> {
                val focus = AapMediaMessages.decodeVideoFocusNotification(message.body)
                note("video focus $focus")
                if (focus.projected) {
                    // Focus coming back means the phone will start again from a key frame.
                    decoder.reset()
                    listener.onReady()
                }
            }

            AapMessageIds.Media.ACK -> Unit
            else -> note("video 0x${message.id.toString(16)} ignored")
        }
    }

    private fun onAudioMessage(channel: Int, message: AapMessage) {
        val stream = services.audioChannels[channel] ?: return
        when (message.id) {
            AapMessageIds.Media.SETUP -> {
                note("audio SETUP on $channel")
                session?.send(
                    channel,
                    AapMessageIds.Media.CONFIG,
                    AapMediaMessages.encodeConfig(maxUnacked = MAX_UNACKED, configurationIndices = listOf(0)),
                )
            }

            AapMessageIds.Media.START -> {
                val start = AapMediaMessages.decodeStart(message.body)
                audioSessionId = start.sessionId
                audio.start(stream.sampleRate, stream.channels, stream.bitDepth)
                note("audio START $start")
            }

            AapMessageIds.Media.STOP -> {
                audioSessionId = 0
                audio.stop()
            }

            AapMessageIds.Media.DATA -> {
                if (message.body.size <= AapMediaMessages.DATA_HEADER) return
                audio.write(
                    message.body,
                    AapMediaMessages.DATA_HEADER,
                    message.body.size - AapMediaMessages.DATA_HEADER,
                )
                session?.send(
                    channel,
                    AapMessageIds.Media.ACK,
                    AapMediaMessages.encodeAck(audioSessionId, 1),
                )
            }

            else -> note("audio 0x${message.id.toString(16)} ignored")
        }
    }

    private fun onInputMessage(message: AapMessage) {
        when (message.id) {
            AapMessageIds.Input.KEY_BINDING_REQUEST -> {
                // Status 0, no keys re-bound: the car sends Android key codes directly.
                session?.send(
                    services.inputChannel,
                    AapMessageIds.Input.KEY_BINDING_RESPONSE,
                    AapInputMessages.encodeKeyBindingResponse(),
                )
                note("key binding accepted")
            }

            AapMessageIds.Input.INPUT_FEEDBACK -> Unit
            else -> note("input 0x${message.id.toString(16)} ignored")
        }
    }

    private fun onSensorMessage(message: AapMessage) {
        if (message.id != AapMessageIds.Sensor.REQUEST) {
            note("sensor 0x${message.id.toString(16)} ignored")
            return
        }
        val request = AapSensorMessages.decodeRequest(message.body) ?: return
        val body = when (request.type) {
            AapSensorType.NIGHT_MODE -> AapSensorMessages.encodeBatch(nightMode = nightMode)
            AapSensorType.DRIVING_STATUS -> AapSensorMessages.encodeBatch(drivingStatus = drivingStatus)
            else -> null
        }
        if (body != null) session?.send(services.sensorChannel, AapMessageIds.Sensor.BATCH, body)
    }

    // ------------------------------------------------------------------ plumbing

    /** Reason for the last failure, so the UI can say something better than "disconnected". */
    @Volatile
    var lastFailure: String = "未连接"
        private set

    private fun startWorker(name: String, body: () -> Unit) {
        stop()
        stopping = false
        worker = Thread({
            try {
                body()
            } catch (e: Exception) {
                note("worker failed: ${e.message}")
                lastFailure = e.message ?: "连接失败"
                listener.onEnded(lastFailure)
            }
        }, name).apply {
            isDaemon = true
            start()
        }
    }

    private fun notify(text: String) {
        AapDiagnostics.log("ui", text)
        listener.onStatus(text)
    }

    private fun note(line: String) {
        AapDiagnostics.log("aap", line)
    }

    companion object {
        /**
         * How many frames the phone may send before an ack. Four bounds the latency the decoder can
         * accumulate to about a tenth of a second at 30 fps.
         */
        const val MAX_UNACKED = 4

        private val DEFAULT_VIDEO = AapVideoConfig(
            resolution = AapResolution.P800x480,
            frameRate = 30,
            density = 160,
        )
    }
}
