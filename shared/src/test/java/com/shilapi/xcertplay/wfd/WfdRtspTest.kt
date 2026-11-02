package com.shilapi.xcertplay.wfd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

/**
 * Pins the parts of RTSP that Wi-Fi Display bends.
 *
 * The two that matter: replies are matched by `CSeq` rather than by who spoke last, and a sink is
 * the one that sends SETUP/PLAY. A framing bug here does not throw — it produces a session that
 * connects, negotiates, and then never shows a picture.
 */
class WfdRtspTest {

    private fun roundTrip(message: WfdMessage): WfdMessage {
        val parsed = WfdMessage.read(ByteArrayInputStream(message.encode()))
        return requireNotNull(parsed)
    }

    @Test
    fun optionsCarriesTheWfdRequireHeader() {
        val parsed = roundTrip(WfdRtsp.options(cSeq = 1))
        assertTrue(parsed.isRequest)
        assertEquals("OPTIONS", parsed.method)
        assertEquals("*", parsed.url)
        assertEquals(1, parsed.cSeq)
        assertEquals(WfdRtsp.REQUIRE, parsed.header("Require"))
    }

    @Test
    fun aResponseIsNotARequest() {
        val parsed = roundTrip(WfdRtsp.ok(cSeq = 7))
        assertEquals(false, parsed.isRequest)
        assertNull(parsed.method)
        assertEquals(200, parsed.statusCode)
        assertEquals(7, parsed.cSeq)
    }

    @Test
    fun headerLookupIgnoresCase() {
        val parsed = roundTrip(WfdRtsp.setup(cSeq = 3, "rtsp://1.2.3.4/wfd1.0/streamid=0", 5000, 5001))
        assertTrue(parsed.header("transport")!!.startsWith("RTP/AVP/UDP"))
        assertTrue(parsed.header("cseq")!!.isNotEmpty())
    }

    /**
     * The specification writes `wfd-video-formats`, Android writes `wfd_video_formats`. A sink that
     * matches on the exact spelling answers M3 with nothing and gets an empty session.
     */
    @Test
    fun parameterLookupFoldsDashesAndUnderscores() {
        val reply = WfdRtsp.parameters(2, mapOf("wfd-video-formats" to "00 00 01 01"))
        val parsed = roundTrip(reply)
        assertEquals("00 00 01 01", parsed.param("wfd_video_formats"))
        assertEquals("00 00 01 01", parsed.param("wfd-video-formats"))
    }

    @Test
    fun getParameterSendsItsNamesAsABody() {
        val request = WfdRtsp.getParameter(4, listOf("wfd_video_formats", "wfd_uibc_capability"))
        val parsed = roundTrip(request)
        assertEquals("GET_PARAMETER", parsed.method)
        val text = String(parsed.body, StandardCharsets.UTF_8)
        assertTrue(text, text.contains("wfd_video_formats"))
        assertTrue(text, text.contains("wfd_uibc_capability"))
        assertEquals(text.toByteArray(StandardCharsets.UTF_8).size.toString(), parsed.header("Content-Length"))
    }

    /**
     * M1/M2 carry capabilities as headers while M3/M4 carry them in the body. Both are real
     * captures, so a sink has to look in both places.
     */
    @Test
    fun parametersAreFoundInHeadersToo() {
        val reply = WfdMessage(
            "RTSP/1.0 200 OK",
            listOf("CSeq" to "1", "wfd_video_formats" to "00 00 01 01", "wfd_audio_codecs" to "LPCM 00000002 00"),
        )
        val parsed = roundTrip(reply)
        assertEquals("00 00 01 01", parsed.param("wfd-video-formats"))
        assertEquals("LPCM 00000002 00", parsed.param("wfd_audio_codecs"))
    }

    @Test
    fun setUpAnnouncesTheSinksOwnPorts() {
        val parsed = roundTrip(WfdRtsp.setup(9, "rtsp://10.0.0.2/wfd1.0/streamid=0", 19000, 19001))
        assertEquals("SETUP", parsed.method)
        assertEquals("rtsp://10.0.0.2/wfd1.0/streamid=0", parsed.url)
        assertEquals("RTP/AVP/UDP;unicast;client_port=19000-19001", parsed.header("Transport"))
    }

    @Test
    fun playCarriesTheSessionIdWithoutItsParameters() {
        val parsed = roundTrip(WfdRtsp.play(11, "ABCDEF"))
        assertEquals("PLAY", parsed.method)
        assertEquals("ABCDEF", parsed.session)
    }

    @Test
    fun aCleanlyClosedConnectionIsNotAnError() {
        assertNull(WfdMessage.read(ByteArrayInputStream(ByteArray(0))))
    }

    /**
     * Two messages arriving in one TCP segment must not be eaten by the first read: a peer that
     * answers M3 and M4 back to back is normal.
     */
    @Test
    fun readingOneMessageLeavesTheNextInTheStream() {
        val stream = ByteArrayInputStream(WfdRtsp.ok(1).encode() + WfdRtsp.ok(2).encode())
        assertEquals(1, WfdMessage.read(stream)!!.cSeq)
        assertEquals(2, WfdMessage.read(stream)!!.cSeq)
        assertNull(WfdMessage.read(stream))
    }

    @Test(expected = EOFException::class)
    fun aBodyCutShortIsAnErrorRatherThanAnEmptyMessage() {
        val full = WfdRtsp.parameters(5, mapOf("wfd_video_formats" to "00 00 01 01")).encode()
        val truncated = full.copyOf(full.size - 4)
        WfdMessage.read(ByteArrayInputStream(truncated))
    }

    @Test
    fun anEmptyBodyStillCarriesContentLengthZero() {
        val encoded = String(WfdRtsp.ok(1).encode(), StandardCharsets.ISO_8859_1)
        assertTrue(encoded, encoded.contains("Content-Length: 0"))
    }
}
