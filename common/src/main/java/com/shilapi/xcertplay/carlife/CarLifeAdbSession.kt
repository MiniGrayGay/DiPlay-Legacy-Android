package com.shilapi.xcertplay.carlife

import com.shilapi.xcertplay.adb.AdbSession.AdbStream
import com.shilapi.xcertplay.adb.AdbSession
import com.shilapi.xcertplay.adb.AnnexBSplitter
import com.shilapi.xcertplay.carlife.CarLifeChannel.CMD
import com.shilapi.xcertplay.carlife.CarLifeChannel.CTRL
import com.shilapi.xcertplay.carlife.CarLifeChannel.VIDEO
import java.io.IOException

/**
 * The head-unit half of the CarLife protocol, driven over the ADB connection.
 *
 * This is the same link Baidu's own head unit uses: it runs
 * `adb forward tcp:7200 tcp:7240` (and five more) on the phone, then connects to `127.0.0.1:7200`.
 * DiPlay skips the host-side listener — [AdbSession.open] with `tcp:7240` is the same tunnel — which
 * means one less process, no bound ports, and reuse of the ADB client that already carries the
 * mirroring path.
 *
 * What the phone needs is the CarLife app running (it owns ports 7240…9340) and USB debugging on.
 * Nothing is installed by DiPlay.
 */
class CarLifeAdbSession(
    private val session: AdbSession,
    private val log: (String) -> Unit = {},
) {

    private var cmdStream: AdbStream? = null
    private var videoStream: AdbStream? = null
    private var ctrlStream: AdbStream? = null

    private var cmdWriter: CarLifeFrameWriter? = null
    private var cmdReader: CarLifeFrameReader? = null
    private var videoReader: CarLifeFrameReader? = null
    private var ctrlWriter: CarLifeFrameWriter? = null

    /** Encoded size the phone agreed to, once `VIDEO_ENCODER_INIT_DONE` has arrived. */
    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    /** True once the phone answered the version exchange with "match". */
    @Volatile
    var versionMatched = false
        private set

    /**
     * Opens the command channel, agrees a protocol version, asks for a video encoder and starts it.
     *
     * Returns false rather than throwing on a refusal: "the CarLife app is not running" is the ordinary
     * first outcome on a phone that has never been used this way, and the caller has to show a hint
     * instead of a stack trace.
     */
    fun handshake(
        deviceInfo: CarLifeDeviceInfo,
        width: Int,
        height: Int,
        frameRate: Int,
        timeoutMillis: Int = HANDSHAKE_TIMEOUT_MS,
    ): Boolean {
        val cmd = openChannel(CMD, timeoutMillis) ?: return false
        cmdStream = cmd
        cmdWriter = CarLifeFrameWriter(CMD, cmd.outputStream())
        cmdReader = CarLifeFrameReader(CMD, cmd.inputStream())

        // Version first: the phone drops every other message until the pair has been agreed.
        cmdWriter!!.write(CarLifeMessage.HU_PROTOCOL_VERSION, CarLifeProto.encodeProtocolVersion(1, 0))
        if (!awaitVersionMatch(timeoutMillis)) return false

        // Then identify the head unit and ask for the size we can actually show. Sending the screen size
        // instead of the visible area is what puts every touch off-target on a wide dash display.
        cmdWriter!!.write(CarLifeMessage.HU_INFO, CarLifeProto.encodeDeviceInfo(deviceInfo))
        cmdWriter!!.write(
            CarLifeMessage.VIDEO_ENCODER_INIT,
            CarLifeProto.encodeVideoEncoderInfo(width, height, frameRate),
        )
        if (!awaitEncoderInit(timeoutMillis)) return false

        openVideoAndControl(timeoutMillis)
        return true
    }

    private fun awaitVersionMatch(timeoutMillis: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val frame = cmdReader!!.read() ?: return false
            if (frame.type == CarLifeMessage.PROTOCOL_VERSION_MATCH_STATUS) {
                val status = CarLifeProto.decodeSingleInt(frame.payload)
                versionMatched = status == CarLifeMessage.VERSION_MATCH_OK
                log("carlife version match status=$status")
                return versionMatched
            }
            // The phone may talk first; anything before the answer is not interesting yet.
            log("carlife cmd ${hex(frame.type)} before the version match, ignored")
        }
        log("carlife: no version match within ${timeoutMillis}ms")
        return false
    }

    private fun awaitEncoderInit(timeoutMillis: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val frame = cmdReader!!.read() ?: return false
            when (frame.type) {
                CarLifeMessage.VIDEO_ENCODER_INIT_DONE -> {
                    val info = CarLifeProto.decodeVideoEncoderInfo(frame.payload)
                    videoWidth = info.width
                    videoHeight = info.height
                    log("carlife encoder ${info.width}x${info.height} @${info.frameRate}")
                    // Only now is the encoder allocated; starting earlier is silently dropped, which
                    // shows up as a black screen with no error to chase.
                    cmdWriter!!.write(CarLifeMessage.VIDEO_ENCODER_START)
                    return true
                }
                CarLifeMessage.CONNECT_EXCEPTION, CarLifeMessage.MD_EXIT -> {
                    log("carlife: phone aborted during setup (${hex(frame.type)})")
                    return false
                }
            }
        }
        log("carlife: encoder init timed out")
        return false
    }

    private fun openVideoAndControl(timeoutMillis: Int) {
        openChannel(VIDEO, timeoutMillis)?.let {
            videoStream = it
            videoReader = CarLifeFrameReader(VIDEO, it.inputStream())
        }
        openChannel(CTRL, timeoutMillis)?.let {
            ctrlStream = it
            ctrlWriter = CarLifeFrameWriter(CTRL, it.outputStream())
        }
        if (videoReader == null || ctrlWriter == null) {
            log("carlife: video=${videoReader != null} control=${ctrlWriter != null}")
        }
    }

    private fun openChannel(channel: CarLifeChannel, timeoutMillis: Int): AdbStream? {
        val stream = try {
            session.open(channel.adbService, timeoutMillis)
        } catch (error: IOException) {
            log("carlife ${channel.name}: ${error.message}")
            null
        }
        if (stream == null && channel == CMD) {
            log("carlife: ${channel.adbService} refused — is the CarLife app running and in USB mode?")
        }
        return stream
    }

    /**
     * Reads the video channel until it ends, feeding complete NAL units to [onNal].
     *
     * A CarLife video message usually carries one access unit, but nothing guarantees the split falls on
     * a NAL boundary, so the same streaming splitter as the `screenrecord` path is used rather than one
     * NAL per message being assumed.
     */
    fun pumpVideo(onNal: (ByteArray) -> Unit) {
        val reader = videoReader ?: return
        val splitter = AnnexBSplitter()
        while (true) {
            val frame = try {
                reader.read()
            } catch (error: IOException) {
                log("carlife video ended: ${error.message}")
                return
            } ?: return
            if (frame.type != CarLifeMessage.VIDEO_DATA) continue
            for (nal in splitter.feed(frame.payload)) onNal(nal)
        }
    }

    /** Sends one touch gesture, already mapped onto the encoded frame. Safe to call from the UI thread. */
    fun sendTouch(gesture: CarLifeTouchGesture, xInFrame: Int, yInFrame: Int) {
        val writer = ctrlWriter ?: return
        val type = CarLifeMessage.TOUCH_BY_GESTURE.getValue(gesture)
        writer.write(type, CarLifeTouch.encode(xInFrame, yInFrame))
    }

    /** Sends a hard key, e.g. HOME or BACK, when the driver uses the dash buttons. */
    fun sendHardKey(keyCode: Int) {
        val writer = ctrlWriter ?: return
        val payload = ByteArray(4)
        payload[0] = (keyCode and 0xff).toByte()
        payload[1] = ((keyCode ushr 8) and 0xff).toByte()
        payload[2] = ((keyCode ushr 16) and 0xff).toByte()
        payload[3] = ((keyCode ushr 24) and 0xff).toByte()
        writer.write(CarLifeMessage.TOUCH_CAR_HARD_KEY_CODE, payload)
    }

    /** Asks the phone to pause its encoder; used when the head unit loses the foreground. */
    fun pauseVideo() {
        cmdWriter?.write(CarLifeMessage.VIDEO_ENCODER_PAUSE)
    }

    fun close() {
        // Closing the ADB streams is enough: adbd drops the tunnels and the phone's CarLife service sees
        // the sockets end, which is how it learns the head unit is gone.
        runCatching { cmdStream?.close() }
        runCatching { videoStream?.close() }
        runCatching { ctrlStream?.close() }
        cmdStream = null
        videoStream = null
        ctrlStream = null
    }

    private fun hex(value: Int) = "0x" + value.toUInt().toString(16).padStart(8, '0')

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 8_000
    }
}
