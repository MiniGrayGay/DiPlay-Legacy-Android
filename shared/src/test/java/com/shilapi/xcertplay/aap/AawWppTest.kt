package com.shilapi.xcertplay.aap

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AawWppTest {

    @Test
    fun theFrameHeaderIsBigEndianAndTheLengthExcludesTheHeader() {
        // 0x02 0x2E = 558 byte payload, message id 3. A little-endian header here is the classic
        // first bug: the phone then reads a length of 0x2E02 and blocks forever.
        val payload = ByteArray(558) { 0x41 }
        val frame = AawWpp.encodeFrame(AawWpp.MessageId.INFO_RESPONSE, payload)
        assertEquals(4 + 558, frame.size)
        assertEquals(0x02, frame[0].toInt())
        assertEquals(0x2E, frame[1].toInt())
        assertEquals(0x00, frame[2].toInt())
        assertEquals(0x03, frame[3].toInt())
    }

    @Test
    fun aFrameSurvivesTheRoundTrip() {
        val payload = AawWpp.encodeInfoResponse("DiPlay-AA", "carplay0001", "02:11:22:33:44:55")
        val read = AawWpp.readFrame(ByteArrayInputStream(AawWpp.encodeFrame(AawWpp.MessageId.INFO_RESPONSE, payload)))
        assertNotNull(read)
        assertEquals(AawWpp.MessageId.INFO_RESPONSE, read!!.id)
        assertTrue(payload.contentEquals(read.payload))
    }

    @Test
    fun aHeaderDeliveredOneByteAtATimeIsReassembled() {
        // RFCOMM hands over whatever fits in one packet; a header split across reads is normal.
        val payload = AawWpp.encodeStartRequest("192.168.43.1", 5277)
        val wire = AawWpp.encodeFrame(AawWpp.MessageId.START_REQUEST, payload)
        val dribble = object : InputStream() {
            private var index = 0
            override fun read(): Int = if (index < wire.size) wire[index++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= wire.size) return -1
                b[off] = wire[index++]
                return 1
            }
        }
        val frame = AawWpp.readFrame(dribble)!!
        assertEquals(AawWpp.MessageId.START_REQUEST, frame.id)
        assertTrue(payload.contentEquals(frame.payload))
    }

    @Test
    fun theSecurityModeIsTheBitFlagValueNotTheInSessionEnum() {
        // WPA2-PSK is 8 on this wire, while the in-session WifiSecurityMode enum numbers it 5.
        // A head unit that sends 5 gets a phone that cannot parse the message as a PSK network.
        val payload = AawWpp.encodeInfoResponse("DiPlay-AA", "carplay0001", null, AawWpp.SecurityMode.WPA2_PERSONAL)
        val reader = com.shilapi.xcertplay.proto.ProtoWire.Reader(payload)
        var security = -1
        while (true) {
            val field = reader.next() ?: break
            if (field.number == 4) security = field.asInt()
        }
        assertEquals(8, security)
    }

    @Test
    fun anEmptyBssidIsLeftOffTheWire() {
        // Sending "" makes some phones parse a zero BSSID and then refuse to join.
        val withoutBssid = AawWpp.encodeInfoResponse("DiPlay-AA", "carplay0001", bssid = null)
        val withEmpty = AawWpp.encodeInfoResponse("DiPlay-AA", "carplay0001", bssid = "")
        assertTrue(withoutBssid.contentEquals(withEmpty))

        val reader = com.shilapi.xcertplay.proto.ProtoWire.Reader(withoutBssid)
        var sawBssid = false
        while (true) {
            val field = reader.next() ?: break
            if (field.number == 3) sawBssid = true
        }
        assertFalse("an empty BSSID must not be written at all", sawBssid)
    }

    @Test
    fun aNegativeStatusStillRoundTrips() {
        // aaw.Status is a proto2 enum with negative members, so -10 is a ten-byte varint.
        val payload = com.shilapi.xcertplay.proto.ProtoWire.Writer()
            .int32(1, AawWpp.Status.PHONE_WIFI_DISABLED)
            .string(2, "wifi is off")
            .build()
        val status = AawWpp.decodeConnectionStatus(payload)
        assertEquals(AawWpp.Status.PHONE_WIFI_DISABLED, status.status)
        assertEquals("wifi is off", status.message)
        assertFalse(status.joined)
    }

    @Test
    fun theStartResponseIsDecodedInFull() {
        val payload = com.shilapi.xcertplay.proto.ProtoWire.Writer()
            .string(1, "192.168.43.23")
            .uint32(2, 5277)
            .int32(3, AawWpp.Status.SUCCESS)
            .build()
        val response = AawWpp.decodeStartResponse(payload)
        assertEquals("192.168.43.23", response.ipAddress)
        assertEquals(5277, response.port)
        assertTrue(response.accepted)
    }
}
