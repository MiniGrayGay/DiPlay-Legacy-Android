package com.shilapi.xcertplay.aa

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

/**
 * H.264 decoder for the receiver.
 *
 * Every call except [setSurface] happens on the receiver's decode thread. The surface, on the other
 * hand, is handed over by the UI thread, so it is read through a volatile field and compared by
 * identity: a recreated SurfaceView surface forces a fresh codec instead of decoding into a dead one.
 */
internal class AaVideoDecoder(private val log: (String) -> Unit) {

    @Volatile
    private var surface: Surface? = null

    private var codec: MediaCodec? = null
    private var configuredSurface: Surface? = null
    private var width = 0
    private var height = 0
    private var csd: ByteArray? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Stream size reported by the decoder once it has seen the first frame. */
    @Volatile
    var streamWidth = 0
        private set

    @Volatile
    var streamHeight = 0
        private set

    /** Frames handed to the surface; the receiver shows this so a stalled decoder is obvious. */
    @Volatile
    var renderedFrames = 0L
        private set

    fun setSurface(value: Surface?) {
        surface = value
    }

    /** [payload] is `width, height, csd-0` as produced by the sender. */
    fun setConfig(payload: ByteArray) {
        if (payload.size <= 8) {
            csd = null
            return
        }
        width = com.shilapi.xcertplay.mirroring.aa.AaWire.getInt(payload, 0)
        height = com.shilapi.xcertplay.mirroring.aa.AaWire.getInt(payload, 4)
        csd = payload.copyOfRange(8, payload.size)
    }

    fun submit(payload: ByteArray, ptsUs: Long) {
        val target = surface ?: return
        if (codec == null || configuredSurface !== target) {
            if (!configure(target)) return
        }
        val codec = this.codec ?: return
        try {
            if (!feed(codec, payload, ptsUs)) return
            drain(codec)
        } catch (error: Throwable) {
            log("decode failed: ${error.message}")
            release()
        }
    }

    private fun configure(target: Surface): Boolean {
        release()
        if (width <= 0 || height <= 0) {
            log("decode skipped: stream size unknown")
            return false
        }
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            csd?.let { format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(it)) }
            val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            created.configure(format, target, null, 0)
            created.start()
            codec = created
            configuredSurface = target
            log("decoder started ${width}x$height")
            true
        } catch (error: Throwable) {
            log("decoder unavailable: ${error.message}")
            release()
            false
        }
    }

    private fun feed(codec: MediaCodec, payload: ByteArray, ptsUs: Long): Boolean {
        var attempts = 0
        while (attempts < 4) {
            val index = codec.dequeueInputBuffer(4_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index) ?: return false
                buffer.clear()
                buffer.put(payload)
                codec.queueInputBuffer(index, 0, payload.size, ptsUs, 0)
                return true
            }
            // A full input queue means the surface has not consumed its output yet.
            drain(codec)
            attempts++
        }
        log("decode dropped: input queue full")
        return false
    }

    private fun drain(codec: MediaCodec) {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    streamWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    streamHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    log("decoder output ${streamWidth}x$streamHeight")
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (index < 0) return
                    val render = bufferInfo.size > 0
                    codec.releaseOutputBuffer(index, render)
                    if (render) renderedFrames++
                }
            }
        }
    }

    fun stop() {
        release()
        surface = null
    }

    private fun release() {
        val previous = codec ?: return
        codec = null
        configuredSurface = null
        try {
            previous.stop()
        } catch (_: Throwable) {
        }
        try {
            previous.release()
        } catch (_: Throwable) {
        }
    }
}
