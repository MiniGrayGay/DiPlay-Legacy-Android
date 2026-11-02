package com.shilapi.xcertplay.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * The CarLife header is the one thing a wrong guess cannot recover from: a swapped field does not throw,
 * the phone just answers "version mismatch" and the screen stays black. So the byte layout, both header
 * shapes and the resynchronisation behaviour are pinned here rather than discovered against a phone.
 */
class CarLifeFramesTest {

    @Test
    fun commandAndControlHeadersCarryNoTimestamp() {
        assertEquals(8, CarLifeChannel.CMD.headLength)
        assertEquals(8, CarLifeChannel.CTRL.headLength)
        listOf(CarLifeChannel.VIDEO, CarLifeChannel.MEDIA, CarLifeChannel.TTS, CarLifeChannel.VR)
            .forEach { assertEquals(it.name, 12, it.headLength) }
    }

    @Test
    fun headerIsLittleEndianSizeThenType() {
        val head = CarLifeFrames.encodeHead(
            CarLifeChannel.CMD,
            CarLifeHead(type = CarLifeMessage.HU_PROTOCOL_VERSION, dataSize = 0x1234),
        )
        // size 0x1234 -> 34 12 00 00, type 0x00018001 -> 01 80 01 00
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0x00, 0x00, 0x01, 0x80.toByte(), 0x01, 0x00),
            head,
        )
    }

    @Test
    fun streamingHeaderIsSizeThenTimestampThenType() {
        val head = CarLifeFrames.encodeHead(
            CarLifeChannel.VIDEO,
            CarLifeHead(type = CarLifeMessage.VIDEO_DATA, dataSize = 1000, timestamp = 0x0a0b0c0d),
        )
        assertArrayEquals(
            byteArrayOf(
                0xe8.toByte(), 0x03, 0x00, 0x00, // size 1000
                0x0d, 0x0c, 0x0b, 0x0a, // timestamp
                0x01, 0x00, 0x02, 0x00, // type 0x00020001
            ),
            head,
        )
    }

    @Test
    fun bothHeaderShapesRoundTrip() {
        for (channel in CarLifeChannel.entries) {
            val original = CarLifeHead(
                type = 0x00018001,
                dataSize = 4096,
                timestamp = if (channel.hasTimestamp) 987654 else 0,
            )
            val decoded = CarLifeFrames.decodeHead(channel, CarLifeFrames.encodeHead(channel, original))
            assertEquals(channel.name, original, decoded)
        }
    }

    @Test
    fun anOversizedPayloadIsRejectedBeforeItIsAllocated() {
        try {
            CarLifeFrames.encodeHead(CarLifeChannel.CMD, CarLifeHead(CarLifeMessage.HU_INFO, 41 * 1024))
            fail("expected the command channel to refuse a 41 KiB payload")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("exceeds"))
        }
    }

    @Test
    fun aHeaderThatClaimsTooMuchIsRejectedOnDecode() {
        val head = ByteArray(8)
        head[0] = 0x00
        head[1] = 0x00
        head[2] = 0x01 // 65536 bytes, more than the control channel allows
        try {
            CarLifeFrames.decodeHead(CarLifeChannel.CTRL, head)
            fail("expected a desynchronised header to be refused")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("limit"))
        }
    }

    @Test
    fun readsFramesWhoseHeaderIsSplitAcrossReads() {
        val source = ByteArrayOutputStream()
        CarLifeFrameWriter(CarLifeChannel.CMD, source, now = { 0 })
            .write(CarLifeMessage.HU_INFO, byteArrayOf(1, 2, 3))
        CarLifeFrameWriter(CarLifeChannel.CMD, source, now = { 0 })
            .write(CarLifeMessage.VIDEO_ENCODER_INIT, byteArrayOf(9))

        // One byte at a time is what a USB bulk read does when the phone dribbles a header out.
        val reader = CarLifeFrameReader(CarLifeChannel.CMD, DribbleInput(source.toByteArray()))
        val first = reader.read()!!
        assertEquals(CarLifeMessage.HU_INFO, first.type)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.payload)
        val second = reader.read()!!
        assertEquals(CarLifeMessage.VIDEO_ENCODER_INIT, second.type)
        assertArrayEquals(byteArrayOf(9), second.payload)
        assertNull(reader.read())
    }

    @Test
    fun aFreshConnectionReadsNothingInsteadOfBlocking() {
        assertNull(CarLifeFrameReader(CarLifeChannel.CMD, ByteArrayInputStream(ByteArray(0))).read())
    }

    @Test
    fun aTruncatedPayloadIsAnErrorNotAnEmptyFrame() {
        val source = ByteArrayOutputStream()
        CarLifeFrameWriter(CarLifeChannel.CTRL, source, now = { 0 }).write(CarLifeMessage.TOUCH_ACTION_UP, ByteArray(8))
        val bytes = source.toByteArray().copyOf(source.size() - 3)
        try {
            CarLifeFrameReader(CarLifeChannel.CTRL, ByteArrayInputStream(bytes)).read()
            fail("expected a truncated payload to be reported")
        } catch (expected: EOFException) {
            assertTrue(expected.message!!.contains("3 of 8"))
        }
    }

    @Test
    fun writerStampsStreamingChannelsAndLeavesCommandAlone() {
        val source = ByteArrayOutputStream()
        CarLifeFrameWriter(CarLifeChannel.VIDEO, source, now = { 42_000 }).write(CarLifeMessage.VIDEO_DATA, ByteArray(4))
        val frame = CarLifeFrameReader(CarLifeChannel.VIDEO, ByteArrayInputStream(source.toByteArray())).read()!!
        assertEquals(42_000, frame.head.timestamp)
        assertEquals(4, frame.head.dataSize)
    }

    /** Hands out one byte per `read`, which is the worst case for a header parser. */
    private class DribbleInput(private val data: ByteArray) : ByteArrayInputStream(data) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            b[off] = data[pos++]
            return 1
        }
    }
}
