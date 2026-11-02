package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.aap.AapIdentity
import com.shilapi.xcertplay.aap.HeadUnitCertificate
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.CertificateFactory

/**
 * Keeps the head unit's self-signed identity between runs.
 *
 * This is the whole of the "offline authentication" state. There is no account, no activation and
 * no server: a key pair generated on the first run, and a certificate the phone remembers by
 * fingerprint. Losing it is recoverable but visible — the phone treats the head unit as a new
 * device and asks the driver to trust it again.
 *
 * Stored in the app's private directory rather than the Android keystore. The keystore would add
 * hardware backing for a key whose only job is to be presented in the clear to a phone that pins it
 * by value; it would also make the identity unreadable on units whose keystore is broken, which on
 * aftermarket head units is common enough to be a support category.
 */
object AapIdentityStore {

    private const val FILE_NAME = "aap-identity.bin"

    /** Format marker, so a future change can migrate instead of failing to parse. */
    private const val MAGIC = 0x44495041 // "DIPA"

    @Volatile
    private var cached: AapIdentity? = null

    /** Loads the stored identity, creating and saving one on first use. */
    @Synchronized
    fun load(context: Context, commonName: String = "DiPlay Head Unit"): AapIdentity {
        cached?.let { return it }
        val file = File(context.filesDir, FILE_NAME)
        val existing = read(file)
        if (existing != null) {
            cached = existing
            return existing
        }
        val generated = HeadUnitCertificate.generate(commonName = commonName, organization = "DiPlay")
        val identity = AapIdentity(generated.keyPair, generated.certificate)
        write(file, identity)
        cached = identity
        return identity
    }

    /** Forgets the identity; the next [load] makes a new one. Used by the diagnostic screen. */
    @Synchronized
    fun reset(context: Context) {
        cached = null
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun read(file: File): AapIdentity? {
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                val keyBytes = ByteArray(input.readInt())
                input.readFully(keyBytes)
                val certBytes = ByteArray(input.readInt())
                input.readFully(certBytes)
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certBytes.inputStream()) as java.security.cert.X509Certificate
                AapIdentity(java.security.KeyPair(certificate.publicKey, key), certificate)
            }
        } catch (e: Exception) {
            // A half-written file from a power loss is not worth failing the session over: a new
            // identity makes the phone ask once more, which is recoverable; crashing is not.
            null
        }
    }

    private fun write(file: File, identity: AapIdentity) {
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                val key = identity.keyPair.private.encoded
                output.writeInt(key.size)
                output.write(key)
                val cert = identity.certificate.encoded
                output.writeInt(cert.size)
                output.write(cert)
            }
            // Rename last, so an interrupted write leaves the previous identity intact.
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        } catch (e: Exception) {
            runCatching { temporary.delete() }
        }
    }
}
