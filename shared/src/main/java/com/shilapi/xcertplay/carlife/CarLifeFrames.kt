package com.shilapi.xcertplay.carlife

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One CarLife message: a fixed header followed by `dataSize` bytes of payload.
 *
 * The header is little-endian, which is not an assumption — the reference head unit writes it with
 * `memcpy` of `struct analyzedHead { u32 dataSize; u32 timeStamp; u32 headType; }` on ARM and on x86, so
 * every field goes out least-significant byte first.
 */
data class CarLifeHead(
    /** Message type, one of [CarLifeMessage]. */
    val type: Int,
    /** Payload length in bytes. */
    val dataSize: Int,
    /** Milliseconds, streaming channels only; `0` on [CarLifeChannel.CMD] and [CarLifeChannel.CTRL]. */
    val timestamp: Int = 0,
)

/**
 * Encodes and decodes the CarLife header.
 *
 * Kept as an object over fake streams so the byte order and the two header shapes are pinned by tests
 * instead of by a cable: a swapped field does not fail loudly, it makes the phone answer with a version
 * mismatch and the screen stay black.
 */
object CarLifeFrames {

    /** Writes the header for [channel]. The caller writes the payload itself. */
    fun encodeHead(channel: CarLifeChannel, head: CarLifeHead): ByteArray {
        require(head.dataSize >= 0) { "negative payload: ${head.dataSize}" }
        require(head.dataSize <= channel.maxPayload) {
            "${channel.name} payload ${head.dataSize} exceeds ${channel.maxPayload}"
        }
        val out = ByteArray(channel.headLength)
        putInt(out, 0, head.dataSize)
        if (channel.hasTimestamp) {
            putInt(out, 4, head.timestamp)
            putInt(out, 8, head.type)
        } else {
            putInt(out, 4, head.type)
        }
        return out
    }

    /**
     * Reads a header out of [bytes] at [offset].
     *
     * Throws rather than returning a best guess: a payload longer than the channel allows means the
     * stream is desynchronised, and continuing would spend the rest of the session reading garbage.
     */
    fun decodeHead(channel: CarLifeChannel, bytes: ByteArray, offset: Int = 0): CarLifeHead {
        if (bytes.size - offset < channel.headLength) {
            throw IllegalArgumentException(
                "need ${channel.headLength} header bytes, have ${bytes.size - offset}",
            )
        }
        val size = getInt(bytes, offset)
        val head = if (channel.hasTimestamp) {
            CarLifeHead(type = getInt(bytes, offset + 8), dataSize = size, timestamp = getInt(bytes, offset + 4))
        } else {
            CarLifeHead(type = getInt(bytes, offset + 4), dataSize = size)
        }
        if (head.dataSize < 0 || head.dataSize > channel.maxPayload) {
            throw IOException(
                "${channel.name} header claims ${head.dataSize} bytes, limit ${channel.maxPayload}",
            )
        }
        return head
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        target[offset + 2] = ((value ushr 16) and 0xff).toByte()
        target[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun getInt(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)
}

/** A decoded message, payload included. */
class CarLifeFrame(val head: CarLifeHead, val payload: ByteArray) {
    val type: Int get() = head.type
}

/**
 * Reads whole frames off a channel, one at a time, blocking until each is complete.
 *
 * Deliberately a single-message-per-call API: the video channel is a natural frame source for the
 * decoder, and a callback-based parser would only move the buffering problem into a lambda. One instance
 * per channel, because each channel has its own header length and payload ceiling.
 *
 * Returns `null` only at a clean end of stream — a truncated header or payload is an [EOFException],
 * since half a message means the session is over, not that the caller should retry.
 */
class CarLifeFrameReader(
    private val channel: CarLifeChannel,
    private val input: InputStream,
) {
    private val head = ByteArray(channel.headLength)

    fun read(): CarLifeFrame? {
        val first = input.read()
        if (first < 0) return null
        head[0] = first.toByte()
        readFully(head, 1, channel.headLength - 1)
        val decoded = CarLifeFrames.decodeHead(channel, head)
        if (decoded.dataSize == 0) return CarLifeFrame(decoded, EMPTY)
        val payload = ByteArray(decoded.dataSize)
        readFully(payload, 0, payload.size)
        return CarLifeFrame(decoded, payload)
    }

    private fun readFully(target: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val count = input.read(target, offset + read, length - read)
            if (count < 0) {
                throw EOFException("${channel.name} stream ended with ${length - read} of $length bytes pending")
            }
            read += count
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * Writes frames onto a channel.
 *
 * Not synchronised on purpose: [CarLifeChannel.CTRL] is written from the UI thread while the video
 * channel belongs to its own reader, and two threads never share one writer. A `synchronized` here would
 * only hide a caller's bug behind a stall.
 */
class CarLifeFrameWriter(
    private val channel: CarLifeChannel,
    private val output: OutputStream,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun write(type: Int, payload: ByteArray = EMPTY, timestampMillis: Int = 0) {
        val stamp = if (channel.hasTimestamp) {
            if (timestampMillis != 0) timestampMillis else (now() and 0xffffffffL).toInt()
        } else {
            0
        }
        val head = CarLifeFrames.encodeHead(channel, CarLifeHead(type = type, dataSize = payload.size, timestamp = stamp))
        // Header and payload go out in one flush: the phone reads a header and then exactly that many
        // bytes, so a split write that stalls leaves the session waiting on a byte that never comes.
        val frame = ByteArray(head.size + payload.size)
        System.arraycopy(head, 0, frame, 0, head.size)
        System.arraycopy(payload, 0, frame, head.size, payload.size)
        output.write(frame)
        output.flush()
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
