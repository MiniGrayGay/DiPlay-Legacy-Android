package com.shilapi.xcertplay.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `screenrecord` hands over a raw Annex-B stream, so the parameter sets that `MediaCodec` needs have
 * to be recovered from the bytes themselves. These cases are the ones a cable produces on its own:
 * a start code split across two reads, a four-byte start code, and a NAL that never completes.
 */
class AnnexBTest {

    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1f)
    private val pps = byteArrayOf(0x68, 0xee.toByte(), 0x3c, 0x80.toByte())
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x21)

    @Test
    fun splitsCompleteNalUnits() {
        val splitter = AnnexBSplitter()
        // A NAL is only known to be complete once the next start code arrives, so the stream carries
        // one more start code than the units asserted here.
        val units = splitter.feed(startCode3(sps) + startCode3(pps) + startCode4(idr) + byteArrayOf(0, 0, 1))
        assertEquals(3, units.size)
        assertArrayEquals(sps, units[0])
        assertArrayEquals(pps, units[1])
        assertArrayEquals(idr, units[2])
        assertEquals(AnnexB.TYPE_SPS, AnnexB.nalType(units[0]))
        assertEquals(AnnexB.TYPE_IDR, AnnexB.nalType(units[2]))
    }

    /** The trailing unit is only emitted once the next start code proves it ended. */
    @Test
    fun holdsBackAnIncompleteTailUntilMoreBytesArrive() {
        val splitter = AnnexBSplitter()
        assertTrue(splitter.feed(startCode3(sps)).isEmpty())
        val units = splitter.feed(startCode3(pps))
        assertEquals(1, units.size)
        assertArrayEquals(sps, units[0])
    }

    /** A 64 KiB cable read routinely breaks a start code in half. */
    @Test
    fun survivesAStartCodeSplitAcrossReads() {
        val stream = startCode3(sps) + startCode4(pps) + startCode3(idr) + byteArrayOf(0, 0, 1)
        val splitter = AnnexBSplitter()
        val units = ArrayList<ByteArray>()
        for (byte in stream) units += splitter.feed(byteArrayOf(byte))
        assertEquals(3, units.size)
        assertArrayEquals(sps, units[0])
        assertArrayEquals(pps, units[1])
        assertArrayEquals(idr, units[2])
    }

    /** Bytes before the first start code are not a NAL and must not become one. */
    @Test
    fun dropsLeadingNoise() {
        val splitter = AnnexBSplitter()
        val units = splitter.feed(byteArrayOf(0x12, 0x34, 0x56) + startCode3(sps) + startCode3(pps))
        assertEquals(1, units.size)
        assertArrayEquals(sps, units[0])
    }

    /** A zero-padded NAL ending must not leak its padding into the next unit. */
    @Test
    fun fourByteStartCodeDoesNotLeaveAZeroOnThePreviousUnit() {
        val splitter = AnnexBSplitter()
        val padded = byteArrayOf(0x65, 0x11, 0x22, 0x00, 0x00)
        val units = splitter.feed(startCode3(padded) + startCode4(sps) + startCode3(pps))
        assertEquals(2, units.size)
        assertArrayEquals(byteArrayOf(0x65, 0x11, 0x22), units[0])
    }

    @Test
    fun csdPrefixesParameterSetsWithAStartCode() {
        val csd = AnnexB.withStartCode(sps)
        assertEquals(4 + sps.size, csd.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), csd.copyOfRange(0, 4))
        assertArrayEquals(sps, csd.copyOfRange(4, csd.size))
    }

    @Test
    fun emptyNalHasNoType() {
        assertEquals(-1, AnnexB.nalType(ByteArray(0)))
    }

    private fun startCode3(nal: ByteArray) = byteArrayOf(0, 0, 1) + nal

    private fun startCode4(nal: ByteArray) = byteArrayOf(0, 0, 0, 1) + nal
}
