package com.shilapi.xcertplay.aap

import java.security.MessageDigest
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitCertificateTest {

    @Test
    fun theCertificateVerifiesAgainstItsOwnKey() {
        // A self-signed certificate the JDK cannot verify would make the TLS handshake fail with
        // an unrelated error, so check the signature explicitly.
        val generated = HeadUnitCertificate.generate()
        generated.certificate.verify(generated.keyPair.public)
    }

    @Test
    fun theSubjectNamesTheHeadUnit() {
        val generated = HeadUnitCertificate.generate(commonName = "BYD DiLink 5.1")
        val subject = generated.certificate.subjectX500Principal
        assertTrue(subject.name, subject.name.contains("CN=BYD DiLink 5.1"))
        // issuer == subject for a self-signed certificate
        assertEquals(subject, generated.certificate.issuerX500Principal)
    }

    @Test
    fun theOrganizationIsDerEncodedInOrder() {
        val generated = HeadUnitCertificate.generate(commonName = "HU", organization = "DiPlay")
        val principal: X500Principal = generated.certificate.subjectX500Principal
        val name = principal.name
        assertTrue(name, name.contains("O=DiPlay"))
        assertTrue(name, name.contains("CN=HU"))
    }

    @Test
    fun theCertificateIsValidForDecadesBecauseThereIsNoRenewalPath() {
        val generated = HeadUnitCertificate.generate(validYears = 30)
        val span = generated.certificate.notAfter.time - generated.certificate.notBefore.time
        val days = span / (24L * 3600 * 1000)
        assertTrue("validity was $days days", days > 30 * 365 - 5)
    }

    @Test
    fun theNotBeforeDateIsBackdatedForHeadUnitsWithoutACorrectClock() {
        val generated = HeadUnitCertificate.generate()
        assertTrue(
            "notBefore must be in the past, was ${generated.certificate.notBefore}",
            generated.certificate.notBefore.time < System.currentTimeMillis(),
        )
    }

    @Test
    fun theKeyIsRsa2048() {
        val generated = HeadUnitCertificate.generate()
        assertEquals("RSA", generated.keyPair.public.algorithm)
        assertEquals(2048, (generated.keyPair.public as java.security.interfaces.RSAPublicKey).modulus.bitLength())
    }

    @Test
    fun theFingerprintMatchesTheEncodedCertificate() {
        val generated = HeadUnitCertificate.generate()
        val expected = MessageDigest.getInstance("SHA-256").digest(generated.certificate.encoded)
        val expectedText = expected.joinToString(":") { String.format("%02X", it.toInt() and 0xff) }
        assertEquals(expectedText, generated.fingerprint)
    }

    @Test
    fun twoHeadUnitsGetDifferentCertificates() {
        // Every installation generating its own is the point: a shared key would let anyone pose
        // as any head unit once one phone trusted it.
        val first = HeadUnitCertificate.generate()
        val second = HeadUnitCertificate.generate()
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertFalse(first.keyPair.public.encoded.contentEquals(second.keyPair.public.encoded))
    }

    @Test
    fun theCertificateParsesBackAsX509V3() {
        val generated = HeadUnitCertificate.generate()
        assertEquals("X.509", generated.certificate.type)
        // Version 3 is what the handshake expects; the DER writer puts the explicit [0] tag in.
        assertEquals(3, generated.certificate.version)
    }
}
