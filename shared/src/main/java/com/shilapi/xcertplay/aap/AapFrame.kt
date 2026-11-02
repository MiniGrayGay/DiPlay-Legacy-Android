package com.shilapi.xcertplay.aap

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * AAP (Android Auto Projection) framing.
 *
 * Every message travels in one or more frames:
 *
 * ```
 * byte 0      channel id
 * byte 1      flags: bit0 FIRST, bit1 LAST, bit2 CONTROL, bit3 ENCRYPTED
 * bytes 2-3   frame payload size, big endian
 * bytes 4-7   only on the FIRST frame of a fragmented message (FIRST set, LAST clear):
 *             total plaintext size of the whole message, big endian
 * payload     plaintext, or TLS records once encrypted
 * ```
 *
 * The reassembled plaintext starts with a 2-byte big-endian message id followed by the protobuf
 * body. Media and TLS handshake messages carry raw bytes after the id instead of protobuf.
 *
 * The header is big endian throughout. That is the one detail that is easy to get wrong when
 * porting from the C++ head units, which use host order for their own framing but must match
 * this for AAP.
 */
object AapFrame {
    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02
    const val FLAG_CONTROL = 0x04
    const val FLAG_ENCRYPTED = 0x08

    /** Channel 0 is the control channel; every service channel gets a number assigned at open. */
    const val CONTROL_CHANNEL = 0

    /**
     * Largest plaintext chunk in a single frame.
     *
     * The official phone caps a whole frame at 16128 bytes including the header, and some head
     * units allocate 16 KiB receive buffers per frame, so staying under that avoids a short read
     * that looks like a desync.
     */
    const val MAX_PAYLOAD = 16128 - 8

    /** Width of the message id that prefixes every reassembled message. */
    const val ID_SIZE = 2

    /** Bytes a frame header always has: channel + flags + 2-byte size. */
    const val BASE_HEADER = 4

    /** A fragmented message's first frame also carries the 4-byte total size. */
    const val EXTENDED_HEADER = 8

    /**
     * Refuse to reassemble anything larger than this. A corrupted length field would otherwise
     * make the assembler allocate until the process dies.
     */
    const val MAX_MESSAGE = 1 shl 20

    fun isFirst(flags: Int) = flags and FLAG_FIRST != 0
    fun isLast(flags: Int) = flags and FLAG_LAST != 0
    fun isControl(flags: Int) = flags and FLAG_CONTROL != 0
    fun isEncrypted(flags: Int) = flags and FLAG_ENCRYPTED != 0

    /** First fragment of a multi-frame message: it carries the extended (total size) field. */
    fun hasTotalSize(flags: Int) = isFirst(flags) && !isLast(flags)

    /**
     * Splits one message into wire frames.
     *
     * [plaintext] already includes the 2-byte message id, because the id is part of what gets
     * fragmented and encrypted.
     */
    fun encode(channel: Int, control: Boolean, encrypted: Boolean, plaintext: ByteArray): List<ByteArray> {
        if (plaintext.size <= MAX_PAYLOAD) {
            var flags = FLAG_FIRST or FLAG_LAST
            if (control) flags = flags or FLAG_CONTROL
            if (encrypted) flags = flags or FLAG_ENCRYPTED
            return listOf(frame(channel, flags, plaintext.size, -1, plaintext, 0, plaintext.size))
        }
        val out = ArrayList<ByteArray>(plaintext.size / MAX_PAYLOAD + 1)
        var offset = 0
        var first = true
        while (offset < plaintext.size) {
            val chunk = minOf(MAX_PAYLOAD, plaintext.size - offset)
            val last = offset + chunk == plaintext.size
            var flags = 0
            if (first) flags = flags or FLAG_FIRST
            if (last) flags = flags or FLAG_LAST
            if (control) flags = flags or FLAG_CONTROL
            if (encrypted) flags = flags or FLAG_ENCRYPTED
            // Only the first fragment of a multi-frame message carries the total size.
            val total = if (first && !last) plaintext.size else -1
            out += frame(channel, flags, chunk, total, plaintext, offset, chunk)
            offset += chunk
            first = false
        }
        return out
    }

    private fun frame(
        channel: Int,
        flags: Int,
        size: Int,
        totalSize: Int,
        source: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        val header = if (totalSize >= 0) EXTENDED_HEADER else BASE_HEADER
        val out = ByteArray(header + length)
        out[0] = channel.toByte()
        out[1] = flags.toByte()
        out[2] = (size shr 8).toByte()
        out[3] = size.toByte()
        if (totalSize >= 0) {
            out[4] = (totalSize ushr 24).toByte()
            out[5] = (totalSize ushr 16).toByte()
            out[6] = (totalSize ushr 8).toByte()
            out[7] = totalSize.toByte()
        }
        System.arraycopy(source, offset, out, header, length)
        return out
    }

    /** Builds the plaintext of a message: 2-byte big-endian id followed by the body. */
    fun plaintext(id: Int, body: ByteArray, offset: Int = 0, length: Int = body.size - offset): ByteArray {
        val out = ByteArray(ID_SIZE + length)
        out[0] = (id shr 8).toByte()
        out[1] = id.toByte()
        System.arraycopy(body, offset, out, ID_SIZE, length)
        return out
    }

    /** Reads the message id out of reassembled plaintext. */
    fun idOf(plaintext: ByteArray): Int {
        if (plaintext.size < ID_SIZE) throw IllegalArgumentException("message shorter than its id")
        return ((plaintext[0].toInt() and 0xff) shl 8) or (plaintext[1].toInt() and 0xff)
    }
}

/**
 * One frame as it appears on the wire.
 *
 * @param totalSize plaintext size of the whole message, or -1 when the frame carries no extended
 * header.
 */
class RawFrame(
    val channel: Int,
    val flags: Int,
    val totalSize: Int,
    val payload: ByteArray,
) {
    val control: Boolean get() = AapFrame.isControl(flags)
    val encrypted: Boolean get() = AapFrame.isEncrypted(flags)

    /** Re-serialises the frame, which the tests use to check the round trip. */
    fun toWire(): ByteArray {
        val header = if (totalSize >= 0) AapFrame.EXTENDED_HEADER else AapFrame.BASE_HEADER
        val out = ByteArray(header + payload.size)
        out[0] = channel.toByte()
        out[1] = flags.toByte()
        out[2] = (payload.size shr 8).toByte()
        out[3] = payload.size.toByte()
        if (totalSize >= 0) {
            out[4] = (totalSize ushr 24).toByte()
            out[5] = (totalSize ushr 16).toByte()
            out[6] = (totalSize ushr 8).toByte()
            out[7] = totalSize.toByte()
        }
        System.arraycopy(payload, 0, out, header, payload.size)
        return out
    }

    override fun toString(): String =
        "RawFrame(ch=$channel, flags=0x${flags.toString(16)}, ${payload.size}B)"
}

/**
 * A complete message after fragmentation is undone.
 *
 * @param id message id, see [AapMessageIds]. For an encrypted message the id is inside the
 *   ciphertext, so the transport layer cannot report it: it is [UNKNOWN_ID] until the payload is
 *   decrypted, and [body] is the sealed payload verbatim.
 */
class AapMessage(
    val channel: Int,
    val control: Boolean,
    val encrypted: Boolean,
    val id: Int,
    val body: ByteArray,
) {
    override fun toString(): String =
        "AapMessage(ch=$channel, id=0x${id.toString(16)}, ${if (control) "ctl " else ""}" +
            "${if (encrypted) "enc " else ""}len=${body.size})"

    companion object {
        /** Id reported for a message whose payload has not been decrypted yet. */
        const val UNKNOWN_ID = 0
    }
}

/**
 * Reads frames off a stream and hands back complete messages.
 *
 * A USB bulk read and a TCP read both split at arbitrary points, so the header can arrive in
 * pieces and a start code style "read the whole frame" approach desyncs on the first busy link.
 */
class AapFrameReader(private val input: InputStream) {

    /** Returns null on a clean end of stream. Throws when the stream ends mid-frame. */
    fun nextFrame(): RawFrame? {
        val channel = input.read()
        if (channel < 0) return null
        val flags = input.read()
        if (flags < 0) throw EOFException("AAP stream ended inside a frame header")
        val size = readShort()
        if (size > AapFrame.MAX_PAYLOAD) {
            throw IllegalStateException("AAP frame size $size exceeds ${AapFrame.MAX_PAYLOAD}")
        }
        var total = -1
        if (AapFrame.hasTotalSize(flags)) {
            total = readInt()
            if (total <= 0 || total > AapFrame.MAX_MESSAGE) {
                throw IllegalStateException("AAP message size $total out of range")
            }
        }
        val payload = ByteArray(size)
        readFully(payload)
        return RawFrame(channel, flags, total, payload)
    }

    fun nextMessage(assembler: AapMessageAssembler): AapMessage? {
        while (true) {
            val frame = nextFrame() ?: return null
            val message = assembler.accept(frame)
            if (message != null) return message
        }
    }

    private fun readShort(): Int {
        val hi = input.read()
        if (hi < 0) throw EOFException("AAP stream ended inside a frame size")
        val lo = input.read()
        if (lo < 0) throw EOFException("AAP stream ended inside a frame size")
        return (hi shl 8) or lo
    }

    private fun readInt(): Int {
        var value = 0
        for (i in 0 until 4) {
            val b = input.read()
            if (b < 0) throw EOFException("AAP stream ended inside an extended header")
            value = (value shl 8) or b
        }
        return value
    }

    private fun readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) {
                throw EOFException("AAP frame truncated: ${target.size - offset} of ${target.size} bytes missing")
            }
            offset += read
        }
    }
}

/**
 * Undoes fragmentation.
 *
 * Not thread safe: one assembler belongs to one stream, fed by that stream's single reader.
 */
class AapMessageAssembler {

    private val buffer = ByteArrayOutputStream()
    private var channel = -1
    private var control = false
    private var encrypted = false
    private var expected = -1
    private var assembling = false

    /** Messages that started but never completed, or fragments with no first frame. */
    var dropped = 0
        private set

    fun accept(frame: RawFrame): AapMessage? {
        if (AapFrame.isFirst(frame.flags)) {
            // A new message while one is still open means the peer restarted mid-message; keeping
            // the stale bytes would glue two messages together.
            if (assembling) {
                dropped++
                buffer.reset()
            }
            channel = frame.channel
            control = frame.control
            encrypted = frame.encrypted
            expected = frame.totalSize
            assembling = true
        } else if (!assembling) {
            // Continuation without a first frame: nothing to attach it to.
            dropped++
            return null
        }
        buffer.write(frame.payload, 0, frame.payload.size)

        if (!AapFrame.isLast(frame.flags)) {
            if (expected > 0 && buffer.size() > expected) {
                // The peer lied about the size; drop rather than grow without bound.
                dropped++
                buffer.reset()
                assembling = false
            }
            return null
        }

        assembling = false
        val payload = buffer.toByteArray()
        buffer.reset()
        if (encrypted) {
            // An encrypted message carries a TLS record, not "id plus body". Its id is inside the
            // ciphertext, so there is nothing here to read it from: the first two bytes are the
            // record header. Splitting them off as an id would hand the engine a truncated record
            // and fail later with "Unrecognized record version", which points at TLS rather than
            // at framing.
            return AapMessage(channel, control, true, AapMessage.UNKNOWN_ID, payload)
        }
        if (payload.size < AapFrame.ID_SIZE) {
            dropped++
            return null
        }
        val id = AapFrame.idOf(payload)
        return AapMessage(channel, control, false, id, payload.copyOfRange(AapFrame.ID_SIZE, payload.size))
    }
}

/** Writes frames to a stream, flushing on demand for callers that need a timely handshake. */
class AapFrameWriter(private val output: OutputStream) {

    fun write(message: AapMessage, flush: Boolean = false) {
        // Plaintext only: an encrypted body is already a sealed record, and prefixing it with an id
        // would corrupt the record while looking like a normal write.
        require(!message.encrypted) {
            "an encrypted message goes through the byte-array overload; its body is a sealed record, not id plus body"
        }
        write(AapFrame.plaintext(message.id, message.body), message.channel, message.control, false, flush)
    }

    fun write(plaintext: ByteArray, channel: Int, control: Boolean, encrypted: Boolean, flush: Boolean = false) {
        for (frame in AapFrame.encode(channel, control, encrypted, plaintext)) {
            output.write(frame)
        }
        if (flush) output.flush()
    }
}
