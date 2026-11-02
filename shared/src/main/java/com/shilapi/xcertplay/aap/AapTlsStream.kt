package com.shilapi.xcertplay.aap

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
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS for an AAP session, from the head unit's side.
 *
 * **The head unit is the TLS client and the phone is the server.** That is the opposite of what a
 * head unit port usually assumes, and getting it backwards means the handshake never starts: the
 * phone waits for a ClientHello that never arrives.
 *
 * The handshake runs inside AAP: each TLS record travels as an `ENCAPSULATED_SSL` message
 * (channel 0, message id 3) in a plaintext frame. Once the handshake finishes, each message is
 * encrypted on its own — the plaintext (id plus body) is wrapped into one TLS record and sent in a
 * frame with the `ENCRYPTED` flag, keeping its channel number. Framing stays outside the
 * encryption, which is why the channel header is still readable and the peer can route the message
 * before decrypting it.
 *
 * What the certificate checks mean here:
 *
 * - This side presents [certificate], self-signed and generated on the head unit. No authority
 *   issues it and nothing about it is checked online.
 * - This side does **not** verify the phone's chain. Google's Android Auto presents a `CarService`
 *   certificate issued by "Google Automotive Link"; open-source phone implementations present one
 *   they generated. Neither chains to anything a head unit could be given, so chain validation
 *   would only ever fail. Trust moves to first use: [phoneFingerprint] is what the driver confirms
 *   once, and [expectedPhoneFingerprint] pins it from then on.
 *
 * That split — no issuer on our side, one-time trust on theirs — is the whole of the "offline
 * authentication" story for Android. There is no activation, account or server in it.
 */
class AapTlsStream(
    private val transport: AapTransport,
    keyPair: KeyPair,
    certificate: X509Certificate,
    /** Fingerprint the driver already trusted, or null on first connection. */
    private val expectedPhoneFingerprint: String? = null,
) : Closeable {

    private val reader = AapFrameReader(transport.inputStream())
    private val writer = AapFrameWriter(transport.outputStream())
    private val assembler = AapMessageAssembler()
    private val trust = CapturingTrustManager()

    private val engine: SSLEngine = SSLContext.getInstance("TLS").run {
        init(arrayOf(FixedKeyManager(keyPair, arrayOf(certificate))), arrayOf(trust), null)
        createSSLEngine().apply {
            useClientMode = true
            // Android Auto runs TLS 1.2, and the version has to be pinned together with the
            // cipher list: a JVM engine enables TLS 1.3 by default, and negotiating 1.3 while the
            // suite list only holds 1.2 suites fails with "no cipher suites in common" — or, if
            // the suite list is left alone, with "No available authentication scheme". Both read
            // like protocol bugs and are neither.
            enabledProtocols = arrayOf("TLSv1.2")
            enabledCipherSuites = AA_CIPHER_SUITES
            // No SNI: the phone is not addressed by name and the trust decision is a pinned
            // fingerprint, so a hostname would be a value we invented and then had to match.
        }
    }

    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize.coerceAtLeast(32 * 1024))
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize.coerceAtLeast(32 * 1024))
    private var pendingNetwork = ByteArray(0)

    /**
     * Fingerprint of the certificate the phone presented, available after [handshake].
     *
     * The caller compares it with what it stored: equal means a known phone, a different value
     * means the phone's certificate changed and deserves a fresh confirmation.
     */
    var phoneFingerprint: String? = null
        private set

    /** True once the handshake completed. */
    var established: Boolean = false
        private set

    /**
     * Runs the handshake.
     *
     * Throws when the phone hangs up or refuses. A refusing phone closes the connection instead of
     * answering, so there is no status code to read — which is why the message says "hung up".
     */
    fun handshake() {
        if (established) return
        engine.beginHandshake()
        var status = engine.handshakeStatus
        var rounds = 0
        while (status != SSLEngineResult.HandshakeStatus.FINISHED &&
            status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            if (++rounds > MAX_ROUNDS) {
                throw SSLException("TLS handshake did not converge after $MAX_ROUNDS rounds")
            }
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
        phoneFingerprint = trust.seen?.let { HeadUnitCertificate.fingerprintOf(it) }
        if (phoneFingerprint == null) {
            throw SSLException("phone sent no certificate; Android Auto is mutual TLS")
        }
        if (expectedPhoneFingerprint != null && phoneFingerprint != expectedPhoneFingerprint) {
            throw SSLException(
                "phone certificate changed: expected $expectedPhoneFingerprint, got $phoneFingerprint",
            )
        }
        established = true
    }

    /**
     * Encrypts one message and writes it.
     *
     * The plaintext must fit in a single frame: an encrypted record that then needed splitting
     * would leave the peer without a way to tell two messages apart. Callers with big payloads
     * (video, audio) are expected to fragment at the media layer, which is where the protocol
     * already has a packet boundary.
     */
    fun write(message: AapMessage, flush: Boolean = false) {
        check(established) { "TLS handshake has not completed" }
        val plaintext = AapFrame.plaintext(message.id, message.body)
        if (plaintext.size > AapFrame.MAX_PAYLOAD) {
            throw IllegalArgumentException(
                "encrypted message of ${plaintext.size} bytes exceeds ${AapFrame.MAX_PAYLOAD}; fragment above this layer",
            )
        }
        var source = ByteBuffer.wrap(plaintext)
        while (source.hasRemaining()) {
            netOut.clear()
            val result = engine.wrap(source, netOut)
            when (result.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    netOut = grow(netOut, engine.session.packetBufferSize)
                    continue
                }

                SSLEngineResult.Status.CLOSED -> throw IOException("TLS engine closed while writing")
                else -> Unit
            }
            netOut.flip()
            if (netOut.hasRemaining()) {
                writer.write(bufferOf(netOut), message.channel, false, true, flush)
            }
        }
    }

    /**
     * Reads and decrypts the next message.
     *
     * Returns null when the phone closed the session.
     */
    fun read(): AapMessage? {
        check(established) { "TLS handshake has not completed" }
        while (true) {
            val message = reader.nextMessage(assembler) ?: return null
            if (!message.encrypted) {
                // A plaintext message after the handshake is a peer that dropped encryption;
                // handing it to the application as payload would hide that.
                throw SSLException("unencrypted message 0x${message.id.toString(16)} after handshake")
            }
            val plaintext = feedNetwork(message.body)
            if (plaintext == null) return null
            if (plaintext.size < AapFrame.ID_SIZE) continue
            val id = AapFrame.idOf(plaintext)
            return AapMessage(
                channel = message.channel,
                control = message.control,
                encrypted = true,
                id = id,
                body = plaintext.copyOfRange(AapFrame.ID_SIZE, plaintext.size),
            )
        }
    }

    override fun close() {
        try {
            engine.closeOutbound()
        } catch (_: Exception) {
            // Closing a broken engine throws; the transport below is going away anyway.
        }
        try {
            transport.close()
        } catch (_: Exception) {
            // Same.
        }
    }

    // --- handshake plumbing ---

    private fun wrapHandshake(): SSLEngineResult.HandshakeStatus {
        netOut.clear()
        val result = engine.wrap(EMPTY, netOut)
        if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            netOut = grow(netOut, engine.session.packetBufferSize)
            return engine.handshakeStatus
        }
        if (result.status == SSLEngineResult.Status.CLOSED) {
            throw SSLException("phone closed during handshake")
        }
        netOut.flip()
        if (netOut.hasRemaining()) {
            // A handshake record still travels as an AAP message: the TLS record is the body and
            // the 2-byte id in front of it says "ENCAPSULATED_SSL". Sending the record bare makes
            // the peer read the record header (0x16 0x03) as the message id.
            val message = AapFrame.plaintext(AapMessageIds.Control.ENCAPSULATED_SSL, bufferOf(netOut))
            writer.write(message, AapFrame.CONTROL_CHANNEL, false, false, true)
        }
        return result.handshakeStatus
    }

    /** Reads the next ENCAPSULATED_SSL message, or fails the way a refusing phone does. */
    private fun readHandshakeRecord(): ByteArray {
        val message = reader.nextMessage(assembler)
            ?: throw SSLException(
                "phone hung up during the TLS handshake (it likely refused this head unit's certificate)",
            )
        if (message.channel != AapFrame.CONTROL_CHANNEL || message.id != AapMessageIds.Control.ENCAPSULATED_SSL) {
            throw SSLException("expected ENCAPSULATED_SSL during handshake, got 0x${message.id.toString(16)}")
        }
        return message.body
    }

    /**
     * Feeds network bytes to the engine and returns whatever plaintext came out, or null when the
     * peer closed.
     *
     * Uses a plain array rather than a ring buffer: records are at most a few kilobytes, and a
     * mistake in ring arithmetic here would surface as mysterious decryption failures.
     */
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
                    appIn = grow(appIn, engine.session.applicationBufferSize)
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

    private fun grow(buffer: ByteBuffer, minimum: Int): ByteBuffer {
        val capacity = maxOf(buffer.capacity() * 2, minimum + buffer.position())
        if (capacity > MAX_BUFFER) throw SSLException("TLS buffer would exceed $MAX_BUFFER bytes")
        val larger = ByteBuffer.allocate(capacity)
        buffer.flip()
        larger.put(buffer)
        return larger
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
        const val MAX_ROUNDS = 200
        const val MAX_BUFFER = 4 * 1024 * 1024
    }
}

/** Alias the key manager answers under; a session has exactly one identity. */
private const val KEY_ALIAS = "diplay"

/**
 * Cipher suites Android Auto uses: ECDHE with RSA authentication and AES-GCM.
 *
 * Listed explicitly because Conscrypt's default set is version-dependent, and a suite the peer
 * does not also enable fails the handshake with "no cipher suites in common" — which reads like a
 * protocol bug rather than a configuration one.
 */
internal val AA_CIPHER_SUITES = arrayOf(
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
    "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
)

/** Hands the engine the head unit's own key pair; there is only ever one identity here. */
private class FixedKeyManager(
    private val keyPair: KeyPair,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(KEY_ALIAS)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = KEY_ALIAS
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    // SSLEngine asks a separate pair of methods. The base class answers null for both, so a key
    // manager that only overrides the socket flavours presents no certificate at all and the
    // handshake dies with "no cipher suites in common" — a message that points at the suite list
    // rather than at the missing identity. There is exactly one identity here, so both answer it.
    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String = KEY_ALIAS

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
    override fun getPrivateKey(alias: String?): PrivateKey = keyPair.private
}

/**
 * Accepts whatever the phone presents and remembers it.
 *
 * Deliberately not validating: see the class comment on [AapTlsStream]. The trust decision moved
 * to first use, where the driver sees the fingerprint. This is the same trade-off CarPlay's
 * authentication chip makes in hardware — no certificate authority on either side — realised in
 * trust-on-first-use instead of a fused key.
 */
private class CapturingTrustManager : X509ExtendedTrustManager() {
    var seen: X509Certificate? = null
        private set

    private fun remember(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: return
        seen = leaf
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = remember(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = remember(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = remember(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = remember(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = remember(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = remember(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
