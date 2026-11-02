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
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Stands in for the phone: the TLS server end of an AAP session.
 *
 * Kept in the test sources on purpose. It is not a security boundary and not a product feature —
 * it exists so the head unit's handshake can be exercised without a phone in the loop, including
 * the case where the phone presents a certificate nobody signed.
 */
class SimulatedPhone(
    private val transport: AapTransport,
    identity: HeadUnitCertificate.Generated,
    /** Refuse the head unit's certificate, the way Google's Android Auto app would. */
    private val refuseHeadUnit: Boolean = false,
) : Closeable {

    private val reader = AapFrameReader(transport.inputStream())
    private val writer = AapFrameWriter(transport.outputStream())
    private val assembler = AapMessageAssembler()
    private var netOut: ByteBuffer = ByteBuffer.allocate(64 * 1024)
    private var appIn: ByteBuffer = ByteBuffer.allocate(64 * 1024)
    private var pending = ByteArray(0)

    private val engine: SSLEngine = SSLContext.getInstance("TLS").run {
        init(arrayOf(ServerKeyManager(identity.keyPair, arrayOf(identity.certificate))), arrayOf(AcceptAll), null)
        createSSLEngine().apply {
            useClientMode = false
            // Same pairing as the head unit: TLS 1.2 with 1.2 suites, see AapTlsStream.
            enabledProtocols = arrayOf("TLSv1.2")
            enabledCipherSuites = AA_CIPHER_SUITES
            // Android Auto is mutual TLS: the phone is given the head unit's certificate even when
            // it decides not to reject it.
            wantClientAuth = true
        }
    }

    /** Fingerprint of the head unit certificate this phone was shown. */
    var headUnitFingerprint: String? = null
        private set

    fun handshake() {
        engine.beginHandshake()
        var status = engine.handshakeStatus
        var rounds = 0
        while (status != SSLEngineResult.HandshakeStatus.FINISHED &&
            status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            if (++rounds > 200) throw IOException("simulated phone handshake did not converge")
            status = when (status) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapHandshake()
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val message = reader.nextMessage(assembler) ?: throw IOException("head unit hung up")
                    if (message.id != AapMessageIds.Control.ENCAPSULATED_SSL) {
                        throw IOException("expected handshake record, got 0x${message.id.toString(16)}")
                    }
                    if (!refuseHeadUnit) feed(message.body) else throw IOException("head unit refused")
                    engine.handshakeStatus
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = engine.delegatedTask
                    }
                    engine.handshakeStatus
                }

                else -> engine.handshakeStatus
            }
        }
        headUnitFingerprint = AcceptAll.seen?.let { HeadUnitCertificate.fingerprintOf(it) }
    }

    /** Encrypts one message the way the phone would: one TLS record, channel kept in the frame. */
    fun writeMessage(channel: Int, id: Int, body: ByteArray) {
        val plaintext = AapFrame.plaintext(id, body)
        val source = ByteBuffer.wrap(plaintext)
        while (source.hasRemaining()) {
            netOut.clear()
            val result = engine.wrap(source, netOut)
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                continue
            }
            netOut.flip()
            if (netOut.hasRemaining()) {
                val record = ByteArray(netOut.remaining())
                netOut.get(record)
                writer.write(record, channel, false, true, true)
            }
        }
    }

    fun readMessage(): AapMessage? {
        while (true) {
            val message = reader.nextMessage(assembler) ?: return null
            if (!message.encrypted) throw IOException("plaintext message after handshake")
            val plaintext = feed(message.body) ?: return null
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
        }
        transport.close()
    }

    private fun wrapHandshake(): SSLEngineResult.HandshakeStatus {
        netOut.clear()
        val result = engine.wrap(ByteBuffer.allocate(0), netOut)
        if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            netOut = ByteBuffer.allocate(netOut.capacity() * 2)
            return engine.handshakeStatus
        }
        netOut.flip()
        if (netOut.hasRemaining()) {
            // Same framing as the head unit: message id first, then the TLS record as the body.
            val message = AapFrame.plaintext(AapMessageIds.Control.ENCAPSULATED_SSL, ByteArray(netOut.remaining()).also { netOut.get(it) })
            writer.write(message, AapFrame.CONTROL_CHANNEL, false, false, true)
        }
        return result.handshakeStatus
    }

    /** Returns the plaintext the engine produced, or null when the peer closed. */
    private fun feed(bytes: ByteArray): ByteArray? {
        pending += bytes
        val source = ByteBuffer.wrap(pending)
        val collected = java.io.ByteArrayOutputStream()
        var closed = false
        while (source.hasRemaining()) {
            appIn.clear()
            val result = engine.unwrap(source, appIn)
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                continue
            }
            if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break
            if (result.status == SSLEngineResult.Status.CLOSED) {
                closed = true
                break
            }
            appIn.flip()
            if (appIn.hasRemaining()) {
                val chunk = ByteArray(appIn.remaining())
                appIn.get(chunk)
                collected.write(chunk)
            }
        }
        pending = ByteArray(source.remaining())
        source.get(pending)
        return if (closed) null else collected.toByteArray()
    }

    private object AcceptAll : X509ExtendedTrustManager() {
        var seen: X509Certificate? = null

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
}

/** Alias the simulated phone's key manager answers under. */
private const val KEY_ALIAS = "simulated-phone"

private class ServerKeyManager(
    private val keyPair: KeyPair,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(KEY_ALIAS)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String = KEY_ALIAS

    // Same SSLEngine-only pair as the head unit's key manager: without them the server end
    // presents nothing and the handshake fails on the suites rather than on the identity.
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
