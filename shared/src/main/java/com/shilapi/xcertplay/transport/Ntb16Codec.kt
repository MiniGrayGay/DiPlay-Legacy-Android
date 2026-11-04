package com.shilapi.xcertplay.transport

/**
 * Minimal NTB16 codec for the iPhone NCM data link.
 *
 * Only the 16-bit transfer-block layout used by LIVI's `iap2-usbmux/src/ntb.rs` is implemented:
 * one NTH16 header, one NDP16 table with one datagram, and the USB short-packet pad byte. There
 * is no NTB32, alignment, CRC, or NCM control-plane handling here.
 */
object Ntb16Codec {
    const val NTH16_SIG = 0x484d434e
    const val NDP16_SIG = 0x304d434e

    internal const val NTH_LENGTH = 12
    private const val NDP_LENGTH = 16
    private const val DATAGRAM_INDEX = 28
    private const val USB_PACKET_SIZE = 512

    /** Wraps one Ethernet frame in one NTB16 block, padding an exact 512-byte boundary. */
    fun build(frame: ByteArray, sequence: Int): ByteArray {
        require(frame.isNotEmpty()) { "frame must not be empty" }
        require(frame.size <= MAX_DATAGRAM_BYTES) { "frame exceeds the NTB16 length field" }
        require(sequence in 0..0xffff) { "sequence must fit in u16" }

        val blockLength = DATAGRAM_INDEX + frame.size
        val block = ByteArray(blockLength)
        putU32(block, 0, NTH16_SIG)
        putU16(block, 4, NTH_LENGTH)
        putU16(block, 6, sequence)
        putU16(block, 8, blockLength)
        putU16(block, 10, NTH_LENGTH)

        putU32(block, 12, NDP16_SIG)
        putU16(block, 16, NDP_LENGTH)
        putU16(block, 18, 0)
        putU16(block, 20, DATAGRAM_INDEX)
        putU16(block, 22, frame.size)
        putU16(block, 24, 0)
        putU16(block, 26, 0)

        frame.copyInto(block, DATAGRAM_INDEX)
        // A transfer that ends exactly on a USB packet boundary is read as a short packet.
        return if (block.size % USB_PACKET_SIZE == 0) block + byteArrayOf(0) else block
    }

    /**
     * Extracts the Ethernet frames carried by one NTB16 block.
     *
     * Wire garbage returns an empty list instead of throwing; malformed entries are skipped where
     * a bounds check fails. A step guard stops a malformed NDP pointer chain from cycling.
     */
    fun parse(block: ByteArray): List<ByteArray> = parse(block, 0, block.size)

    /** Parses one NTB16 block from [offset] without copying the containing buffer. */
    fun parse(block: ByteArray, offset: Int, length: Int): List<ByteArray> {
        val datagrams = ArrayList<ByteArray>(1)
        forEachDatagram(block, offset, length) { start, size ->
            datagrams.add(block.copyOfRange(start, start + size))
        }
        return datagrams
    }

    /**
     * Calls [onDatagram] with the absolute offset and length of each Ethernet frame in one NTB16
     * block, in wire order, without allocating. Validation is identical to [parse].
     */
    internal inline fun forEachDatagram(block: ByteArray, offset: Int, length: Int, onDatagram: (Int, Int) -> Unit) {
        require(offset >= 0 && length >= 0 && offset <= block.size - length) {
            "NTB16 range is outside the source buffer"
        }
        val end = offset + length
        if (length < NTH_LENGTH || u32(block, offset) != NTH16_SIG) return

        var ndpOffset = offset + u16(block, offset + 10)
        var hops = 0
        val maxHops = maxOf(1, length / 4)
        while (ndpOffset != 0 && hops < maxHops) {
            if (ndpOffset < offset || ndpOffset + 12 > end) break
            if (u32(block, ndpOffset) and 0x00ffffff != NDP16_SIG and 0x00ffffff) break
            val ndpLength = u16(block, ndpOffset + 4)
            val nextNdp = u16(block, ndpOffset + 6)
            var entry = ndpOffset + 8
            val ndpEnd = minOf(ndpOffset + ndpLength, end)
            while (entry + 4 <= ndpEnd) {
                val datagramIndex = u16(block, entry)
                val datagramLength = u16(block, entry + 2)
                if (datagramIndex == 0 || datagramLength == 0) break
                val datagramStart = offset + datagramIndex
                val datagramEnd = datagramStart + datagramLength
                if (datagramStart >= offset && datagramStart <= end && datagramEnd <= end) {
                    onDatagram(datagramStart, datagramLength)
                }
                entry += 4
            }
            ndpOffset = if (nextNdp == 0) 0 else offset + nextNdp
            hops++
        }
    }

    internal fun u16(source: ByteArray, offset: Int): Int = readU16(source, offset)

    internal fun u32(source: ByteArray, offset: Int): Int = readU32(source, offset)

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private const val MAX_DATAGRAM_BYTES = 0xffff - DATAGRAM_INDEX
}
