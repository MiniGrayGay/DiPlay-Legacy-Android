package com.shilapi.xcertplay.mirroring.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AaWireTest {

    @Test
    fun `header carries type codec pts and length`() {
        val frame = AaWire.encode(AaWire.Type.VIDEO, AaWire.Codec.H264, 1234567890123L, byteArrayOf(1, 2, 3, 4))
        assertEquals(AaWire.HEADER_SIZE + 4, frame.size)
        assertEquals(AaWire.Type.VIDEO, AaWire.typeOf(frame))
        assertEquals(AaWire.Codec.H264, AaWire.codecOf(frame))
        assertEquals(1234567890123L, AaWire.ptsOf(frame))
        assertEquals(4, AaWire.payloadLengthOf(frame))
    }

    @Test
    fun `single frame round trips`() {
        val payload = ByteArray(64) { it.toByte() }
        val frames = AaFrameReader().feed(AaWire.encode(AaWire.Type.VIDEO, AaWire.Codec.H264, 7L, payload))
        assertEquals(1, frames.size)
        assertEquals(AaWire.Type.VIDEO, frames[0].type)
        assertEquals(7L, frames[0].ptsUs)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test
    fun `a frame split byte by byte still decodes once`() {
        val reader = AaFrameReader()
        val payload = "hello android mode".toByteArray()
        val bytes = AaWire.encode(AaWire.Type.STATS, payload = payload)
        val collected = ArrayList<AaFrame>()
        for (byte in bytes) {
            collected += reader.feed(byteArrayOf(byte))
        }
        assertEquals(1, collected.size)
        assertArrayEquals(payload, collected[0].payload)
        assertEquals(0L, reader.skippedBytes)
    }

    @Test
    fun `two frames in one read are both returned`() {
        val first = AaWire.encode(AaWire.Type.PING, payload = byteArrayOf(9))
        val second = AaWire.encode(AaWire.Type.PONG, payload = byteArrayOf(10))
        val frames = AaFrameReader().feed(first + second)
        assertEquals(2, frames.size)
        assertEquals(AaWire.Type.PING, frames[0].type)
        assertEquals(AaWire.Type.PONG, frames[1].type)
    }

    @Test
    fun `garbage before the magic is skipped`() {
        val reader = AaFrameReader()
        val garbage = byteArrayOf(0x00, 0x13, 0x37, 0x41)
        val frames = reader.feed(garbage + AaWire.encode(AaWire.Type.BYE, payload = byteArrayOf(1)))
        assertEquals(1, frames.size)
        assertEquals(AaWire.Type.BYE, frames[0].type)
        assertTrue(reader.skippedBytes >= 4)
    }

    @Test
    fun `an incomplete payload waits for the rest`() {
        val reader = AaFrameReader()
        val payload = ByteArray(500) { (it % 251).toByte() }
        val bytes = AaWire.encode(AaWire.Type.VIDEO, AaWire.Codec.H264, payload = payload)
        assertEquals(0, reader.feed(bytes, 0, bytes.size - 1).size)
        val frames = reader.feed(bytes, bytes.size - 1, 1)
        assertEquals(1, frames.size)
        assertArrayEquals(payload, frames[0].payload)
    }

    @Test
    fun `a wrong version byte is resynchronised`() {
        val reader = AaFrameReader()
        val broken = AaWire.encode(AaWire.Type.VIDEO, payload = byteArrayOf(5))
        broken[2] = 9
        val frames = reader.feed(broken + AaWire.encode(AaWire.Type.VIDEO, payload = byteArrayOf(6)))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(6), frames[0].payload)
    }

    @Test
    fun `an impossible length is rejected instead of allocating`() {
        val reader = AaFrameReader()
        val broken = AaWire.encode(AaWire.Type.VIDEO, payload = byteArrayOf(5))
        AaWire.putInt(broken, 14, AaWire.MAX_PAYLOAD + 1)
        val frames = reader.feed(broken + AaWire.encode(AaWire.Type.VIDEO, payload = byteArrayOf(6)))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(6), frames[0].payload)
    }

    @Test
    fun `negative pts survives the round trip`() {
        val frames = AaFrameReader().feed(AaWire.encode(AaWire.Type.VIDEO, ptsUs = -42L, payload = byteArrayOf(1)))
        assertEquals(-42L, frames[0].ptsUs)
    }
}
