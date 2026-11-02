package com.shilapi.xcertplay.aap

import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AapTlsStreamTest {

    private class Wired(
        val tls: AapTlsStream,
        val phone: SimulatedPhone,
        val phoneThread: Thread,
        val phoneError: AtomicReference<Throwable?>,
        val phoneIdentity: HeadUnitCertificate.Generated,
    ) {
        /** Surfaces what the phone side hit, so a head unit failure does not hide its real cause. */
        fun assertPhoneHealthy() {
            val error = phoneError.get() ?: return
            throw AssertionError("the simulated phone failed: $error", error)
        }
    }

    /**
     * Wires a head unit to a simulated phone over an in-process pipe.
     *
     * The phone side runs on its own thread: the pipe blocks when empty, exactly like a USB
     * endpoint, so a single thread would deadlock waiting for a reply it never sends.
     */
    private fun wire(
        expectedFingerprint: String? = null,
        phoneRefuses: Boolean = false,
        headUnit: HeadUnitCertificate.Generated = HeadUnitCertificate.generate(commonName = "DiPlay"),
        phoneIdentity: HeadUnitCertificate.Generated = HeadUnitCertificate.generate(commonName = "AARE phone"),
    ): Wired {
        val (headUnitSide, phoneSide) = LoopbackTransport.pair()
        val phone = SimulatedPhone(phoneSide, phoneIdentity, refuseHeadUnit = phoneRefuses)
        val error = AtomicReference<Throwable?>(null)
        val phoneThread = thread(name = "simulated-phone") {
            try {
                phone.handshake()
            } catch (t: Throwable) {
                // A refusing phone closes the accessory the way a real one does; the head unit's
                // own failure is what the tests assert, so the cause is kept for the message.
                error.set(t)
                phone.close()
            }
        }
        val tls = AapTlsStream(headUnitSide, headUnit.keyPair, headUnit.certificate, expectedFingerprint)
        return Wired(tls, phone, phoneThread, error, phoneIdentity)
    }

    @Test
    fun theHandshakeCompletesAndTheSessionCarriesDataBothWays() {
        val wired = wire()
        try {
            wired.tls.handshake()
        } catch (e: Exception) {
            wired.phoneThread.join(5_000)
            wired.assertPhoneHealthy()
            throw e
        }
        wired.phoneThread.join(10_000)
        wired.assertPhoneHealthy()

        assertTrue("handshake did not complete", wired.tls.established)
        assertNotNull("the phone certificate must be captured", wired.tls.phoneFingerprint)

        // Phone to head unit.
        val payload = "hello from the phone".toByteArray(Charsets.UTF_8)
        val sender = thread { wired.phone.writeMessage(7, 0x8001, payload) }
        val received = wired.tls.read()!!
        sender.join(5_000)
        assertEquals(7, received.channel)
        assertEquals(0x8001, received.id)
        assertArrayEquals(payload, received.body)

        // Head unit to phone, keeping the channel number through the encryption.
        val reply = "and back from the car".toByteArray(Charsets.UTF_8)
        wired.tls.write(AapMessage(3, control = false, encrypted = true, id = 0x8003, body = reply), flush = true)
        val phoneSaw = wired.phone.readMessage()!!
        assertEquals(3, phoneSaw.channel)
        assertEquals(0x8003, phoneSaw.id)
        assertArrayEquals(reply, phoneSaw.body)
    }

    @Test
    fun thePhoneCertificateIsPinnedOnFirstUse() {
        val wired = wire()
        wired.tls.handshake()
        wired.phoneThread.join(10_000)
        wired.assertPhoneHealthy()

        // Trust on first use: the first connection yields the fingerprint the driver confirms.
        assertEquals(wired.phoneIdentity.fingerprint, wired.tls.phoneFingerprint)
    }

    @Test
    fun aKnownFingerprintLetsTheSamePhoneBackIn() {
        val headUnit = HeadUnitCertificate.generate(commonName = "DiPlay")
        val phoneIdentity = HeadUnitCertificate.generate(commonName = "AARE phone")

        val first = wire(headUnit = headUnit, phoneIdentity = phoneIdentity)
        first.tls.handshake()
        first.phoneThread.join(10_000)
        first.assertPhoneHealthy()
        val learned = first.tls.phoneFingerprint
        assertNotNull(learned)

        val second = wire(headUnit = headUnit, phoneIdentity = phoneIdentity, expectedFingerprint = learned)
        second.tls.handshake()
        second.phoneThread.join(10_000)
        second.assertPhoneHealthy()
        assertTrue(second.tls.established)
    }

    @Test
    fun aDifferentPhoneCertificateIsRefused() {
        val wired = wire(expectedFingerprint = "AA:BB:CC:DD")
        try {
            wired.tls.handshake()
            fail("a changed phone certificate must not be accepted")
        } catch (expected: SSLException) {
            assertTrue(
                "message was: ${expected.message}",
                expected.message!!.contains("phone certificate changed"),
            )
        } finally {
            wired.tls.close()
            wired.phoneThread.join(5_000)
        }
    }

    @Test
    fun aPhoneThatHangsUpIsReportedAsRefusalNotAsADesync() {
        // Google's Android Auto app rejects a self-signed head unit by closing the connection
        // mid-handshake; there is no status code to read, so the message has to say so.
        val wired = wire(phoneRefuses = true)
        try {
            wired.tls.handshake()
            fail("a refusing phone must fail the handshake")
        } catch (expected: SSLException) {
            assertTrue(
                "message was: ${expected.message}",
                expected.message!!.contains("hung up"),
            )
        } finally {
            wired.tls.close()
            wired.phoneThread.join(5_000)
        }
    }

    @Test
    fun writingBeforeTheHandshakeIsRejected() {
        val headUnit = HeadUnitCertificate.generate()
        val (side, _) = LoopbackTransport.pair()
        val tls = AapTlsStream(side, headUnit.keyPair, headUnit.certificate)
        try {
            tls.write(AapMessage(0, control = false, encrypted = true, id = 1, body = byteArrayOf(1)))
            fail("writing before the handshake must not silently succeed")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("handshake"))
        }
    }
}
