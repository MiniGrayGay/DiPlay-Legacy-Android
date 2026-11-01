package com.shilapi.xcertplay.mirroring.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AaMessagesTest {

    @Test
    fun `hello round trips every field`() {
        val hello = AaMessages.Hello(
            width = 1920,
            height = 1080,
            densityDpi = 420,
            codec = AaWire.Codec.H264,
            deviceName = "Pixel 7",
            screenRotation = 1,
            statsIntervalMs = 500,
        )
        val decoded = AaMessages.Hello.decode(hello.encode())!!
        assertEquals(hello, decoded)
        assertEquals(1920, decoded.width)
        assertEquals(500, decoded.statsIntervalMs)
    }

    @Test
    fun `a pipe in the device name cannot break the encoding`() {
        val hello = AaMessages.Hello(
            width = 100,
            height = 100,
            densityDpi = 160,
            codec = AaWire.Codec.H264,
            deviceName = "Odd|Phone",
        )
        assertEquals("Odd/Phone", AaMessages.Hello.decode(hello.encode())!!.deviceName)
    }

    @Test
    fun `an unknown protocol is rejected`() {
        val payload = AaMessages.encodePairs(mapOf("protocol" to "airplay/1", "width" to "1", "height" to "1"))
        assertNull(AaMessages.Hello.decode(payload))
    }

    @Test
    fun `hello without dimensions is rejected`() {
        val payload = AaMessages.encodePairs(mapOf("protocol" to AaMessages.PROTOCOL))
        assertNull(AaMessages.Hello.decode(payload))
    }

    @Test
    fun `touch round trips with normalised coordinates`() {
        val touch = AaMessages.Touch(AaWire.TouchAction.DOWN, pointerId = 2, x = 0.25f, y = 0.75f)
        val decoded = AaMessages.Touch.decode(touch.encode())!!
        assertEquals(AaWire.TouchAction.DOWN, decoded.action)
        assertEquals(2, decoded.pointerId)
        assertEquals(0.25f, decoded.x, 1e-6f)
        assertEquals(0.75f, decoded.y, 1e-6f)
    }

    @Test
    fun `clamping keeps pointers on screen`() {
        assertEquals(0f, AaMessages.Touch.clamp(-3f), 0f)
        assertEquals(1f, AaMessages.Touch.clamp(9f), 0f)
        assertEquals(0f, AaMessages.Touch.clamp(Float.NaN), 0f)
        assertEquals(0.4f, AaMessages.Touch.clamp(0.4f), 0f)
    }

    @Test
    fun `a truncated touch payload is ignored`() {
        assertNull(AaMessages.Touch.decode(ByteArray(4)))
    }

    @Test
    fun `key round trips`() {
        val key = AaMessages.Key(action = 0, keyCode = 87, repeat = 3)
        assertEquals(key, AaMessages.Key.decode(key.encode()))
    }

    @Test
    fun `stats round trips`() {
        val stats = AaMessages.Stats(encodedFrames = 120, droppedFrames = 2, lastFrameBytes = 4096, keyFrames = 3, width = 720, height = 480)
        assertEquals(stats, AaMessages.Stats.decode(stats.encode()))
    }

    @Test
    fun `nonce is eight bytes big endian`() {
        val nonce = AaMessages.nonce(1L)
        assertEquals(8, nonce.size)
        assertEquals(1L, AaMessages.nonceOf(nonce))
        assertNull(AaMessages.nonceOf(ByteArray(3)))
        assertEquals(0x0102030405060708L, AaMessages.nonceOf(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test
    fun `receiver name and refusal reason survive the ack`() {
        val ack = AaMessages.HelloAck(accepted = false, reason = "receiver busy", receiverName = "DiPlay")
        val decoded = AaMessages.HelloAck.decode(ack.encode())
        assertTrue(!decoded.accepted)
        assertEquals("receiver busy", decoded.reason)
        assertEquals("DiPlay", decoded.receiverName)
    }
}
