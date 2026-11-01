package com.shilapi.xcertplay.aa

import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.view.Surface
import com.shilapi.xcertplay.mirroring.aa.AaWire
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater

/**
 * The phone half's producer: renders a test pattern and encodes it, either as H.264 through a
 * `MediaCodec` input surface or as deflated ARGB for devices whose encoder refuses to start.
 *
 * The pattern is deliberately unmistakable — a moving bar, the frame counter, and a square that
 * follows the head unit's touch — because the point of the loopback pair is to prove three things at
 * a glance: video flows, it is live, and input comes back.
 */
internal class AaSenderPipeline(
    private val codecKind: Int,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val sink: Sink,
    private val log: (String) -> Unit,
) {
    interface Sink {
        /** `width, height, csd-0`; sent once before the first frame. */
        fun onConfig(payload: ByteArray)
        fun onFrame(codec: Int, ptsUs: Long, payload: ByteArray)
        fun onPacingTick(sentFrames: Long, bytes: Long)
    }

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var encoder: MediaCodec? = null

    @Volatile
    private var touchX = 0.5f

    @Volatile
    private var touchY = 0.5f

    /** Frames produced so far, whatever the codec. */
    @Volatile
    var frameCount = 0L
        private set

    @Volatile
    var bytesSent = 0L
        private set

    @Volatile
    private var lastTouchAt = 0L

    fun setTouch(x: Float, y: Float) {
        touchX = x.coerceIn(0f, 1f)
        touchY = y.coerceIn(0f, 1f)
        lastTouchAt = System.currentTimeMillis()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ run() }, "aa-sender").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.interrupt()
        worker = null
        releaseEncoder()
    }

    private fun run() {
        try {
            if (codecKind == AaWire.Codec.H264) {
                runH264()
            } else {
                runRaw()
            }
        } catch (error: Throwable) {
            log("pipeline stopped: ${error.message}")
        } finally {
            running.set(false)
            releaseEncoder()
        }
    }

    // --- H.264: MediaCodec input surface fed by GLES ---

    private fun runH264() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            // 1.5 Mbit/s is enough for a 360p test pattern and stays inside an emulator's budget.
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // A key frame every second means a receiver that was not ready recovers immediately.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = created.createInputSurface()
        created.start()
        encoder = created

        val gl = EglWindow(inputSurface)
        val info = MediaCodec.BufferInfo()
        val frameIntervalNanos = 1_000_000_000L / fps
        var nextFrame = System.nanoTime()
        val startedAt = System.nanoTime()

        while (running.get()) {
            val now = System.nanoTime()
            if (now < nextFrame) {
                // Keep the encoder's output flowing while waiting for the next frame.
                drain(created, info)
                Thread.sleep(1)
                continue
            }
            nextFrame = now + frameIntervalNanos
            drawPattern(now - startedAt)
            gl.present(now)
            drain(created, info)
        }
        gl.release()
        inputSurface.release()
    }

    /**
     * Pulls whatever the encoder has ready. The codec config buffer arrives once, before the first
     * slice, and is forwarded as CONFIG so the receiver has `csd-0` when it configures its decoder.
     */
    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) return
            val buffer: ByteBuffer? = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val payload = ByteArray(info.size)
                buffer.get(payload)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    val config = ByteArray(8 + payload.size)
                    AaWire.putInt(config, 0, width)
                    AaWire.putInt(config, 4, height)
                    System.arraycopy(payload, 0, config, 8, payload.size)
                    sink.onConfig(config)
                } else {
                    emit(AaWire.Codec.H264, info.presentationTimeUs, payload)
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    // --- Raw: CPU pattern, deflated ---

    private fun runRaw() {
        val pixels = IntArray(width * height)
        val raw = ByteArray(width * height * 4)
        val deflater = Deflater(Deflater.BEST_SPEED)
        val frameIntervalMs = (1000L / fps).coerceAtLeast(16L)
        val startedAt = System.nanoTime()
        while (running.get()) {
            val began = System.currentTimeMillis()
            fillPattern(pixels, startedAt)
            val buffer = ByteBuffer.wrap(raw)
            buffer.asIntBuffer().put(pixels)
            deflater.reset()
            deflater.setInput(raw)
            deflater.finish()
            val compressed = ByteArray(raw.size + 64)
            val length = deflater.deflate(compressed)
            val payload = ByteArray(8 + length)
            AaWire.putInt(payload, 0, width)
            AaWire.putInt(payload, 4, height)
            System.arraycopy(compressed, 0, payload, 8, length)
            emit(AaWire.Codec.NONE, (System.nanoTime() - startedAt) / 1000, payload)
            val elapsed = System.currentTimeMillis() - began
            if (elapsed < frameIntervalMs) Thread.sleep(frameIntervalMs - elapsed)
        }
        deflater.end()
    }

    private fun emit(codec: Int, ptsUs: Long, payload: ByteArray) {
        frameCount++
        bytesSent += payload.size
        sink.onFrame(codec, ptsUs, payload)
        if (frameCount % (fps * 2L) == 0L) sink.onPacingTick(frameCount, bytesSent)
    }

    // --- Pattern ---

    /** CPU version, used by the raw path; mirrors [drawPattern]. */
    private fun fillPattern(pixels: IntArray, elapsedNanos: Long) {
        val seconds = elapsedNanos / 1_000_000_000.0
        val barX = ((seconds * 0.35 * width) % width).toInt()
        val background = Color.HSVToColor(floatArrayOf(((seconds * 30) % 360).toFloat(), 0.55f, 0.35f))
        java.util.Arrays.fill(pixels, background)
        val barWidth = (width / 16).coerceAtLeast(8)
        for (x in barX until (barX + barWidth).coerceAtMost(width)) {
            for (y in 0 until height) {
                pixels[y * width + x] = Color.WHITE
            }
        }
        val markerSize = (minOf(width, height) / 10).coerceAtLeast(12)
        val markerX = (touchX * width).toInt()
        val markerY = (touchY * height).toInt()
        val pressAge = System.currentTimeMillis() - lastTouchAt
        val marker = if (pressAge < 1_500) Color.YELLOW else Color.CYAN
        for (y in (markerY - markerSize)..(markerY + markerSize)) {
            for (x in (markerX - markerSize)..(markerX + markerSize)) {
                if (x in 0 until width && y in 0 until height) pixels[y * width + x] = marker
            }
        }
    }

    /** GLES version, used by the H.264 path: solid clears through scissor rectangles, no shaders. */
    private fun drawPattern(elapsedNanos: Long) {
        val seconds = elapsedNanos / 1_000_000_000.0
        val background = Color.HSVToColor(floatArrayOf(((seconds * 30) % 360).toFloat(), 0.55f, 0.35f))
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(
            Color.red(background) / 255f,
            Color.green(background) / 255f,
            Color.blue(background) / 255f,
            1f,
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        val barWidth = (width / 16).coerceAtLeast(8)
        val barX = ((seconds * 0.35 * width) % width).toInt()
        // GL's origin is bottom-left; a full-height bar makes the flip irrelevant.
        GLES20.glScissor(barX, 0, barWidth.coerceAtMost(width - barX), height)
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val markerSize = (minOf(width, height) / 10).coerceAtLeast(12)
        val markerX = (touchX * width).toInt()
        val markerY = (height - (touchY * height).toInt())
        val pressAge = System.currentTimeMillis() - lastTouchAt
        val marker = if (pressAge < 1_500) Color.YELLOW else Color.CYAN
        GLES20.glScissor(
            (markerX - markerSize).coerceIn(0, width - 1),
            (markerY - markerSize).coerceIn(0, height - 1),
            (markerSize * 2).coerceAtMost(width),
            (markerSize * 2).coerceAtMost(height),
        )
        GLES20.glClearColor(
            Color.red(marker) / 255f,
            Color.green(marker) / 255f,
            Color.blue(marker) / 255f,
            1f,
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun releaseEncoder() {
        val codec = encoder ?: return
        encoder = null
        try {
            codec.stop()
        } catch (_: Throwable) {
        }
        try {
            codec.release()
        } catch (_: Throwable) {
        }
    }

    /** Minimal EGL window bound to the encoder's input surface. */
    private class EglWindow(surface: Surface) {
        private val display: EGLDisplay
        private val context: EGLContext
        private val window: EGLSurface

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw IllegalStateException("eglInitialize failed")
            }
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
            val config = configs[0] ?: throw IllegalStateException("no EGL config")
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (window == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException("eglCreateWindowSurface failed")
            }
            if (!EGL14.eglMakeCurrent(display, window, window, context)) {
                throw IllegalStateException("eglMakeCurrent failed")
            }
        }

        fun present(frameTimeNanos: Long) {
            // The encoder uses this as presentationTimeUs, so the receiver's pacing stays honest.
            EGLExt.eglPresentationTimeANDROID(display, window, frameTimeNanos)
            EGL14.eglSwapBuffers(display, window)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
