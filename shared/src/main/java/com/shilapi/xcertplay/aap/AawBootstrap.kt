package com.shilapi.xcertplay.aap

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The head unit's half of the wireless Android Auto bootstrap: the conversation that runs over
 * Bluetooth RFCOMM before any video exists.
 *
 * The sequence, mirroring what a real head unit does and what AARE's phone side expects:
 *
 * ```
 * HU  -> phone   WifiVersionRequest        (optional, but strict units are gated on the answer)
 * phone -> HU    WifiVersionResponse
 * HU  -> phone   WifiStartRequest          (ip + port of this head unit's AAP server)
 * phone -> HU    WifiInfoRequest           (asks for the access point)
 * HU  -> phone   WifiInfoResponse          (ssid + passphrase + security mode)
 * phone          joins the access point
 * phone -> HU    WifiConnectionStatus      (0 = joined)
 * phone -> HU    WifiStartResponse
 * phone          opens TCP to (ip, port) and the AAP TLS handshake begins
 * ```
 *
 * Deviations that are deliberate:
 *
 * - **The access point credentials are sent without waiting to be asked**, right after the start
 *   request. AARE accepts them unsolicited ("credentials sent without being asked"), and a phone
 *   that never gets around to sending `WifiInfoRequest` would otherwise stall until it times out.
 *   A later `WifiInfoRequest` is still answered.
 * - **Pings are answered by echoing their payload.** The phone pings every couple of seconds while
 *   it joins, and some cars drop a silent link in about 15 s; the payload is a timestamp the phone
 *   only uses for round-trip timing, so echoing needs no knowledge of the message shape.
 * - **No timer decides anything on this side.** Every step is driven by an incoming frame, so the
 *   whole exchange can be unit tested against an in-memory pipe with no clock. The overall deadline
 *   belongs to the caller: closing the streams unblocks the read, which is what the Android side
 *   does with `BluetoothSocket.close()`.
 *
 * Threading: [run] blocks and must be called on a dedicated thread. It returns once the phone reports
 * that it joined the network; the RFCOMM link is left open afterwards, because the phone holds it for
 * the whole session and re-dials on drop. Use [serveLink] to keep answering pings, and [close] to end.
 */
class AawBootstrap(
    private val input: InputStream,
    private val output: OutputStream,
    /** Supplies what the phone must join. Called once, when the credentials are first needed. */
    private val accessPoint: () -> AawAccessPoint,
    /** Where this head unit's AAP TCP server listens, as the phone should reach it. */
    private val server: AawServerEndpoint,
    private val deviceName: String? = null,
    private val log: (String) -> Unit = {},
) {

    /** What the driver-facing UI can show while the bootstrap runs. */
    enum class State { IDLE, GREETING, AWAITING_CREDENTIALS_REQUEST, AWAITING_JOIN, READY, FAILED }

    @Volatile
    var state: State = State.IDLE
        private set

    /** Version the phone answered, once it has. */
    @Volatile
    var versionResponse: AawVersionResponse? = null
        private set

    @Volatile
    var startResponse: AawStartResponse? = null
        private set

    @Volatile
    var connectionStatus: AawConnectionStatus? = null
        private set

    private var credentials: AawAccessPoint? = null
    private val protocolLog = StringBuilder()

    /**
     * Runs the exchange and returns once the phone says it is on the network.
     *
     * Never throws for a protocol failure: a phone that refuses is a normal outcome the UI has to
     * show, so it comes back as [AawOutcome.Failed] with the phone's own status code.
     */
    fun run(): AawOutcome {
        try {
            state = State.GREETING
            note("sending WifiVersionRequest")
            write(AawWpp.MessageId.VERSION_REQUEST, AawWpp.encodeVersionRequest(carInfo = carInfo()))

            state = State.AWAITING_CREDENTIALS_REQUEST
            note("sending WifiStartRequest ${server.ipAddress}:${server.port}")
            write(
                AawWpp.MessageId.START_REQUEST,
                AawWpp.encodeStartRequest(server.ipAddress, server.port),
            )
            // Proactive: see the class comment. Sent once; a later WifiInfoRequest re-sends it.
            sendCredentials()

            state = State.AWAITING_JOIN
            while (true) {
                val frame = AawWpp.readFrame(input)
                if (frame == null) {
                    state = State.FAILED
                    return AawOutcome.Failed("phone closed the Bluetooth link during the bootstrap")
                }
                note("<- $frame")
                when (frame.id) {
                    AawWpp.MessageId.VERSION_RESPONSE -> {
                        versionResponse = AawWpp.decodeVersionResponse(frame.payload)
                        note("phone ${versionResponse}")
                    }

                    AawWpp.MessageId.START_RESPONSE -> {
                        val response = AawWpp.decodeStartResponse(frame.payload)
                        startResponse = response
                        note("phone $response")
                        if (!response.accepted) {
                            // The phone refuses the endpoint rather than the credentials; there is
                            // nothing to retry until the address changes.
                            state = State.FAILED
                            return AawOutcome.Failed(
                                "phone refused the AAP endpoint (${AawWpp.Status.name(response.status)})",
                                response.status,
                            )
                        }
                    }

                    AawWpp.MessageId.INFO_REQUEST -> {
                        note("phone asked for credentials")
                        sendCredentials()
                    }

                    AawWpp.MessageId.CONNECTION_STATUS -> {
                        val status = AawWpp.decodeConnectionStatus(frame.payload)
                        connectionStatus = status
                        note("phone $status")
                        if (status.joined) {
                            state = State.READY
                            return AawOutcome.Ready(versionResponse, startResponse, status)
                        }
                        // A non-zero status here means the phone could not join; the phone sends
                        // nothing further, so waiting would only stall until the caller's deadline.
                        state = State.FAILED
                        return AawOutcome.Failed(
                            "phone could not join the access point: $status",
                            status.status,
                        )
                    }

                    AawWpp.MessageId.PING_REQUEST ->
                        write(AawWpp.MessageId.PING_RESPONSE, AawWpp.pingResponseFor(frame))

                    AawWpp.MessageId.CONNECTION_REJECTION -> {
                        state = State.FAILED
                        return AawOutcome.Failed("phone rejected the connection")
                    }

                    else -> note("ignoring ${AawWpp.MessageId.name(frame.id)}")
                }
            }
        } catch (e: IOException) {
            state = State.FAILED
            return AawOutcome.Failed("Bluetooth link failed: ${e.message}", cause = e)
        } catch (e: IllegalStateException) {
            // Malformed protobuf or an impossible length; both mean the peer is not who we think.
            state = State.FAILED
            return AawOutcome.Failed("malformed WPP message: ${e.message}", cause = e)
        }
    }

    /**
     * Keeps the RFCOMM link alive after [run] succeeded, answering pings, until it closes.
     *
     * The phone holds this link for the whole session and treats a drop as "head unit went away",
     * so hanging up right after the TCP connect is not free. Returns when the link ends.
     */
    fun serveLink() {
        try {
            while (true) {
                val frame = AawWpp.readFrame(input) ?: return
                when (frame.id) {
                    AawWpp.MessageId.PING_REQUEST ->
                        write(AawWpp.MessageId.PING_RESPONSE, AawWpp.pingResponseFor(frame))

                    AawWpp.MessageId.INFO_REQUEST -> sendCredentials()

                    // A repeated start request means the phone restarted its end of the session.
                    AawWpp.MessageId.START_REQUEST -> {
                        note("phone re-sent WifiStartRequest; re-announcing the endpoint")
                        write(
                            AawWpp.MessageId.START_REQUEST,
                            AawWpp.encodeStartRequest(server.ipAddress, server.port),
                        )
                        sendCredentials()
                    }

                    else -> note("link: ignoring ${AawWpp.MessageId.name(frame.id)}")
                }
            }
        } catch (_: IOException) {
            // The link ended; nothing above this needed to know how.
        }
    }

    /** Closes both streams, which unblocks [run] or [serveLink]. Idempotent. */
    fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }

    /** Everything the bootstrap saw, for the diagnostics screen and for bug reports. */
    fun transcript(): String = protocolLog.toString()

    private fun sendCredentials() {
        val point = credentials ?: accessPoint().also { credentials = it }
        val payload = AawWpp.encodeInfoResponse(
            ssid = point.ssid,
            password = point.password,
            bssid = point.bssid,
            securityMode = point.securityMode,
            accessPointType = point.accessPointType,
        )
        write(AawWpp.MessageId.INFO_RESPONSE, payload)
        // The passphrase never goes into the transcript.
        note("sent WifiInfoResponse ssid=\"${point.ssid}\" security=${point.securityMode}")
    }

    private fun write(id: Int, payload: ByteArray) {
        AawWpp.writeFrame(output, id, payload)
        note("-> ${AawWpp.MessageId.name(id)} (${payload.size} B)")
    }

    private fun carInfo(): WppCarInfo? = deviceName?.let {
        WppCarInfo(
            headUnitMake = it,
            headUnitModel = it,
        )
    }

    private fun note(line: String) {
        synchronized(protocolLog) {
            protocolLog.append(line).append('\n')
        }
        log(line)
    }
}

/**
 * The access point the phone is asked to join.
 *
 * [password] must not be empty: AARE refuses an open network outright, so an implementation that
 * cannot set a passphrase cannot be used for wireless projection at all.
 */
data class AawAccessPoint(
    val ssid: String,
    val password: String,
    val bssid: String? = null,
    val securityMode: Int = AawWpp.SecurityMode.WPA2_PERSONAL,
    val accessPointType: Int = AawWpp.AccessPointType.DYNAMIC,
) {
    init {
        require(ssid.isNotEmpty()) { "the access point needs an SSID" }
        require(password.isNotEmpty()) {
            "wireless Android Auto needs a WPA2 passphrase; the phone refuses an open network"
        }
    }

    override fun toString() =
        "ssid=\"$ssid\" bssid=${bssid ?: "-"} security=$securityMode passphrase=${password.length} chars"
}

/** Where this head unit's AAP TCP server listens, as the phone should reach it. */
data class AawServerEndpoint(val ipAddress: String, val port: Int) {
    init {
        require(ipAddress.isNotEmpty()) { "the phone needs an address to connect back to" }
        require(port in 1..65535) { "port $port is out of range" }
    }
}

/** Result of [AawBootstrap.run]. */
sealed class AawOutcome {
    /** The phone is on the access point and is about to open the AAP connection. */
    class Ready(
        val version: AawVersionResponse?,
        val startResponse: AawStartResponse?,
        val connectionStatus: AawConnectionStatus?,
    ) : AawOutcome() {
        override fun toString() = "Ready(version=$version, start=$startResponse)"
    }

    /** The bootstrap ended without the phone on the network. [status] is 0 when it is not a WPP code. */
    class Failed(
        val reason: String,
        val status: Int = AawWpp.Status.WIFI_NETWORK_UNAVAILABLE,
        val cause: Throwable? = null,
    ) : AawOutcome() {
        override fun toString() = "Failed($reason, status=${AawWpp.Status.name(status)})"
    }
}
