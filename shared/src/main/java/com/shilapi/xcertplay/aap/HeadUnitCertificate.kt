package com.shilapi.xcertplay.aap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Self-signed head unit certificate.
 *
 * This is what makes the Android path work offline, the way CarPlay's authentication coprocessor
 * does: the head unit presents a certificate it generated locally, and the phone decides whether
 * to trust it. Nothing is issued, activated or checked against a server, so there is no account,
 * no activation code and no network dependency on either side.
 *
 * The asymmetry is on the phone's side, and it decides whose implementation can connect:
 *
 * - Google's Android Auto app holds a trust store with a single anchor, the "Google Automotive
 *   Link" root, so it only accepts a head unit certificate that Google issued. A car maker gets
 *   one; a project like this cannot.
 * - Open-source phone implementations (AARE, aasdk-based ones) do not check for a Google issuer.
 *   They pin the certificate the first time and ask the driver to confirm it, which is trust on
 *   first use.
 *
 * So the certificate below works with the open-source phone side and is refused by Google's. That
 * is a property of the phone, not of this code.
 *
 * Neither the JDK nor Android exposes a certificate builder, and pulling BouncyCastle in for this
 * one job is heavier than writing the few DER structures RFC 5280 needs. The encoding below
 * follows the same minimal approach as AARE's `SelfSignedCertificate` (GPL-3.0-or-later), which
 * this project is also licensed under.
 */
object HeadUnitCertificate {

    class Generated(val keyPair: KeyPair, val certificate: X509Certificate) {
        /** SHA-256 fingerprint, formatted the way the phone shows it in its trust prompt. */
        val fingerprint: String get() = fingerprintOf(certificate)
    }

    /**
     * Generates a fresh certificate.
     *
     * [commonName] is what the driver sees in the phone's "New head unit" prompt, so it should
     * name the car rather than the app. [validYears] is long because a head unit has no renewal
     * path: an expired certificate cannot be reissued without the phone asking to trust a new
     * one, which is exactly the notification this is meant to avoid.
     */
    fun generate(
        commonName: String = "DiPlay",
        organization: String = "DiPlay",
        validYears: Int = 30,
        random: SecureRandom = SecureRandom(),
    ): Generated {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val serial = BigInteger(64, random).abs().add(BigInteger.ONE)
        val now = System.currentTimeMillis()
        // Backdate by a day: a head unit that boots without a network often has a clock behind
        // the phone's, and a not-yet-valid certificate is refused with its own status code.
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + validYears * 365L * 24 * 3600 * 1000)
        val name = name(organization, commonName)

        // sha256WithRSAEncryption, used both as the TBS signature field and the outer one.
        val sha256WithRsa = seq(oid("1.2.840.113549.1.1.11"), NULL)
        val tbs = seq(
            explicit(0, integer(BigInteger.valueOf(2))), // version v3
            integer(serial),
            sha256WithRsa,
            name, // issuer
            seq(time(notBefore), time(notAfter)),
            name, // subject == issuer, self-signed
            keyPair.public.encoded, // SubjectPublicKeyInfo is already DER
            extensions(),
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, sha256WithRsa, bitString(signature))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        return Generated(keyPair, certificate)
    }

    fun fingerprintOf(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val out = StringBuilder(digest.size * 3)
        for ((index, byte) in digest.withIndex()) {
            if (index > 0) out.append(':')
            out.append(HEX[(byte.toInt() shr 4) and 0x0f])
            out.append(HEX[byte.toInt() and 0x0f])
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    // --- minimal DER encoding (RFC 5280) ---

    private val NULL = byteArrayOf(0x05, 0x00)

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val length = content.size
        when {
            length < 0x80 -> out.write(length)
            length < 0x100 -> {
                out.write(0x81)
                out.write(length)
            }
            length < 0x10000 -> {
                out.write(0x82)
                out.write(length shr 8)
                out.write(length and 0xff)
            }
            else -> {
                out.write(0x83)
                out.write(length shr 16)
                out.write((length shr 8) and 0xff)
                out.write(length and 0xff)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray): ByteArray {
        var content = ByteArray(0)
        for (part in parts) content += part
        return tlv(0x30, content)
    }

    private fun set(vararg parts: ByteArray): ByteArray {
        var content = ByteArray(0)
        for (part in parts) content += part
        return tlv(0x31, content)
    }

    private fun explicit(n: Int, content: ByteArray): ByteArray = tlv(0xA0 or n, content)

    private fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    private fun bitString(bytes: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + bytes)

    private fun utf8(value: String): ByteArray = tlv(0x0C, value.toByteArray(Charsets.UTF_8))

    /** One RDN per attribute, in DER order: O (2.5.4.10) before CN (2.5.4.3). */
    private fun name(organization: String, commonName: String): ByteArray {
        val parts = ArrayList<ByteArray>(2)
        if (organization.isNotEmpty()) parts += set(seq(oid("2.5.4.10"), utf8(organization)))
        parts += set(seq(oid("2.5.4.3"), utf8(commonName)))
        return seq(*parts.toTypedArray())
    }

    private fun time(date: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = Calendar.getInstance(utc).apply { time = date }.get(Calendar.YEAR)
        // RFC 5280: UTCTime through 2049, GeneralizedTime from 2050.
        return if (year < 2050) {
            tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }
                .format(date).toByteArray())
        } else {
            tlv(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }
                .format(date).toByteArray())
        }
    }

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (part in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var value = part
            stack.add((value and 0x7f).toInt())
            value = value shr 7
            while (value > 0) {
                stack.add(((value and 0x7f) or 0x80).toInt())
                value = value shr 7
            }
            for (byte in stack.asReversed()) out.write(byte)
        }
        return tlv(0x06, out.toByteArray())
    }

    /**
     * The X.509 v3 extensions a TLS endpoint needs.
     *
     * Not optional in practice: Conscrypt — which is what Android uses for TLS — refuses a
     * certificate with no key usage when it has to pick an authentication scheme, failing the
     * handshake with "No available authentication scheme". SunJSSE tolerates the omission, so a
     * certificate that works on a desktop JVM can still fail on every phone.
     *
     * basicConstraints: CA:FALSE, critical — this is a leaf, it must not chain anything.
     * keyUsage: digitalSignature + keyEncipherment, critical — what a TLS endpoint uses.
     * extendedKeyUsage: serverAuth + clientAuth — the head unit is the TLS *client* here, so
     * clientAuth is the one that matters, but the same certificate is usable either way.
     */
    private fun extensions(): ByteArray {
        val basicConstraints = extension("2.5.29.19", critical = true, value = seq())
        // BIT STRING for keyUsage: 5 unused bits, then digitalSignature (bit 0) and
        // keyEncipherment (bit 2) set, which encodes as 0xA0.
        val keyUsage = extension(
            "2.5.29.15",
            critical = true,
            value = tlv(0x03, byteArrayOf(5, 0xA0.toByte())),
        )
        val extendedKeyUsage = extension(
            "2.5.29.37",
            critical = false,
            value = seq(oid("1.3.6.1.5.5.7.3.1"), oid("1.3.6.1.5.5.7.3.2")),
        )
        return explicit(3, seq(basicConstraints, keyUsage, extendedKeyUsage))
    }

    private fun extension(dotted: String, critical: Boolean, value: ByteArray): ByteArray {
        val parts = ArrayList<ByteArray>(3)
        parts += oid(dotted)
        if (critical) parts += tlv(0x01, byteArrayOf(0xFF.toByte()))
        // extnValue always wraps the DER of the extension in an OCTET STRING.
        parts += tlv(0x04, value)
        return seq(*parts.toTypedArray())
    }
}
