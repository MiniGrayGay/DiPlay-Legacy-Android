package com.shilapi.xcertplay.aap

import java.io.Closeable
import java.io.IOException
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

/** The head unit's TLS identity: one self-signed certificate and its key. */
class AapIdentity(val keyPair: KeyPair, val certificate: X509Certificate) {

    /** SHA-256 of the certificate, the value the phone pins on first use. */
    val fingerprint: String get() = HeadUnitCertificate.fingerprintOf(certificate)

    override fun toString(): String = certificate.subjectX500Principal.name

    companion object {
        /**
         * Creates an identity from nothing.
         *
         * A head unit that generates one per connection would make the phone ask for trust every
         * time, so callers are expected to generate this once and store it — see the Android-side
         * store. This is only for tests and for a first run with nothing saved.
         */
        fun generate(commonName: String = "DiPlay Head Unit"): AapIdentity {
            val generated = HeadUnitCertificate.generate(commonName = commonName, organization = "DiPlay")
            return AapIdentity(generated.keyPair, generated.certificate)
        }
    }
}

/**
 * One Android Auto session, from the head unit's side.
 *
 * The order is fixed and every step is gated on the previous one:
 *
 * ```
 * HU  -> phone   VersionRequest        plaintext, raw bytes, not protobuf
 * phone -> HU    VersionResponse      plaintext
 *                TLS handshake         HU is the client, phone the server
 * HU  -> phone   AuthComplete         plaintext, the last unencrypted message
 * phone -> HU    ServiceDiscoveryRequest   encrypted; the phone asks what the car can do
 * HU  -> phone   ServiceDiscoveryResponse  the service list from [AapServiceSet]
 * phone -> HU    ChannelOpenRequest   one per service it wants
 * HU  -> phone   ChannelOpenResponse  same channel, CONTROL flag
 * ```
 *
 * Note the direction of discovery: the **phone** asks and the **car** answers. It reads backwards
 * from a head unit that announces itself, and building it the other way round produces a session
 * that stalls after `AuthComplete` with no error at either end.
 *
 * Threading: [connect] and [pump] block on the transport, so they run on one read thread. [send]
 * may be called from any thread — a touch event comes from the UI thread while video acks come
 * from the read thread — and is serialised internally, because TLS records must leave in the order
 * they were sealed.
 */
class AapSession(
    private val transport: AapTransport,
    val identity: AapIdentity,
    val services: AapServiceSet,
    /** Fingerprint the driver already trusted, or null on first connection. */
    private val expectedPhoneFingerprint: String? = null,
    /**
     * Where `ChannelOpenResponse` is written.
     *
     * AARE-style phones read it on the service channel with the CONTROL flag, which is the default
     * here. Google's own phone reads it on the control channel; the flag exists so a session
     * against a real phone can be attempted without editing code, and documents that the two
     * differ rather than pretending one is wrong.
     */
    private val channelOpenResponseOnControlChannel: Boolean = false,
    /** How long [connect] may spend in each phase before giving up. */
    private val phaseTimeoutMillis: Int = 15_000,
    private val log: (String) -> Unit = {},
) : Closeable {

    enum class State { IDLE, VERSION, HANDSHAKE, AUTH, DISCOVERY, RUNNING, CLOSED }

    interface Listener {
        fun onState(state: State) {}
        fun onPhoneDiscovered(request: AapControlMessages.ServiceDiscoveryRequest) {}

        /** Every channel the phone asked for has been answered. */
        fun onReady(session: AapSession) {}
        fun onChannelOpen(channel: Int) {}
        fun onChannelClose(channel: Int) {}
        fun onAudioFocus(focus: AapControlMessages.AudioFocus) {}

        /** Traffic on a service channel: video, audio, input feedback, sensors. */
        fun onServiceMessage(channel: Int, message: AapMessage) {}
        fun onClosed(reason: String, cause: Throwable?) {}
    }

    @Volatile
    var state: State = State.IDLE
        private set

    /** Protocol version the phone answered, once it has. */
    @Volatile
    var phoneVersion: String? = null
        private set

    /** Fingerprint of the certificate the phone presented. */
    @Volatile
    var phoneFingerprint: String? = null
        private set

    /** What the phone said about itself. */
    @Volatile
    var phoneDescription: AapControlMessages.ServiceDiscoveryRequest? = null
        private set

    var listener: Listener? = null

    private val preTlsReader = AapFrameReader(transport.inputStream())

    /** Reassembles the version response, which may arrive split like any other message. */
    private val preTlsAssembler = AapMessageAssembler()
    private val writer = AapFrameWriter(transport.outputStream())
    private val tls: AapTlsStream by lazy {
        AapTlsStream(transport, identity.keyPair, identity.certificate, expectedPhoneFingerprint)
    }

    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private val openChannels = LinkedHashSet<Int>()

    /** Opens the session: version, TLS, auth, discovery. Returns true once [State.RUNNING]. */
    fun connect(): Boolean {
        try {
            sendPlaintext(
                AapFrame.CONTROL_CHANNEL,
                AapMessageIds.Control.VERSION_REQUEST,
                AapControlMessages.encodeVersionRequest(),
            )
            setState(State.VERSION)
            val version = withDeadline("version response") { readVersionResponse() }
            if (version == null) return fail("phone closed before answering the version request")
            if (!version.supported) return fail("phone refused protocol ${version.major}.${version.minor}: ${AapStatus.describe(version.status)}")
            phoneVersion = version.toString()
            note("phone speaks $version")

            setState(State.HANDSHAKE)
            withDeadline("TLS handshake") { tls.handshake() }
            phoneFingerprint = tls.phoneFingerprint
            note("TLS up; phone certificate ${phoneFingerprint ?: "none"}")

            // Cleartext on purpose: the phone only starts decrypting after this message.
            setState(State.AUTH)
            tls.writeCleartext(
                AapMessage(
                    AapFrame.CONTROL_CHANNEL,
                    control = false,
                    encrypted = false,
                    id = AapMessageIds.Control.AUTH_COMPLETE,
                    body = AapControlMessages.encodeAuthResponse(),
                ),
            )
            note("sent AuthComplete")

            setState(State.DISCOVERY)
            val request = withDeadline("service discovery request") { readDiscoveryRequest() }
            if (request == null) return fail("phone never asked for the service list")
            phoneDescription = request
            note("phone is $request")
            listener?.onPhoneDiscovered(request)
            send(AapFrame.CONTROL_CHANNEL, AapMessageIds.Control.SERVICE_DISCOVERY_RESPONSE, services.encodeResponse())
            note("sent ${services.channels().size} channels: ${services.channels()}")

            setState(State.RUNNING)
            listener?.onReady(this)
            return true
        } catch (e: Exception) {
            return fail(describeFailure(e), e)
        }
    }

    /**
     * Reads and dispatches one message. Returns false when the session is over.
     *
     * A caller with a UI can use this to keep its own event loop; [run] is the simple form.
     */
    fun pump(): Boolean {
        if (closed.get()) return false
        val message = try {
            tls.read()
        } catch (e: Exception) {
            fail(describeFailure(e), e)
            return false
        }
        if (message == null) {
            fail("phone ended the session", null)
            return false
        }
        try {
            dispatch(message)
        } catch (e: Exception) {
            // A broken handler must not take the whole session down: the next frame is still valid.
            note("handler failed for $message: ${e.message}")
        }
        return !closed.get()
    }

    /** Runs until the session ends. Blocks the calling thread. */
    fun run() {
        while (pump()) {
            // Nothing to do: pump reads and dispatches.
        }
    }

    /** Sends an encrypted message on [channel]. Thread safe. */
    fun send(channel: Int, id: Int, body: ByteArray, control: Boolean = false) {
        if (closed.get()) return
        synchronized(writeLock) {
            try {
                tls.write(AapMessage(channel, control, true, id, body))
            } catch (e: Exception) {
                fail("write failed: ${describeFailure(e)}", e)
            }
        }
    }

    // ------------------------------------------------------------------ convenience senders

    /** Tells the phone whether the car is showing it. */
    fun sendVideoFocus(mode: Int, unsolicited: Boolean = false) {
        send(
            services.videoChannel,
            AapMessageIds.Media.VIDEO_FOCUS_NOTIFICATION,
            AapMediaMessages.encodeVideoFocusNotification(mode, unsolicited),
        )
    }

    /** Asks the phone to hand the screen back to the car's own UI. */
    fun requestNativeFocus() {
        send(
            services.videoChannel,
            AapMessageIds.Media.VIDEO_FOCUS_REQUEST,
            AapMediaMessages.encodeVideoFocusRequest(
                AapMediaMessages.VideoFocusMode.NATIVE,
                AapMediaMessages.VideoFocusReason.LAUNCH_NATIVE,
            ),
        )
    }

    /** Acks one video frame, keeping the phone's in-flight window open. */
    fun ackVideo(sessionId: Int, ack: Int = 1) {
        send(services.videoChannel, AapMessageIds.Media.ACK, AapMediaMessages.encodeAck(sessionId, ack))
    }

    /**
     * Reports a touch. [x] and [y] are already in the phone's video coordinates.
     */
    fun sendTouch(timestampMicros: Long, x: Int, y: Int, action: Int) {
        send(
            services.inputChannel,
            AapMessageIds.Input.INPUT_REPORT,
            AapInputMessages.encodeTouch(timestampMicros, x, y, action),
        )
    }

    /** Reports a hardware key press on the car's steering wheel or panel. */
    fun sendKey(timestampMicros: Long, keycode: Int, down: Boolean, longPress: Boolean = false) {
        send(
            services.inputChannel,
            AapMessageIds.Input.INPUT_REPORT,
            AapInputMessages.encodeKey(timestampMicros, keycode, down, longPress),
        )
    }

    /** Reports the car's day/night state so the phone switches palettes. */
    fun sendNightMode(night: Boolean) {
        send(
            services.sensorChannel,
            AapMessageIds.Sensor.BATCH,
            AapSensorMessages.encodeBatch(nightMode = night),
        )
    }

    /** Reports whether the car is moving, which is what locks out text entry on the phone. */
    fun sendDrivingStatus(status: Int) {
        send(
            services.sensorChannel,
            AapMessageIds.Sensor.BATCH,
            AapSensorMessages.encodeBatch(drivingStatus = status),
        )
    }

    /** Ends the session politely; the phone shows its own "disconnected" state instead of an error. */
    fun byeBye(reason: Int = AapControlMessages.ByeByeReason.USER_SELECTION) {
        if (closed.get()) return
        send(AapFrame.CONTROL_CHANNEL, AapMessageIds.Control.BYEBYE_REQUEST, AapControlMessages.encodeByeByeRequest(reason))
        // Give the write a moment to leave before tearing the socket down under it.
        Thread.sleep(BYE_BYE_GRACE_MILLIS)
        close()
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
        val onControlChannel = message.channel == AapFrame.CONTROL_CHANNEL
        if (message.control) {
            // Channel-scoped control traffic: open and close notifications for one service.
            when (message.id) {
                AapMessageIds.Control.CHANNEL_OPEN_REQUEST -> openChannel(message.channel)
                AapMessageIds.Control.CHANNEL_CLOSE_NOTIFICATION -> {
                    openChannels.remove(message.channel)
                    listener?.onChannelClose(message.channel)
                }

                else -> note("ignoring control 0x${message.id.toString(16)} on channel ${message.channel}")
            }
            return
        }
        if (onControlChannel) {
            handleControl(message)
            return
        }
        listener?.onServiceMessage(message.channel, message)
    }

    private fun handleControl(message: AapMessage) {
        when (message.id) {
            AapMessageIds.Control.CHANNEL_OPEN_REQUEST -> openChannel(
                AapControlMessages.decodeChannelOpenRequest(message.body).serviceId,
            )

            AapMessageIds.Control.PING_REQUEST ->
                send(
                    AapFrame.CONTROL_CHANNEL,
                    AapMessageIds.Control.PING_RESPONSE,
                    AapControlMessages.encodePingResponse(AapControlMessages.decodePingTimestamp(message.body)),
                )

            AapMessageIds.Control.PING_RESPONSE -> Unit

            AapMessageIds.Control.AUDIO_FOCUS_NOTIFICATION ->
                listener?.onAudioFocus(AapControlMessages.decodeAudioFocus(message.body))

            AapMessageIds.Control.BYEBYE_REQUEST -> {
                val reason = AapControlMessages.decodeByeByeReason(message.body)
                send(AapFrame.CONTROL_CHANNEL, AapMessageIds.Control.BYEBYE_RESPONSE, ByteArray(0))
                fail("phone ended the session (reason $reason)", null)
            }

            AapMessageIds.Control.BYEBYE_RESPONSE -> fail("phone acknowledged the good-bye", null)

            // The phone re-announces services after a configuration change; nothing here depends on
            // the list, so accepting it silently is correct rather than rude.
            AapMessageIds.Control.SERVICE_DISCOVERY_UPDATE -> note("phone sent a service discovery update")

            else -> note("ignoring control 0x${message.id.toString(16)}")
        }
    }

    private fun openChannel(channel: Int) {
        if (openChannels.add(channel)) {
            note("phone opened channel $channel")
            listener?.onChannelOpen(channel)
        }
        // Answer even a duplicate: a phone that re-opened the channel would otherwise wait forever.
        val response = AapControlMessages.encodeChannelOpenResponse()
        val id = AapMessageIds.Control.CHANNEL_OPEN_RESPONSE
        if (channelOpenResponseOnControlChannel) {
            send(AapFrame.CONTROL_CHANNEL, id, response)
        } else {
            send(channel, id, response, control = true)
        }
    }

    private fun readVersionResponse(): AapControlMessages.Version? {
        while (true) {
            val message = preTlsReader.nextMessage(preTlsAssembler) ?: return null
            if (message.channel != AapFrame.CONTROL_CHANNEL) continue
            if (message.id != AapMessageIds.Control.VERSION_RESPONSE) {
                note("ignoring 0x${message.id.toString(16)} before the version response")
                continue
            }
            return AapControlMessages.decodeVersionResponse(message.body)
        }
    }

    private fun readDiscoveryRequest(): AapControlMessages.ServiceDiscoveryRequest? {
        while (true) {
            val message = tls.read() ?: return null
            if (message.channel != AapFrame.CONTROL_CHANNEL) {
                note("ignoring channel ${message.channel} before discovery")
                continue
            }
            when (message.id) {
                AapMessageIds.Control.SERVICE_DISCOVERY_REQUEST ->
                    return AapControlMessages.decodeServiceDiscoveryRequest(message.body)

                AapMessageIds.Control.PING_REQUEST -> {
                    send(
                        AapFrame.CONTROL_CHANNEL,
                        AapMessageIds.Control.PING_RESPONSE,
                        AapControlMessages.encodePingResponse(
                            AapControlMessages.decodePingTimestamp(message.body),
                        ),
                    )
                }

                AapMessageIds.Control.BYEBYE_REQUEST -> return null
                else -> note("ignoring 0x${message.id.toString(16)} before discovery")
            }
        }
    }

    private fun sendPlaintext(channel: Int, id: Int, body: ByteArray) {
        synchronized(writeLock) {
            writer.write(AapMessage(channel, false, false, id, body), flush = true)
        }
    }

    private fun setState(next: State) {
        state = next
        listener?.onState(next)
    }

    private fun fail(reason: String, cause: Throwable? = null): Boolean {
        if (closed.compareAndSet(false, true)) {
            setState(State.CLOSED)
            note("session failed: $reason")
            // The log has no room for a full trace, but the first frames are what turn "write
            // failed" into an answer, and they cost nothing when the session is already over.
            cause?.stackTrace?.take(3)?.forEach { note("  at $it") }
            runCatching { tls.close() }
            runCatching { transport.close() }
            listener?.onClosed(reason, cause)
        }
        return false
    }

    /** Turns an I/O exception into something a driver-facing message can say. */
    private fun describeFailure(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "the phone stopped responding"
        is java.io.EOFException -> "the phone disconnected"
        is javax.net.ssl.SSLException -> "TLS failed: ${e.message}"
        is IOException -> e.message ?: "connection failed"
        // A null message is the norm for the failures worth seeing, so the type has to be in the
        // text: "write failed: null" tells whoever reads the log nothing at all.
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * Runs [block] with a ceiling.
     *
     * Implemented by closing the transport when the deadline passes, because that is the only
     * portable way to unblock a read: sockets, USB bulk transfers and in-memory pipes all return
     * -1 or throw once closed, while a read timeout exists only for sockets.
     */
    private fun <T> withDeadline(what: String, block: () -> T): T {
        val finished = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val watchdog = Thread {
            try {
                Thread.sleep(phaseTimeoutMillis.toLong())
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!finished.get()) {
                timedOut.set(true)
                note("timed out waiting for $what")
                runCatching { transport.close() }
            }
        }
        watchdog.isDaemon = true
        watchdog.name = "aap-deadline"
        watchdog.start()
        try {
            return block()
        } catch (e: Exception) {
            if (timedOut.get()) throw IOException("timed out waiting for $what", e)
            throw e
        } finally {
            finished.set(true)
            watchdog.interrupt()
        }
    }

    private fun note(line: String) {
        log(line)
    }

    private companion object {
        const val BYE_BYE_GRACE_MILLIS = 150L
    }
}
