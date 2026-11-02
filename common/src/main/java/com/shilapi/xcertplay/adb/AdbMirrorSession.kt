package com.shilapi.xcertplay.adb

import android.view.Surface
import java.security.KeyPair
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns a live ADB link into a mirroring session — with nothing installed on the phone.
 *
 * The whole pipeline is four steps: speak ADB to the phone's own `adbd`, start `screenrecord` with
 * `--output-format=h264` on its stdout, decode that into a Surface, and send `input` for touches. Both
 * binaries are part of Android, so this is the only design that works on a phone the driver has
 * installed nothing on and cannot be asked to grant a screen-capture dialog to.
 *
 * `screenrecord` stops itself after three minutes, so the video stream is reopened in a loop; the
 * decoder is kept across the boundary, and the new stream's SPS/PPS are simply fed again.
 */
class AdbMirrorSession(
    private val transport: AdbTransport,
    private val key: KeyPair,
    private val listener: Listener,
) {

    interface Listener {
        /** One line for the diagnostics strip. */
        fun onStatus(status: String)

        /** Encoded size of the phone's screen, once the decoder knows it. */
        fun onVideoSize(width: Int, height: Int)

        /** The session is over; the driver has to retry deliberately. */
        fun onEnded(reason: String)
    }

    @Volatile
    private var session: AdbSession? = null

    private val decoder = AnnexBDecoder { listener.onStatus(it) }
    private val commands = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adb-input").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private var videoThread: Thread? = null

    /** Frames the decoder has rendered, shown so a stalled picture is obvious. */
    val renderedFrames: Long get() = decoder.renderedFrames

    /** Phone's screen size in its current rotation; 0 until the first frame. */
    val videoWidth: Int get() = decoder.width
    val videoHeight: Int get() = decoder.height

    fun setSurface(surface: Surface?) = decoder.setSurface(surface)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        videoThread = Thread({ run() }, "adb-mirror").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        session?.close()
        session = null
        commands.shutdownNow()
        decoder.release()
    }

    /**
     * A touch that never moved becomes `input tap`; anything else becomes `input swipe`.
     *
     * `input` starts a process per gesture, which is fine for a tap and gives a drag the shape of a
     * straight line rather than the driver's actual curve. Enough to drive a phone's UI; a persistent
     * injector is the upgrade if it is not.
     */
    fun tapOrSwipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Int) {
        val width = decoder.width
        val height = decoder.height
        if (width <= 0 || height <= 0) return
        val (startX, startY) = AdbCommands.toDevice(fromX, fromY, width, height)
        val (endX, endY) = AdbCommands.toDevice(toX, toY, width, height)
        val moved = Math.abs(endX - startX) + Math.abs(endY - startY) > TAP_SLOP_PX
        val command = if (moved || durationMillis > TAP_TIMEOUT_MS) {
            AdbCommands.swipe(startX, startY, endX, endY, durationMillis.coerceIn(1, 2_000))
        } else {
            AdbCommands.tap(endX, endY)
        }
        run(command)
    }

    /** A hardware key on the phone, e.g. back. */
    fun key(keyCode: Int) = run(AdbCommands.keyEvent(keyCode))

    private fun run(command: String) {
        val active = session ?: return
        if (commands.isShutdown) return
        commands.execute { active.exec(command, COMMAND_TIMEOUT_MS) }
    }

    private fun run() {
        val active = AdbSession(transport, key) { listener.onStatus(it) }
        session = active
        listener.onStatus("adb handshake")
        when (active.connect()) {
            AdbSession.State.READY -> Unit
            AdbSession.State.AWAITING_APPROVAL -> return finish("approve usb debugging on the phone")
            AdbSession.State.UNSUPPORTED -> return finish("phone only accepts tls adb")
            else -> return finish("no adb link")
        }
        listener.onStatus("adb ready: ${active.deviceBanner.ifEmpty { transport.peer }}")

        var attempts = 0
        while (running.get()) {
            if (!streamOnce(active)) {
                attempts++
                if (attempts > MAX_RESTARTS) {
                    // Repeated immediate failures mean USB debugging was turned off or the phone was
                    // unplugged; retrying forever would hide that from the driver.
                    return finish("screenrecord keeps failing")
                }
                Thread.sleep(RESTART_DELAY_MS)
            } else {
                attempts = 0
            }
        }
        finish("stopped")
    }

    /** Streams one `screenrecord` run. Returns true if it produced video before ending on its own. */
    private fun streamOnce(active: AdbSession): Boolean {
        val command = AdbCommands.screenRecord()
        val stream = active.open("exec:$command") ?: return false
        val splitter = AnnexBSplitter()
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var produced = false
        try {
            while (running.get()) {
                val read = stream.read(buffer, 0, buffer.size)
                if (read < 0) break
                produced = produced || read > 0
                for (nal in splitter.feed(buffer.copyOf(read))) {
                    decoder.submit(nal)
                    val width = decoder.width
                    val height = decoder.height
                    if (width > 0 && height > 0 && width != reportedWidth) {
                        reportedWidth = width
                        reportedHeight = height
                        listener.onVideoSize(width, height)
                    }
                }
            }
        } catch (error: Throwable) {
            listener.onStatus("video stopped: ${error.message}")
        } finally {
            stream.close()
        }
        return produced
    }

    private fun finish(reason: String) {
        running.set(false)
        session?.close()
        session = null
        listener.onEnded(reason)
    }

    private var reportedWidth = 0
    private var reportedHeight = 0

    companion object {
        private const val READ_CHUNK_BYTES = 64 * 1024
        private const val MAX_RESTARTS = 5
        private const val RESTART_DELAY_MS = 400L
        private const val COMMAND_TIMEOUT_MS = 2_000
        private const val TAP_SLOP_PX = 12
        private const val TAP_TIMEOUT_MS = 350
    }
}
