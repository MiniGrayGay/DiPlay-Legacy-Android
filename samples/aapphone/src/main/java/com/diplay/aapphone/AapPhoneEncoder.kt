package com.diplay.aapphone

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer

/**
 * Encodes the test pattern as H.264 and hands out an Annex-B elementary stream.
 *
 * A real phone encodes its projected UI; this one encodes a generated frame, which is enough to
 * prove every part of the head unit's video path: the parameter sets, the access units and their
 * timestamps, and whether the decoder keeps up.
 *
 * Two details are worth knowing because they decide whether the other side sees anything:
 *
 * - **Annex-B, not AVCC.** Android's encoders emit start-code-prefixed NAL units and put the SPS
 *   and PPS in `csd-0`/`csd-1` on the output format. Android Auto wants exactly that, so the config
 *   is the two `csd` buffers concatenated and each output buffer is one access unit.
 * - **Frames are paced by a clock, not by the output flags.** Feeding as fast as the encoder accepts
 *   would produce a burst the head unit's decoder can only drop; 20 fps matches what a small car
 *   screen needs and keeps the emulator's software encoder comfortable.
 */
class AapPhoneEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 20,
    private val bitRate: Int = 2_000_000,
    private val onCodecConfig: (ByteArray) -> Unit,
    private val onFrame: (bytes: ByteArray, presentationTimeUs: Long) -> Unit,
    private val log: (String) -> Unit = {},
) {

    val renderer = TestPatternRenderer(width, height)

    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var forceKeyFrame = false

    /**
     * How to lay out a raw input buffer: NV12 unless the encoder says it wants I420. Only consulted
     * when the codec refuses `getInputImage`.
     */
    private var semiPlanar = true

    /** Set once the output format arrives; re-sent when the head unit reclaims focus. */
    @Volatile
    var codecConfig: ByteArray? = null
        private set

    private var framesSent = 0

    fun start() {
        if (running) return
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // A key frame every second keeps a decoder that dropped one from staying broken, and is
            // what a real phone does for projection.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
        val created = try {
            MediaCodec.createEncoderByType(MIME)
        } catch (e: Exception) {
            log("no H.264 encoder on this device: ${e.message}")
            return
        }
        try {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
        } catch (e: Exception) {
            log("could not start the encoder: ${e.message}")
            runCatching { created.release() }
            return
        }
        codec = created
        running = true
        framesSent = 0
        semiPlanar = runCatching {
            created.inputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT) !=
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        }.getOrDefault(true)
        thread = Thread({ loop(created) }, "aap-encoder").apply {
            isDaemon = true
            start()
        }
        log("encoder started ${width}x$height@$fps")
    }

    /** Asks for an immediate key frame, re-sending the parameter sets first. */
    fun requestKeyFrame() {
        forceKeyFrame = true
        codecConfig?.let { onCodecConfig(it) }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        val current = codec ?: return
        codec = null
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    private fun loop(codec: MediaCodec) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val yuv = YuvFrame(width, height)
        val frameIntervalNanos = 1_000_000_000L / fps
        var frameIndex = 0L
        var nextFrameAt = System.nanoTime()
        var warnedNoImage = false
        val info = MediaCodec.BufferInfo()

        try {
            while (running) {
                val now = System.nanoTime()
                if (now < nextFrameAt) {
                    Thread.sleep(((nextFrameAt - now) / 1_000_000L).coerceAtLeast(1))
                    continue
                }
                nextFrameAt += frameIntervalNanos

                renderer.render(bitmap)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                yuv.fill(pixels)

                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val flags = if (forceKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    forceKeyFrame = false
                    val pts = frameIndex * 1_000_000L / fps
                    val image = codec.getInputImage(index)
                    if (image != null) {
                        yuv.writeTo(image)
                        codec.queueInputBuffer(index, 0, yuv.byteCount, pts, flags)
                        frameIndex++
                    } else {
                        val buffer = codec.getInputBuffer(index)
                        if (buffer == null || buffer.capacity() < yuv.byteCount) {
                            // Nothing to encode into: return the buffer unused rather than queue a
                            // zero-length frame, which the encoder silently swallows.
                            codec.queueInputBuffer(index, 0, 0, pts, 0)
                            if (!warnedNoImage) {
                                warnedNoImage = true
                                log("encoder input buffer too small: ${buffer?.capacity() ?: 0} < ${yuv.byteCount}")
                            }
                        } else {
                            if (!warnedNoImage) {
                                warnedNoImage = true
                                log("encoder takes raw YUV buffers; writing NV12 by hand")
                            }
                            yuv.writeTo(buffer, semiPlanar)
                            codec.queueInputBuffer(index, 0, yuv.byteCount, pts, flags)
                            frameIndex++
                        }
                    }
                }
                drain(codec, info)
            }
        } catch (e: InterruptedException) {
            // stop() interrupts the pacing sleep; the loop condition already says why.
        } catch (e: Exception) {
            log("encoder loop failed: ${e.message}")
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val config = parameterSets(codec.outputFormat)
                    if (config != null && config.isNotEmpty()) {
                        codecConfig = config
                        onCodecConfig(config)
                        log("codec config ${config.size} bytes")
                    }
                }

                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes, 0, info.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            onFrame(bytes, info.presentationTimeUs)
                            framesSent++
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }

                else -> return
            }
        }
    }

    /** Concatenates `csd-0` and `csd-1`, which carry SPS and PPS with start codes. */
    private fun parameterSets(format: MediaFormat): ByteArray? {
        val sps = format.getByteBuffer("csd-0")?.let { copyOf(it) }
        val pps = format.getByteBuffer("csd-1")?.let { copyOf(it) }
        return when {
            sps == null && pps == null -> null
            sps == null -> pps
            pps == null -> sps
            else -> sps + pps
        }
    }

    private fun copyOf(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        val out = ByteArray(copy.remaining())
        copy.get(out)
        return out
    }

    private companion object {
        const val MIME = "video/avc"
    }
}

/**
 * Converts ARGB pixels to the YUV 4:2:0 image a MediaCodec encoder expects.
 *
 * The conversion is BT.601 with studio swing (16–235 for luma), which is what every Android encoder
 * assumes. Full-range values instead make the picture look washed out on a car display, and the
 * difference is visible enough that reviewers report it as a colour bug.
 */
private class YuvFrame(private val width: Int, private val height: Int) {

    private val y = ByteArray(width * height)
    private val u = ByteArray(width * height / 4)
    private val v = ByteArray(width * height / 4)

    val byteCount: Int get() = y.size + u.size + v.size

    fun fill(pixels: IntArray) {
        var yIndex = 0
        var uvIndex = 0
        for (row in 0 until height) {
            for (column in 0 until width) {
                val color = pixels[row * width + column]
                val red = (color shr 16) and 0xff
                val green = (color shr 8) and 0xff
                val blue = color and 0xff
                val luma = ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16
                y[yIndex++] = luma.coerceIn(0, 255).toByte()
                // Chroma is sampled from the top-left pixel of each 2x2 block; the pattern is made
                // of large flat areas, so the loss is invisible and the saving is half the work.
                if (row % 2 == 0 && column % 2 == 0) {
                    val cb = ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128
                    val cr = ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128
                    u[uvIndex] = cb.coerceIn(0, 255).toByte()
                    v[uvIndex] = cr.coerceIn(0, 255).toByte()
                    uvIndex++
                }
            }
        }
    }

    /** Copies the planes into a codec's input [android.media.Image], honouring its strides. */
    fun writeTo(image: android.media.Image) {        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (column in 0 until width) {
                yBuffer.put(rowStart + column * yPlane.pixelStride, y[row * width + column])
            }
        }

        val chromaRows = height / 2
        val chromaColumns = width / 2
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (row in 0 until chromaRows) {
            val uStart = row * uPlane.rowStride
            val vStart = row * vPlane.rowStride
            for (column in 0 until chromaColumns) {
                val index = row * chromaColumns + column
                uBuffer.put(uStart + column * uPlane.pixelStride, u[index])
                vBuffer.put(vStart + column * vPlane.pixelStride, v[index])
            }
        }
    }

    /**
     * Copies the planes into a flat input buffer, for encoders that hand out bytes instead of an
     * `Image`.
     *
     * The emulator's software H.264 encoder is one of them: `getInputImage` returns null there, and
     * a caller that queues the empty buffer instead gets no error and no frames — the session looks
     * connected and the car stays black. Semi-planar (NV12, chroma interleaved U then V) is what
     * essentially every Android encoder accepts; planar (I420) is the fallback.
     */
    fun writeTo(buffer: ByteBuffer, semiPlanar: Boolean) {
        buffer.clear()
        buffer.put(y)
        if (semiPlanar) {
            for (index in u.indices) {
                buffer.put(u[index])
                buffer.put(v[index])
            }
        } else {
            buffer.put(u)
            buffer.put(v)
        }
    }
}
