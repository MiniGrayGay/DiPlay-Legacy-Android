package com.shilapi.xcertplay.mirroring.aa

/**
 * The framing shared by the phone side (sender) and the head unit side (receiver).
 *
 * Every message is a fixed 18-byte header followed by its payload. The header is deliberately
 * small and byte-aligned so the same code decodes from a TCP stream today and from a USB bulk
 * endpoint later; nothing here touches Android.
 *
 * ```
 * 0..1  'A' 'A'      magic, lets a reader resynchronise after garbage
 * 2     version      [VERSION]
 * 3     type         one of [Type]
 * 4     codec        [Codec] for VIDEO, 0 otherwise
 * 5     flags        reserved, must be 0
 * 6..13 ptsUs        sender timestamp, big endian
 * 14..17 payloadLen  big endian, guarded by [MAX_PAYLOAD]
 * ```
 */
object AaWire {
    const val MAGIC_0 = 'A'.code
    const val MAGIC_1 = 'A'.code
    const val VERSION = 1
    const val HEADER_SIZE = 18

    /** A key frame from a 1080p screen is a few hundred KiB; 4 MiB leaves room without inviting OOM. */
    const val MAX_PAYLOAD = 4 * 1024 * 1024

    object Type {
        const val HELLO = 1
        const val HELLO_ACK = 2
        const val CONFIG = 3
        const val VIDEO = 4
        const val TOUCH = 5
        const val KEY = 6
        const val PING = 7
        const val PONG = 8
        const val STATS = 9
        const val BYE = 10
    }

    object Codec {
        const val NONE = 0
        const val H264 = 1
    }

    object TouchAction {
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
    }

    fun encode(type: Int, codec: Int = Codec.NONE, ptsUs: Long = 0L, payload: ByteArray = EMPTY): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = MAGIC_0.toByte()
        out[1] = MAGIC_1.toByte()
        out[2] = VERSION.toByte()
        out[3] = type.toByte()
        out[4] = codec.toByte()
        out[5] = 0
        putLong(out, 6, ptsUs)
        putInt(out, 14, payload.size)
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    private val EMPTY = ByteArray(0)

    fun typeOf(header: ByteArray): Int = header[3].toInt() and 0xFF
    fun codecOf(header: ByteArray): Int = header[4].toInt() and 0xFF
    fun ptsOf(header: ByteArray): Long = getLong(header, 6)
    fun payloadLengthOf(header: ByteArray): Int = getInt(header, 14)

    fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    fun getInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)

    fun putLong(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            target[offset + i] = (value ushr ((7 - i) * 8)).toByte()
        }
    }

    fun getLong(source: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (source[offset + i].toLong() and 0xFF)
        }
        return value
    }
}

/** One decoded message. [payload] is a copy owned by the caller. */
class AaFrame(
    val type: Int,
    val codec: Int,
    val ptsUs: Long,
    val payload: ByteArray,
)

/**
 * Incremental decoder for [AaWire] frames over a byte stream.
 *
 * TCP splits writes wherever it likes, and a USB bulk pipe does the same, so the reader must accept
 * a frame arriving in any number of pieces. Bytes that do not start with the magic are dropped one
 * at a time until it resynchronises, which keeps a half-written frame from wedging the session.
 */
class AaFrameReader {
    private var buffer = ByteArray(8 * 1024)
    private var size = 0

    /** Bytes dropped so far because they did not belong to a well-formed frame. */
    var skippedBytes: Long = 0L
        private set

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<AaFrame> {
        if (length <= 0) return emptyList()
        ensure(size + length)
        System.arraycopy(bytes, offset, buffer, size, length)
        size += length
        val frames = ArrayList<AaFrame>(4)
        var consumed = 0
        while (true) {
            // Resynchronise on the magic first: a truncated write must not be mistaken for a header.
            while (consumed + 2 <= size &&
                !(buffer[consumed].toInt() and 0xFF == AaWire.MAGIC_0 &&
                    buffer[consumed + 1].toInt() and 0xFF == AaWire.MAGIC_1)
            ) {
                consumed++
                skippedBytes++
            }
            if (consumed + AaWire.HEADER_SIZE > size) break
            val version = buffer[consumed + 2].toInt() and 0xFF
            if (version != AaWire.VERSION) {
                // Only step one byte: the next magic may start inside this bogus header.
                consumed++
                skippedBytes++
                continue
            }
            val length = AaWire.payloadLengthOf(buffer.copyOfRange(consumed, consumed + AaWire.HEADER_SIZE))
            if (length < 0 || length > AaWire.MAX_PAYLOAD) {
                consumed++
                skippedBytes++
                continue
            }
            if (consumed + AaWire.HEADER_SIZE + length > size) break
            val header = buffer.copyOfRange(consumed, consumed + AaWire.HEADER_SIZE)
            val payload = buffer.copyOfRange(
                consumed + AaWire.HEADER_SIZE,
                consumed + AaWire.HEADER_SIZE + length,
            )
            frames.add(AaFrame(AaWire.typeOf(header), AaWire.codecOf(header), AaWire.ptsOf(header), payload))
            consumed += AaWire.HEADER_SIZE + length
        }
        if (consumed > 0) {
            System.arraycopy(buffer, consumed, buffer, 0, size - consumed)
            size -= consumed
        }
        return frames
    }

    private fun ensure(required: Int) {
        if (buffer.size >= required) return
        var capacity = buffer.size
        while (capacity < required) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }
}
