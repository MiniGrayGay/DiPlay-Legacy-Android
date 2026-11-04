package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ntb16CodecTest {
    @Test
    fun parseCanReadBlockFromLargerBuffer() {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        val block = Ntb16Codec.build(frame, 7)
        val buffer = ByteArray(11) + block + ByteArray(3)

        val parsed = Ntb16Codec.parse(buffer, 11, block.size)

        assertArrayEquals(frame, parsed.single())
    }

    @Test
    fun largestDatagramKeepsBlockLengthWithinU16() {
        val block = Ntb16Codec.build(ByteArray(65_507), 0x1234)

        assertEquals(65_535, block.size)
        assertEquals(0xff, block[8].toInt() and 0xff)
        assertEquals(0xff, block[9].toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun datagramThatWouldOverflowBlockLengthIsRejected() {
        Ntb16Codec.build(ByteArray(65_508), 0x1234)
    }

    @Test
    fun forEachDatagramPointsIntoTheSourceBuffer() {
        val frame = ByteArray(300) { (it * 7).toByte() }
        val stream = byteArrayOf(1, 2, 3) + Ntb16Codec.build(frame, 4)
        val seen = ArrayList<Pair<Int, Int>>()
        Ntb16Codec.forEachDatagram(stream, 3, stream.size - 3) { offset, length -> seen += offset to length }
        org.junit.Assert.assertEquals(listOf((3 + 28) to frame.size), seen)
        org.junit.Assert.assertArrayEquals(frame, stream.copyOfRange(seen[0].first, seen[0].first + seen[0].second))
    }
}
