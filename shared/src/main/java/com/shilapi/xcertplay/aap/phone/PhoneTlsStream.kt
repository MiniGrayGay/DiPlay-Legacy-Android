package com.shilapi.xcertplay.aap.phone

import com.shilapi.xcertplay.aap.AA_CIPHER_SUITES
import com.shilapi.xcertplay.aap.AapFrame
import com.shilapi.xcertplay.aap.AapFrameReader
import com.shilapi.xcertplay.aap.AapFrameWriter
import com.shilapi.xcertplay.aap.AapMessage
import com.shilapi.xcertplay.aap.AapMessageAssembler
import com.shilapi.xcertplay.aap.AapMessageIds
import com.shilapi.xcertplay.aap.AapTransport
import com.shilapi.xcertplay.aap.HeadUnitCertificate
import com.shilapi.xcertplay.aap.inputStream
import com.shilapi.xcertplay.aap.outputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS for an AAP session, from the **phone's** side.
 *
 * This is the mirror of `AapTlsStream`: the phone is the TLS **server**, so the handshake starts by
 * waiting for the head unit's ClientHello rather than by sending one. Everything else is the same
 * shape — handshake records travel as `ENCAPSULATED_SSL` messages in plaintext frames, and after
 * the handshake each message is one sealed record inside a frame that keeps its channel id.
 *
 * It lives in the product module rather than the tests because two things need it: the protocol
 * tests, and the phone simulator used to exercise a head unit end to end. A head unit never runs
 * this code.
 */
class PhoneTlsStream(
    private val transport: AapTransport,
    keyPair: KeyPair,
    certificate: X509Certificate,
) : Closeable {

    private val reader = AapFrameReader(transport.inputStream())
    private val writer = AapFrameWriter(transport.outputStream())
    private val assembler = AapMessageAssembler()
    private val trust = CapturingTrustManager()

    private val engine: SSLEngine = SSLContext.getInstance("TLS").run {
        init(arrayOf(ServerKeyManager(keyPair, arrayOf(certificate))), arrayOf(trust), null)
        createSSLEngine().apply {
            useClientMode = false
            // Paired deliberately: a server that leaves the protocol list alone will negotiate
            // TLS 1.3 with a head unit that only offers 1.2 suites, and fail on the cipher list.
            enabledProtocols = arrayOf("TLSv1.2")
            enabledCipherSuites = AA_CIPHER_SUITES
            // Mutual TLS: the head unit always presents a certificate, even though the decision to
            // trust it happens afterwards, on the fingerprint.
            wantClientAuth = true
        }
    }

    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize.coerceAtLeast(32 * 1024))
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize.coerceAtLeast(32 * 1024))
    private var pendingNetwork = ByteArray(0)

    /** Fingerprint of the head unit's certificate, available after [handshake]. */
    var headUnitFingerprint: String? = null
        private set

    var established = false
        private set

    /** Runs the handshake. Blocks reading the head unit's ClientHello first. */
    fun handshake() {
        if (established) return
        engine.beginHandshake()
        var status = engine.handshakeStatus
        var rounds = 0
        while (status != SSLEngineResult.HandshakeStatus.FINISHED &&
            status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            if (++rounds > MAX_ROUNDS) throw IOException("TLS handshake did not converge after $MAX_ROUNDS rounds")
            status = when (status) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapHandshake()

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val record = readHandshakeRecord()
                    feedNetwork(record)
                    engine.handshakeStatus
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runTasks()
                    engine.handshakeStatus
                }

                else -> engine.handshakeStatus
            }
        }
        headUnitFingerprint = trust.seen?.let { HeadUnitCertificate.fingerprintOf(it) }
        if (headUnitFingerprint == null) {
            throw IOException("head unit sent no certificate; Android Auto is mutual TLS")
        }
        established = true
    }

    /**
     * Encrypts one message and writes it.
     *
     * Same shape as the head unit's writer, and it has to be: a video keyframe does not fit in one
     * frame, so the plaintext is sealed in chunks and the concatenated records are handed to the
     * frame writer, which fragments them with FIRST/LAST and a total size. Sealing per record and
     * writing each as its own message would leave the head unit with several half-messages.
     */
    fun write(message: AapMessage, flush: Boolean = false) {
        check(established) { "TLS handshake has not completed" }
        val plaintext = AapFrame.plaintext(message.id, message.body)
        val sealed = ByteArrayOutputStream(plaintext.size + 128)
        var offset = 0
        while (offset < plaintext.size) {
            val length = minOf(SEAL_CHUNK, plaintext.size - offset)
            val source = ByteBuffer.wrap(plaintext, offset, length)
            while (source.hasRemaining()) {
                netOut.clear()
                val result = engine.wrap(source, netOut)
                if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                    continue
                }
                if (result.status == SSLEngineResult.Status.CLOSED) throw IOException("TLS engine closed while writing")
                netOut.flip()
                if (netOut.hasRemaining()) sealed.write(bufferOf(netOut))
            }
            offset += length
        }
        // Same reason as the head unit: the control bit rides in the frame header and is not
        // covered by the sealed record, so dropping it here turns a channel open request into a
        // service message the head unit ignores.
        writer.write(sealed.toByteArray(), message.channel, message.control, true, flush)
    }

    /**
     * Writes a plaintext message.
     *
     * Used for the version exchange and for nothing else: the head unit turns decryption on when it
     * receives `AuthComplete`, and that is the last unencrypted message in the session.
     */
    fun writeCleartext(message: AapMessage, flush: Boolean = true) {
        writer.write(message, flush)
    }

    /** Reads and decrypts the next message, or null when the head unit hung up. */
    fun read(): AapMessage? {
        check(established) { "TLS handshake has not completed" }
        while (true) {
            val message = reader.nextMessage(assembler) ?: return null
            if (!message.encrypted) {
                throw IOException("unencrypted message 0x${message.id.toString(16)} after the handshake")
            }
            val plaintext = feedNetwork(message.body) ?: return null
            if (plaintext.size < AapFrame.ID_SIZE) continue
            return AapMessage(
                channel = message.channel,
                control = message.control,
                encrypted = true,
                id = AapFrame.idOf(plaintext),
                body = plaintext.copyOfRange(AapFrame.ID_SIZE, plaintext.size),
            )
        }
    }

    override fun close() {
        runCatching { engine.closeOutbound() }
        runCatching { transport.close() }
    }

    private fun wrapHandshake(): SSLEngineResult.HandshakeStatus {
        netOut.clear()
        val result = engine.wrap(EMPTY, netOut)
        if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            netOut = ByteBuffer.allocate(netOut.capacity() * 2)
            return engine.handshakeStatus
        }
        netOut.flip()
        if (netOut.hasRemaining()) {
            val message = AapFrame.plaintext(AapMessageIds.Control.ENCAPSULATED_SSL, bufferOf(netOut))
            writer.write(message, AapFrame.CONTROL_CHANNEL, false, false, true)
        }
        return result.handshakeStatus
    }

    private fun readHandshakeRecord(): ByteArray {
        while (true) {
            val message = reader.nextMessage(assembler) ?: throw IOException("head unit hung up during the TLS handshake")
            if (message.channel == AapFrame.CONTROL_CHANNEL && message.id == AapMessageIds.Control.ENCAPSULATED_SSL) {
                return message.body
            }
        }
    }

    private fun feedNetwork(bytes: ByteArray): ByteArray? {
        pendingNetwork += bytes
        val source = ByteBuffer.wrap(pendingNetwork)
        var out: ByteArray? = ByteArray(0)
        while (source.hasRemaining()) {
            appIn.clear()
            val result = engine.unwrap(source, appIn)
            when (result.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> break
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                    continue
                }

                SSLEngineResult.Status.CLOSED -> {
                    out = null
                    break
                }

                SSLEngineResult.Status.OK -> {
                    appIn.flip()
                    if (appIn.hasRemaining()) out = (out ?: ByteArray(0)) + bufferOf(appIn)
                }
            }
        }
        pendingNetwork = ByteArray(source.remaining())
        source.get(pendingNetwork)
        return out
    }

    private fun runTasks() {
        var task = engine.delegatedTask
        while (task != null) {
            task.run()
            task = engine.delegatedTask
        }
    }

    private fun bufferOf(buffer: ByteBuffer): ByteArray {
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
        const val MAX_ROUNDS = 200

        /** Plaintext per TLS record; matches the head unit's writer, for the same reason. */
        const val SEAL_CHUNK = 8 * 1024
    }
}

private const val KEY_ALIAS = "phone"

/** Answers with the phone's identity; a session has exactly one. */
private class ServerKeyManager(
    private val keyPair: KeyPair,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(KEY_ALIAS)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String = KEY_ALIAS

    // The SSLEngine-only pair; the base class returns null for both, and a server that answers null
    // presents no certificate at all.
    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = null

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String = KEY_ALIAS

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
    override fun getPrivateKey(alias: String?): PrivateKey = keyPair.private
}

/** Records the head unit's certificate instead of validating a chain nobody can issue. */
private class CapturingTrustManager : X509ExtendedTrustManager() {
    var seen: X509Certificate? = null
        private set

    private fun remember(chain: Array<out X509Certificate>?) {
        seen = chain?.firstOrNull() ?: seen
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = remember(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = remember(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = remember(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = remember(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = remember(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = remember(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
