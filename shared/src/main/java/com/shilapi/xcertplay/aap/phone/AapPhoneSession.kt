package com.shilapi.xcertplay.aap.phone

import com.shilapi.xcertplay.aap.AapControlMessages
import com.shilapi.xcertplay.aap.AapFrame
import com.shilapi.xcertplay.aap.AapFrameReader
import com.shilapi.xcertplay.aap.AapIdentity
import com.shilapi.xcertplay.aap.AapMessage
import com.shilapi.xcertplay.aap.AapMessageAssembler
import com.shilapi.xcertplay.aap.AapMessageIds
import com.shilapi.xcertplay.aap.AapServiceDiscovery
import com.shilapi.xcertplay.aap.AapTransport
import com.shilapi.xcertplay.aap.inputStream
import com.shilapi.xcertplay.aap.outputStream
import com.shilapi.xcertplay.proto.ProtoWire
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's half of an Android Auto session.
 *
 * A head unit never runs this. It exists so a head unit can be exercised against a real peer — in
 * the protocol tests, and in the "phone" app that runs on a second emulator during development.
 * The conversation it drives is exactly the one a phone drives:
 *
 * ```
 * phone -> HU   VersionResponse           1.7, or 6.1 for head units on the newer numbering
 *                TLS handshake              phone is the server
 * phone -> HU   AuthComplete              the last plaintext message, sent by the head unit
 * phone -> HU   ServiceDiscoveryRequest   "what can you do?"
 * HU -> phone   ServiceDiscoveryResponse
 * phone -> HU   ChannelOpenRequest        one per service, on that service's channel, CONTROL flag
 * ```
 *
 * Trust is on first use: the head unit's certificate is recorded as a fingerprint and never
 * validated against an issuer, because no head unit in this design has one.
 */
class AapPhoneSession(
    private val transport: AapTransport,
    val identity: AapIdentity,
    private val deviceName: String = "Android phone",
    private val label: String = "",
    private val log: (String) -> Unit = {},
) : Closeable {

    enum class State { IDLE, VERSION, HANDSHAKE, AUTH, DISCOVERY, OPENING, RUNNING, CLOSED }

    interface Listener {
        fun onState(state: State) {}
        fun onDiscovery(discovery: AapServiceDiscovery.Discovery) {}
        fun onChannelOpen(channel: Int, service: AapServiceDiscovery.Service) {}
        fun onServiceMessage(channel: Int, message: AapMessage) {}
        fun onClosed(reason: String, cause: Throwable?) {}
    }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var discovery: AapServiceDiscovery.Discovery? = null
        private set

    @Volatile
    var headUnitFingerprint: String? = null
        private set

    var listener: Listener? = null

    private val preTlsReader = AapFrameReader(transport.inputStream())
    private val preTlsAssembler = AapMessageAssembler()
    private val preTlsWriter = com.shilapi.xcertplay.aap.AapFrameWriter(transport.outputStream())
    private val tls: PhoneTlsStream by lazy { PhoneTlsStream(transport, identity.keyPair, identity.certificate) }

    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private val openedChannels = LinkedHashSet<Int>()

    /** Runs the opening sequence. Returns true once every advertised channel is open. */
    fun connect(): Boolean {
        try {
            setState(State.VERSION)
            val request = readVersionRequest() ?: return fail("head unit closed before the version request", null)
            val reply = versionReplyTo(request)
            note("head unit asked for $request, answering ${reply.first}.${reply.second}")
            preTlsWriter.write(
                AapMessage(
                    AapFrame.CONTROL_CHANNEL,
                    control = false,
                    encrypted = false,
                    id = AapMessageIds.Control.VERSION_RESPONSE,
                    body = AapControlMessages.encodeVersionRequest(reply.first, reply.second),
                ),
                flush = true,
            )

            setState(State.HANDSHAKE)
            tls.handshake()
            headUnitFingerprint = tls.headUnitFingerprint
            note("TLS up; head unit certificate $headUnitFingerprint")

            setState(State.AUTH)
            val auth = readPlaintext(AapMessageIds.Control.AUTH_COMPLETE)
                ?: return fail("head unit never sent AuthComplete", null)
            val status = AapControlMessages.decodeAuthStatus(auth.body)
            if (status != 0) return fail("head unit rejected the phone (status $status)", null)
            note("authenticated")

            setState(State.DISCOVERY)
            write(
                AapFrame.CONTROL_CHANNEL,
                AapMessageIds.Control.SERVICE_DISCOVERY_REQUEST,
                ProtoWire.Writer().string(4, label.ifEmpty { deviceName }).string(5, deviceName).build(),
            )
            val response = readUntilEncrypted(AapMessageIds.Control.SERVICE_DISCOVERY_RESPONSE)
                ?: return fail("head unit never described its services", null)
            val parsed = AapServiceDiscovery.parse(response.body)
            discovery = parsed
            note("head unit offers ${parsed.services.size} services: ${parsed.services.joinToString()}")

            setState(State.OPENING)
            openChannels(parsed)
            if (openedChannels.size != parsed.services.count { it.kind != AapServiceDiscovery.Kind.OTHER }) {
                note("only ${openedChannels.size} channels opened: $openedChannels")
            }
            setState(State.RUNNING)
            listener?.onDiscovery(parsed)
            return true
        } catch (e: Exception) {
            return fail(e.message ?: e.javaClass.simpleName, e)
        }
    }

    /** Reads and dispatches one message. Returns false when the session is over. */
    fun pump(): Boolean {
        if (closed.get()) return false
        val message = try {
            tls.read()
        } catch (e: Exception) {
            fail(e.message ?: "read failed", e)
            return false
        }
        if (message == null) {
            fail("head unit ended the session", null)
            return false
        }
        try {
            dispatch(message)
        } catch (e: Exception) {
            note("handler failed for $message: ${e.message}")
        }
        return !closed.get()
    }

    fun run() {
        while (pump()) {
            // pump dispatches.
        }
    }

    fun send(channel: Int, id: Int, body: ByteArray, control: Boolean = false) {
        if (closed.get()) return
        write(channel, id, body, control)
    }

    /** Sends one DATA message on a media channel: 8-byte microsecond timestamp then the payload. */
    fun sendMediaData(channel: Int, timestampMicros: Long, data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        val body = ByteArray(AapMediaMessagesHeaders.DATA_HEADER + length)
        for (i in 0 until 8) body[i] = (timestampMicros ushr (56 - 8 * i)).toByte()
        System.arraycopy(data, offset, body, 8, length)
        send(channel, AapMessageIds.Media.DATA, body)
    }

    /** Sends a codec configuration blob (SPS/PPS for H.264) on a media channel. */
    fun sendCodecConfig(channel: Int, config: ByteArray) {
        send(channel, AapMessageIds.Media.CODEC_CONFIG, config)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        setState(State.CLOSED)
        runCatching { tls.close() }
        runCatching { transport.close() }
    }

    val isOpen: Boolean get() = !closed.get()

    // ------------------------------------------------------------------ internals

    private fun dispatch(message: AapMessage) {
        if (message.control) {
            // Channel-scoped control: the head unit's answer to an open request.
            if (message.id == AapMessageIds.Control.CHANNEL_OPEN_RESPONSE) {
                markOpen(message.channel)
            } else if (message.id == AapMessageIds.Control.CHANNEL_CLOSE_NOTIFICATION) {
                openedChannels.remove(message.channel)
            }
            return
        }
        if (message.channel == AapFrame.CONTROL_CHANNEL) {
            when (message.id) {
                AapMessageIds.Control.CHANNEL_OPEN_RESPONSE -> markOpen(discoveryChannelFor(message))
                AapMessageIds.Control.PING_REQUEST -> send(
                    AapFrame.CONTROL_CHANNEL,
                    AapMessageIds.Control.PING_RESPONSE,
                    AapControlMessages.encodePingResponse(AapControlMessages.decodePingTimestamp(message.body)),
                )

                AapMessageIds.Control.BYEBYE_REQUEST -> fail("head unit ended the session", null)
                else -> note("ignoring control 0x${message.id.toString(16)}")
            }
            return
        }
        listener?.onServiceMessage(message.channel, message)
    }

    /**
     * A `ChannelOpenResponse` on the control channel does not say which channel it opened.
     *
     * Google's phone reads them here and relies on ordering; this implementation matches answers to
     * requests in the order they were sent, which is the only information available.
     */
    private fun discoveryChannelFor(message: AapMessage): Int {
        val pending = (discovery?.services ?: emptyList())
            .filter { it.kind != AapServiceDiscovery.Kind.OTHER }
            .map { it.channel }
            .filter { it !in openedChannels }
        return pending.firstOrNull() ?: message.channel
    }

    private fun markOpen(channel: Int) {
        if (openedChannels.add(channel)) {
            val service = discovery?.services?.firstOrNull { it.channel == channel }
            note("channel $channel open${if (service != null) " ($service)" else ""}")
            if (service != null) listener?.onChannelOpen(channel, service)
        }
    }

    private fun openChannels(parsed: AapServiceDiscovery.Discovery) {
        for (service in parsed.services) {
            if (service.kind == AapServiceDiscovery.Kind.OTHER) continue
            if (!wanted(service.kind)) continue
            val request = ProtoWire.Writer().int32(1, 0).int32(2, service.channel).build()
            send(service.channel, AapMessageIds.Control.CHANNEL_OPEN_REQUEST, request, control = true)
        }
        // Read until every requested channel is answered. Pings and unrelated traffic arrive in
        // between and are handled rather than treated as an error.
        val wanted = parsed.services.count { it.kind != AapServiceDiscovery.Kind.OTHER && wanted(it.kind) }
        var rounds = 0
        while (openedChannels.size < wanted && !closed.get()) {
            if (++rounds > MAX_ROUNDS) {
                note("gave up after $MAX_ROUNDS messages waiting for channel opens")
                break
            }
            val message = tls.read() ?: break
            dispatch(message)
        }
    }

    private fun wanted(kind: AapServiceDiscovery.Kind): Boolean = when (kind) {
        AapServiceDiscovery.Kind.VIDEO, AapServiceDiscovery.Kind.AUDIO,
        AapServiceDiscovery.Kind.INPUT, AapServiceDiscovery.Kind.SENSOR,
        -> true

        AapServiceDiscovery.Kind.OTHER -> false
    }

    /**
     * AARE's rule, copied because it is what real phones do: answer 1.7 to a head unit that asks
     * for 1.7 or lower, and 6.1 to the newer "Google Automotive Link" numbering. A phone that
     * always answers 1.7 makes a 6.x head unit fall back to the older protocol and lose features.
     */
    private fun versionReplyTo(request: AapControlMessages.Version): Pair<Int, Int> =
        if (request.major < 1 || (request.major == 1 && request.minor <= 7)) 1 to 7 else 6 to 1

    private fun readVersionRequest(): AapControlMessages.Version? {
        while (true) {
            val message = preTlsReader.nextMessage(preTlsAssembler) ?: return null
            if (message.id == AapMessageIds.Control.VERSION_REQUEST) {
                return AapControlMessages.decodeVersionResponse(message.body)
            }
            note("ignoring 0x${message.id.toString(16)} before the version request")
        }
    }

    private fun readPlaintext(id: Int): AapMessage? {
        while (true) {
            val message = preTlsReader.nextMessage(preTlsAssembler) ?: return null
            if (message.id == id) return message
            note("ignoring 0x${message.id.toString(16)} while waiting for 0x${id.toString(16)}")
        }
    }

    private fun readUntilEncrypted(id: Int): AapMessage? {
        while (true) {
            val message = tls.read() ?: return null
            when {
                message.id == id -> return message
                message.id == AapMessageIds.Control.PING_REQUEST -> send(
                    AapFrame.CONTROL_CHANNEL,
                    AapMessageIds.Control.PING_RESPONSE,
                    AapControlMessages.encodePingResponse(
                        AapControlMessages.decodePingTimestamp(message.body),
                    ),
                )

                else -> note("ignoring 0x${message.id.toString(16)} while waiting for 0x${id.toString(16)}")
            }
        }
    }

    private fun write(channel: Int, id: Int, body: ByteArray, control: Boolean = false) {
        synchronized(writeLock) {
            try {
                tls.write(AapMessage(channel, control, true, id, body))
            } catch (e: Exception) {
                fail("write failed: ${e.message}", e)
            }
        }
    }

    private fun setState(next: State) {
        state = next
        listener?.onState(next)
    }

    private fun fail(reason: String, cause: Throwable?): Boolean {
        if (closed.compareAndSet(false, true)) {
            setState(State.CLOSED)
            note("session failed: $reason")
            runCatching { tls.close() }
            runCatching { transport.close() }
            listener?.onClosed(reason, cause)
        }
        return false
    }

    private fun note(line: String) {
        log(line)
    }

    private companion object {
        const val MAX_ROUNDS = 64
    }
}

/** Layout constants the phone side needs when it writes media messages itself. */
object AapMediaMessagesHeaders {
    const val DATA_HEADER = 8
}
