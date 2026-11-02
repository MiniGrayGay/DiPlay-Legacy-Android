package com.shilapi.xcertplay.adb

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes the Annex-B H.264 that `screenrecord` streams, straight into a [Surface].
 *
 * Two things differ from the decoder of the DiPlay-on-the-phone path. The parameter sets are not sent
 * separately — they are NAL units 7 and 8 inside the stream, so they are lifted out and handed to
 * `MediaCodec` as `csd-0`/`csd-1` the first time both have been seen. And the encoded size is not known
 * before the first frame, so the codec is configured with a placeholder and the real size is read back
 * from `INFO_OUTPUT_FORMAT_CHANGED`; that size is what the head unit maps touches against.
 */
internal class AnnexBDecoder(private val log: (String) -> Unit) {

    @Volatile
    private var surface: Surface? = null

    private var codec: MediaCodec? = null
    private var configuredSurface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var presentationUs = 0L
    private val bufferInfo = MediaCodec.BufferInfo()

    /** Encoded size of the phone's screen in its current rotation, once the codec has said so. */
    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    @Volatile
    var renderedFrames = 0L
        private set

    /** Set when the codec gave up; the caller reconfigures on the next parameter sets. */
    @Volatile
    var failed = false
        private set

    fun setSurface(value: Surface?) {
        surface = value
    }

    /** Feeds one complete NAL unit. Safe to call before the surface exists. */
    fun submit(nal: ByteArray) {
        when (AnnexB.nalType(nal)) {
            AnnexB.TYPE_SPS -> sps = nal
            AnnexB.TYPE_PPS -> pps = nal
        }
        val target = surface ?: return
        if (codec == null || configuredSurface !== target || failed) {
            if (!configure(target)) return
        }
        val codec = this.codec ?: return
        try {
            if (!feed(codec, nal, presentationUs)) return
            presentationUs += FRAME_INTERVAL_US
            drain(codec)
        } catch (error: Throwable) {
            log("h264 decode failed: ${error.message}")
            release()
            failed = true
        }
    }

    fun release() {
        val current = codec
        codec = null
        configuredSurface = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    private fun configure(target: Surface): Boolean {
        val sps = sps ?: return false
        val pps = pps ?: return false
        release()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, PLACEHOLDER_WIDTH, PLACEHOLDER_HEIGHT)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(AnnexB.withStartCode(sps)))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(AnnexB.withStartCode(pps)))
        val created = runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, target, null, 0)
                start()
            }
        }.getOrElse { error ->
            log("h264 decoder unavailable: ${error.message}")
            failed = true
            return false
        }
        codec = created
        configuredSurface = target
        failed = false
        presentationUs = 0
        return true
    }

    private fun feed(codec: MediaCodec, nal: ByteArray, ptsUs: Long): Boolean {
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index) ?: return false
        buffer.clear()
        buffer.put(AnnexB.withStartCode(nal))
        codec.queueInputBuffer(index, 0, buffer.position(), ptsUs, 0)
        return true
    }

    private fun drain(codec: MediaCodec) {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    width = format.getInteger(MediaFormat.KEY_WIDTH)
                    height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    log("h264 stream ${width}x$height")
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (index < 0) return
                    // Every buffer is rendered: the surface owns the timing, not this loop.
                    codec.releaseOutputBuffer(index, true)
                    renderedFrames++
                }
            }
        }
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val FRAME_INTERVAL_US = 16_666L
        private const val PLACEHOLDER_WIDTH = 1280
        private const val PLACEHOLDER_HEIGHT = 720
    }
}
