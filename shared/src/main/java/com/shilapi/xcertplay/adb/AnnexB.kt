package com.shilapi.xcertplay.adb

/**
 * The little bit of H.264 that `screenrecord` makes necessary.
 *
 * The phone hands over an Annex-B elementary stream: NAL units separated by `00 00 01` start codes,
 * with no container and no separate parameter sets. `MediaCodec` wants a `MediaFormat` first, and the
 * only place the SPS/PPS exist is inside that stream, so they have to be lifted out of it — and a
 * 64 KiB read from a cable routinely ends in the middle of a NAL or a start code, hence [AnnexBSplitter]
 * keeps the remainder instead of parsing each chunk on its own.
 */
object AnnexB {
    const val TYPE_IDR = 5
    const val TYPE_SPS = 7
    const val TYPE_PPS = 8

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** NAL unit type, i.e. the low five bits of the first byte. -1 for an empty NAL. */
    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1f

    /** A NAL prefixed with a four-byte start code, the form `MediaFormat` expects for `csd-0`/`csd-1`. */
    fun withStartCode(nal: ByteArray): ByteArray = START_CODE + nal
}

/** Splits a byte stream into complete NAL units, holding back an incomplete tail. */
class AnnexBSplitter(initialCapacity: Int = 128 * 1024) {

    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    fun reset() {
        size = 0
    }

    /** Returns the NAL units completed by [chunk]; the remainder stays buffered. */
    fun feed(chunk: ByteArray): List<ByteArray> {
        append(chunk)
        val units = ArrayList<ByteArray>(8)
        var scan = 0
        while (true) {
            val start = findStartCode(scan)
            if (start < 0) {
                // No start code: either pre-stream noise, or the tail of one split across reads.
                scan = (size - 2).coerceAtLeast(0)
                break
            }
            val nalStart = start + 3
            val next = findStartCode(nalStart)
            if (next < 0) {
                scan = start
                break
            }
            var end = next
            // A four-byte start code carries a leading zero that belongs to the next unit.
            while (end > nalStart && buffer[end - 1].toInt() == 0) end--
            if (end > nalStart) units.add(buffer.copyOfRange(nalStart, end))
            scan = next
        }
        keepFrom(scan.coerceAtMost(size))
        return units
    }

    private fun findStartCode(from: Int): Int {
        var index = from.coerceAtLeast(0)
        while (index + 2 < size) {
            if (buffer[index].toInt() == 0 && buffer[index + 1].toInt() == 0 && buffer[index + 2].toInt() == 1) {
                return index
            }
            index++
        }
        return -1
    }

    private fun append(chunk: ByteArray) {
        if (size + chunk.size > buffer.size) {
            var capacity = buffer.size
            while (capacity < size + chunk.size) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
        System.arraycopy(chunk, 0, buffer, size, chunk.size)
        size += chunk.size
    }

    private fun keepFrom(index: Int) {
        if (index <= 0) return
        if (index >= size) {
            size = 0
            return
        }
        System.arraycopy(buffer, index, buffer, 0, size - index)
        size -= index
    }
}
