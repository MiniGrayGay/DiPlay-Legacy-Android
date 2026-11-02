package com.shilapi.xcertplay.aap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AoaTest {

    @Test
    fun theProtocolVersionIsLittleEndian() {
        // AOA replies 2 as 0x02 0x00; reading it big endian would give 512.
        assertEquals(2, Aoa.protocolVersion(byteArrayOf(0x02, 0x00)))
        assertEquals(1, Aoa.protocolVersion(byteArrayOf(0x01, 0x00)))
    }

    @Test
    fun aShortReplyIsTreatedAsNoSupport() {
        assertEquals(0, Aoa.protocolVersion(null))
        assertEquals(0, Aoa.protocolVersion(byteArrayOf(0x02)))
    }

    @Test
    fun versionTwoAddsAudio() {
        assertFalse(Aoa.supportsAudio(1))
        assertTrue(Aoa.supportsAudio(2))
        assertTrue(Aoa.canStart(1))
        assertFalse(Aoa.canStart(0))
    }

    @Test
    fun onlyAccessoryVendorIdsCount() {
        assertTrue(Aoa.isAccessory(Aoa.ACCESSORY_VID, Aoa.ACCESSORY_PID))
        assertTrue(Aoa.isAccessory(Aoa.ACCESSORY_VID, Aoa.ACCESSORY_PID_WITH_ADB))
        assertTrue(Aoa.isAccessory(Aoa.ACCESSORY_VID, Aoa.ACCESSORY_PID_WITH_AUDIO))
        assertTrue(Aoa.isAccessory(Aoa.ACCESSORY_VID, Aoa.ACCESSORY_PID_WITH_AUDIO_AND_ADB))
        // A phone in MTP mode is not an accessory even though it is an Android device.
        assertFalse(Aoa.isAccessory(0x2717, 0xFF48))
        assertFalse(Aoa.isAccessory(Aoa.ACCESSORY_VID, 0x2D04))
    }

    @Test
    fun stringsAreNulTerminatedUtf8() {
        val payload = AoaHandshake.stringPayload("DiPlay")
        assertEquals(7, payload.size)
        assertEquals(0, payload.last().toInt())
        assertArrayEquals("DiPlay".toByteArray(Charsets.UTF_8), payload.copyOfRange(0, 6))
    }

    @Test
    fun theHandshakeSendsIdentityBeforeStart() {
        val plan = AoaHandshake.plan(2)
        assertEquals(5, plan.size) // four strings plus START
        assertEquals(Aoa.REQUEST_SEND_STRING, plan[0].request)
        assertEquals(Aoa.STRING_MANUFACTURER, plan[0].index)
        assertEquals(Aoa.REQUEST_SEND_STRING, plan[3].request)
        assertEquals(Aoa.STRING_VERSION, plan[3].index)
        // START must be last: the phone re-enumerates the moment it is accepted.
        assertEquals(Aoa.REQUEST_START, plan.last().request)
        assertEquals(Aoa.TYPE_OUT, plan.last().requestType)
    }

    @Test
    fun anEmptyUriAndSerialAreOmitted() {
        val plan = AoaHandshake.plan(2, AoaIdentity("m", "d", "x", "1"))
        assertFalse(plan.any { it.request == Aoa.REQUEST_SEND_STRING && it.index == Aoa.STRING_URI })
        assertFalse(plan.any { it.request == Aoa.REQUEST_SEND_STRING && it.index == Aoa.STRING_SERIAL })
    }

    @Test
    fun aUriAndSerialAreSentWhenSet() {
        val plan = AoaHandshake.plan(2, AoaIdentity("m", "d", "x", "1", uri = "https://diplay", serial = "42"))
        // Order matters: the phone stores the strings by index, so the indices must be ascending
        // even when the optional ones are present.
        val indices = plan.filter { it.request == Aoa.REQUEST_SEND_STRING }.map { it.index }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), indices)
    }

    @Test
    fun aPhoneWithoutAoaHasNothingToDo() {
        assertTrue(AoaHandshake.plan(0).isEmpty())
    }

    @Test
    fun getProtocolIsADeviceToHostRead() {
        val request = AoaHandshake.getProtocol()
        assertTrue(request.isInbound)
        assertEquals(2, request.replyLength)
        assertEquals(Aoa.REQUEST_GET_PROTOCOL, request.request)
    }

    @Test
    fun theDefaultIdentityIsStableAcrossReleases() {
        // The phone keys the accessory on the model string; changing it makes the phone treat the
        // same head unit as a new device and ask to trust its certificate again.
        assertEquals("DiPlay Head Unit", AoaIdentity.diplay().model)
        assertEquals("DiPlay", AoaIdentity.diplay().manufacturer)
    }
}
