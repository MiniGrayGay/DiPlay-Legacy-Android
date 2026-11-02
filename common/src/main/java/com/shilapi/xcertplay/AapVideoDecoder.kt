package com.shilapi.xcertplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes the phone's H.264 stream onto a [Surface].
 *
 * Android Auto sends Annex-B H.264: `CODEC_CONFIG` carries SPS and PPS with start codes, and every
 * `DATA` message is one access unit. MediaCodec wants the parameter sets as `csd-0`/`csd-1` on the
 * format rather than as buffers, so they are parsed out and the codec is configured once both are
 * known — which is why nothing is configured until the config arrives.
 *
 * Output goes straight to the surface (`render = true`), so there is no output loop: the decoder
 * hands frames to the compositor on its own. The only thing that has to be watched is the input
 * side, which is where frames are dropped when the decoder is behind — dropping is the right answer
 * for video, since a late frame carries no information the next one does not.
 */
class AapVideoDecoder(
    private val log: (String) -> Unit = {},
    /** Called once, when the decoder has actually put a frame on the surface. */
    private val onFirstFrame: () -> Unit = {},
) {

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /** Frames that arrived with no input buffer free. */
    var dropped = 0
        private set

    /** True once the codec has been started and is taking frames. */
    val running: Boolean get() = codec != null

    /** Sets the surface and the size the phone announced. Reconfigures when either changes. */
    @Synchronized
    fun setSurface(surface: Surface, width: Int, height: Int) {
        val changed = this.surface !== surface || this.width != width || this.height != height
        if (!changed) return
        release()
        this.surface = surface
        this.width = width
        this.height = height
        // Parameter sets belong to the stream, not the surface, but a resolution change comes with
        // new ones; waiting for them again is cheaper than decoding with the old ones.
        sps = null
        pps = null
        startIfReady()
    }

    /** Feeds `CODEC_CONFIG`: Annex-B SPS and/or PPS. */
    @Synchronized
    fun setCodecConfig(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        for (nal in AnnexB.split(data, offset, length)) {
            when (nal.type) {
                // The start code goes back on: what an encoder puts in `csd-0` is the whole NAL
                // unit including it, and decoders that receive a bare SPS without one accept the
                // config, report no error, and then never output a frame.
                7 -> sps = AnnexB.withStartCode(nal.bytes)
                8 -> pps = AnnexB.withStartCode(nal.bytes)
                else -> Unit
            }
        }
        log("codec config: sps=${sps?.size ?: 0}B pps=${pps?.size ?: 0}B")
        startIfReady()
    }

    /** Queues one access unit, in microseconds, as the phone stamped it. */
    @Synchronized
    fun queue(data: ByteArray, offset: Int, length: Int, presentationTimeUs: Long) {
        if (!loggedFirstUnit) {
            loggedFirstUnit = true
            // NAL types say most of it: 5 is an IDR, 7/8 are the parameter sets, 6 is SEI. A first
            // unit without a 5 decodes to nothing until the next key frame.
            log("first access unit NALs ${AnnexB.split(data, offset, length).map { it.type }}")
        }
        val codec = codec ?: run {
            // No parameter sets yet. Some phones send the first key frame complete with in-band SPS
            // and PPS; feeding it as a codec-config buffer is the only way to recover from that.
            if (sps == null && AnnexB.hasParameterSets(data, offset, length)) {
                setCodecConfig(data, offset, length)
            }
            codec ?: return
        }
        val index = try {
            codec.dequeueInputBuffer(0)
        } catch (e: IllegalStateException) {
            log("decoder went away: ${e.message}")
            release()
            return
        }
        if (index < 0) {
            dropped++
            return
        }
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        if (length > buffer.capacity()) {
            log("frame of $length bytes does not fit the decoder buffer")
            dropped++
            codec.queueInputBuffer(index, 0, 0, presentationTimeUs, 0)
            return
        }
        buffer.put(data, offset, length)
        codec.queueInputBuffer(index, 0, length, presentationTimeUs, 0)
        drain(codec)
    }

    /** Drops the decoder so the next frame reconfigures. Used when focus returns. */
    @Synchronized
    fun reset() {
        release()
        startIfReady()
    }

    @Synchronized
    fun release() {
        val current = codec ?: return
        codec = null
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    /**
     * Releases every decoded frame the codec has ready, rendering each one.
     *
     * This is not optional even though the codec was configured with a surface: a surface-backed
     * decoder shows nothing until the frame is released with `render = true`, and leaving the loop
     * out produces the worst possible symptom — frames counting up on the input side while the
     * screen stays black.
     */
    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (e: IllegalStateException) {
                log("decoder went away: ${e.message}")
                release()
                return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> log("decoder output format changed")
                index >= 0 -> {
                    codec.releaseOutputBuffer(index, true)
                    if (!reportedFirstFrame) {
                        reportedFirstFrame = true
                        log("first frame on screen")
                        onFirstFrame()
                    }
                }
                else -> return
            }
        }
    }

    private var reportedFirstFrame = false
    private var loggedFirstUnit = false

    private fun startIfReady() {
        val surface = surface ?: return
        val sps = sps ?: return
        val pps = pps ?: return
        if (codec != null) return
        try {
            val format = MediaFormat.createVideoFormat(MIME, width.coerceAtLeast(16), height.coerceAtLeast(16))
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            // The stream is already low-latency; asking the decoder to hold frames back would only
            // add latency the driver feels on every touch.
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            val created = MediaCodec.createDecoderByType(MIME)
            created.configure(format, surface, null, 0)
            created.start()
            codec = created
            log("decoder started for ${width}x$height")
        } catch (e: Exception) {
            log("could not start the decoder: ${e.message}")
        }
    }

    private companion object {
        const val MIME = "video/avc"
    }
}

/** One NAL unit found in an Annex-B stream. */
class AnnexBNal(val type: Int, val bytes: ByteArray)

/**
 * Splits Annex-B byte streams into NAL units.
 *
 * Both start codes (`00 00 01` and the four-byte `00 00 00 01`) occur in Android Auto streams, often
 * in the same buffer, so the scanner looks for the three-byte form and treats a preceding zero as
 * part of the same start code.
 */
object AnnexB {

    fun split(data: ByteArray, offset: Int, length: Int): List<AnnexBNal> {
        val out = ArrayList<AnnexBNal>(4)
        val end = offset + length
        var start = findStartCode(data, offset, end)
        while (start >= 0) {
            // The three-byte pattern is what the scan finds, so a four-byte start code leaves one
            // zero at the end of the previous unit. Annex-B allows trailing zeros there, but
            // trimming them keeps the bytes handed to the decoder identical to what the encoder
            // produced.
            val headerStart = start + START_CODE_SIZE
            val next = findStartCode(data, headerStart, end)
            var nalEnd = if (next < 0) end else next
            if (next >= 0) {
                while (nalEnd > headerStart && data[nalEnd - 1].toInt() == 0) nalEnd--
            }
            if (headerStart < nalEnd) {
                val type = data[headerStart].toInt() and 0x1f
                out += AnnexBNal(type, data.copyOfRange(headerStart, nalEnd))
            }
            start = next
        }
        return out
    }

    /** Whether [data] carries an SPS or PPS, which is what makes it usable as codec config. */
    fun hasParameterSets(data: ByteArray, offset: Int, length: Int): Boolean =
        split(data, offset, length).any { it.type == 7 || it.type == 8 }

    /** Four-byte Annex-B start code plus the unit, which is the form `csd-0` expects. */
    fun withStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(4 + nal.size)
        out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }

    private fun findStartCode(data: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i
            i++
        }
        return -1
    }

    private const val START_CODE_SIZE = 3
}
