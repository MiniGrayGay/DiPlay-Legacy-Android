package com.shilapi.xcertplay.aap

import com.shilapi.xcertplay.proto.ProtoWire
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The Wi-Fi Projection Protocol: the Bluetooth RFCOMM exchange through which a wireless head unit
 * tells the phone which access point to join and where its AAP server listens.
 *
 * This file is the head unit's half. Field numbers and message ids are taken from AARE's `aaw`
 * protobuf definitions and its `Wpp.kt`, which in turn decode Gearhead 17.5+. They are not guesses:
 * getting one wrong here shows up as a phone that silently never asks for credentials.
 *
 * Three things about this protocol are easy to get wrong and worth stating:
 *
 * - The frame header is `[u16 length][u16 id]`, **length only covers the payload**, and both are big
 *   endian. It is not an AAP frame and shares nothing with one.
 * - `security_mode` is a **bit-flag** value (8 = WPA2-PSK, 40 = WPA2/WPA3), not the in-session
 *   `WifiSecurityMode` enum where WPA2_PERSONAL happens to be 5.
 * - A phone **refuses an open network**. AARE rejects it outright with
 *   "refusing an open Wi-Fi network", so the head unit's access point has to be WPA2 with a
 *   passphrase. That constraint comes from the phone side, not from this code.
 */
object AawWpp {

    /**
     * RFCOMM service class the head unit publishes over SDP. The phone looks for this UUID on a
     * paired device and connects to it as a client, which is why the head unit has to be the
     * listening side.
     */
    const val RFCOMM_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"

    /** Message ids from the 4-byte header, as seen from the head unit. */
    object MessageId {
        /** HU to phone: IP and port of this head unit's AAP TCP server. */
        const val START_REQUEST = 1

        /** Phone to head unit: asks for the access point credentials. Empty payload. */
        const val INFO_REQUEST = 2

        /** HU to phone: SSID, passphrase, BSSID, security mode, access point type. */
        const val INFO_RESPONSE = 3

        /** Optional opening message carrying this head unit's WPP version. */
        const val VERSION_REQUEST = 4
        const val VERSION_RESPONSE = 5

        /** Phone to head unit: whether it got onto the network. */
        const val CONNECTION_STATUS = 6

        /** Phone to head unit: acknowledges [START_REQUEST]. */
        const val START_RESPONSE = 7

        /** Keepalive, either direction. A request is answered by echoing its payload. */
        const val PING_REQUEST = 8
        const val PING_RESPONSE = 9

        const val CONNECTION_REJECTION = 10
        const val SETUP_INFO = 11

        fun name(id: Int): String = when (id) {
            START_REQUEST -> "WifiStartRequest"
            INFO_REQUEST -> "WifiInfoRequest"
            INFO_RESPONSE -> "WifiInfoResponse"
            VERSION_REQUEST -> "WifiVersionRequest"
            VERSION_RESPONSE -> "WifiVersionResponse"
            CONNECTION_STATUS -> "WifiConnectionStatus"
            START_RESPONSE -> "WifiStartResponse"
            PING_REQUEST -> "WifiPingRequest"
            PING_RESPONSE -> "WifiPingResponse"
            CONNECTION_REJECTION -> "WifiConnectionRejection"
            SETUP_INFO -> "WifiSetupInfo"
            else -> "unknown($id)"
        }
    }

    /** Status codes shared by the WPP replies (`aaw.Status`). Negative values are legal. */
    object Status {
        const val SUCCESS = 0
        const val UNSOLICITED_MESSAGE = 1
        const val NO_COMPATIBLE_VERSION = -1
        const val WIFI_INACCESSIBLE_CHANNEL = -2
        const val WIFI_INCORRECT_CREDENTIALS = -3
        const val PROJECTION_ALREADY_STARTED = -4
        const val WIFI_DISABLED = -5
        const val WIFI_NOT_YET_STARTED = -6
        const val INVALID_HOST = -7
        const val NO_SUPPORTED_WIFI_CHANNELS = -8
        const val INSTRUCT_USER_TO_CHECK_THE_PHONE = -9
        const val PHONE_WIFI_DISABLED = -10
        const val WIFI_NETWORK_UNAVAILABLE = -11

        fun name(value: Int): String = when (value) {
            SUCCESS -> "SUCCESS"
            UNSOLICITED_MESSAGE -> "UNSOLICITED_MESSAGE"
            NO_COMPATIBLE_VERSION -> "NO_COMPATIBLE_VERSION"
            WIFI_INACCESSIBLE_CHANNEL -> "WIFI_INACCESSIBLE_CHANNEL"
            WIFI_INCORRECT_CREDENTIALS -> "WIFI_INCORRECT_CREDENTIALS"
            PROJECTION_ALREADY_STARTED -> "PROJECTION_ALREADY_STARTED"
            WIFI_DISABLED -> "WIFI_DISABLED"
            WIFI_NOT_YET_STARTED -> "WIFI_NOT_YET_STARTED"
            INVALID_HOST -> "INVALID_HOST"
            NO_SUPPORTED_WIFI_CHANNELS -> "NO_SUPPORTED_WIFI_CHANNELS"
            INSTRUCT_USER_TO_CHECK_THE_PHONE -> "INSTRUCT_USER_TO_CHECK_THE_PHONE"
            PHONE_WIFI_DISABLED -> "PHONE_WIFI_DISABLED"
            WIFI_NETWORK_UNAVAILABLE -> "WIFI_NETWORK_UNAVAILABLE"
            else -> "unknown($value)"
        }
    }

    /**
     * Access point security, as the wire numbers it.
     *
     * Only the two the head unit can actually create are used: [WPA2_PERSONAL] for a 2.4/5 GHz PSK
     * access point, [WPA2_WPA3_PERSONAL] when the platform reports WPA3 transition mode.
     */
    object SecurityMode {
        const val OPEN = 1
        const val WPA_PERSONAL = 4
        const val WPA2_PERSONAL = 8
        const val WPA_WPA2_PERSONAL = 12
        const val WPA3_PERSONAL = 32
        const val WPA2_WPA3_PERSONAL = 40
    }

    /** `access_point_type`: the phone may remember a STATIC network and forget a DYNAMIC one. */
    object AccessPointType {
        const val STATIC = 0
        const val DYNAMIC = 1
    }

    /** `supported_wifi_channel_type` in the version request. */
    object WifiChannelType {
        const val FIVE_GHZ = 0
        const val TWO_POINT_FOUR_GHZ = 1
        const val DUAL = 2
        const val NONE = 3
    }

    /** `reason` in [encodeStartRequest], only ever logged by the phone. */
    object StartReason {
        const val USER_REQUEST = 0
        const val AUTO_LAUNCH = 1
        const val AUTOMATIC_RESTART = 2
    }

    /** One WPP message: `[u16 payload length][u16 message id][protobuf payload]`. */
    class Frame(val id: Int, val payload: ByteArray) {
        override fun toString() = "${MessageId.name(id)} (${payload.size} B)"
    }

    /**
     * Reads one frame, or returns null on a clean end of stream between frames.
     *
     * Throws when the stream ends inside a frame: a half frame means the link died mid-message, and
     * pretending it was a clean close would hide a failed bootstrap behind a timeout.
     */
    fun readFrame(input: InputStream): Frame? {
        val hi = input.read()
        if (hi < 0) return null
        val lo = input.read()
        if (lo < 0) throw EOFException("WPP stream ended inside a header")
        val length = (hi shl 8) or lo
        val idHi = input.read()
        if (idHi < 0) throw EOFException("WPP stream ended inside a header")
        val idLo = input.read()
        if (idLo < 0) throw EOFException("WPP stream ended inside a header")
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(payload, offset, length - offset)
            if (read < 0) throw EOFException("WPP frame truncated: ${length - offset} of $length bytes missing")
            offset += read
        }
        return Frame((idHi shl 8) or idLo, payload)
    }

    /** Builds the bytes of one frame, for transports that must send a frame in a single write. */
    fun encodeFrame(id: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 0xffff) { "WPP payload too large: ${payload.size}" }
        val out = ByteArray(4 + payload.size)
        out[0] = (payload.size shr 8).toByte()
        out[1] = payload.size.toByte()
        out[2] = (id shr 8).toByte()
        out[3] = id.toByte()
        payload.copyInto(out, 4)
        return out
    }

    fun writeFrame(output: OutputStream, id: Int, payload: ByteArray) {
        output.write(encodeFrame(id, payload))
        output.flush()
    }

    // --- head unit to phone ---

    /**
     * `WifiVersionRequest { major = 1; minor = 2; supported_wifi_channel_type = 3; car_info = 5; }`
     *
     * Optional, but strict OEM head units gate the rest of the exchange on a well-formed answer, so
     * sending it is the cheaper choice. [carInfo] fields are shown in the phone's connection log.
     */
    fun encodeVersionRequest(
        major: Int = 1,
        minor: Int = 0,
        supportedWifiChannelType: Int = WifiChannelType.DUAL,
        carInfo: WppCarInfo? = null,
    ): ByteArray {
        val writer = ProtoWire.Writer()
        writer.int32(FIELD_VERSION_MAJOR, major)
        writer.int32(FIELD_VERSION_MINOR, minor)
        writer.int32(FIELD_VERSION_CHANNEL_TYPE, supportedWifiChannelType)
        carInfo?.let { writer.message(FIELD_VERSION_CAR_INFO, it.encode()) }
        return writer.build()
    }

    /** `WifiStartRequest { ip_address = 1; port = 2; reason = 3; }` — where to find the AAP server. */
    fun encodeStartRequest(
        ipAddress: String,
        port: Int,
        reason: Int = StartReason.USER_REQUEST,
    ): ByteArray = ProtoWire.Writer()
        .string(FIELD_START_IP, ipAddress)
        .uint32(FIELD_START_PORT, port)
        .int32(FIELD_START_REASON, reason)
        .build()

    /**
     * `WifiInfoResponse { ssid = 1; password = 2; bssid = 3; security_mode = 4; access_point_type = 5; }`
     *
     * [bssid] is left off when empty rather than sent as `""`: some phones parse an empty string into
     * a zero BSSID and then refuse to join the network they were just told about.
     */
    fun encodeInfoResponse(
        ssid: String,
        password: String,
        bssid: String? = null,
        securityMode: Int = SecurityMode.WPA2_PERSONAL,
        accessPointType: Int = AccessPointType.DYNAMIC,
    ): ByteArray {
        val writer = ProtoWire.Writer()
            .string(FIELD_INFO_SSID, ssid)
            .string(FIELD_INFO_PASSWORD, password)
        bssid?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_BSSID, it) }
        writer.int32(FIELD_INFO_SECURITY_MODE, securityMode)
        writer.int32(FIELD_INFO_ACCESS_POINT_TYPE, accessPointType)
        return writer.build()
    }

    /**
     * Answers a ping by echoing its payload.
     *
     * The payload is a timestamp the phone only uses to measure the round trip, so echoing it is both
     * correct and free of any assumption about the message's shape.
     */
    fun pingResponseFor(request: Frame): ByteArray = request.payload

    // --- phone to head unit ---

    /** `WifiConnectionStatus { status = 1; error_message = 2; }` — whether the phone joined. */
    fun decodeConnectionStatus(payload: ByteArray): AawConnectionStatus {
        var status = Status.WIFI_NETWORK_UNAVAILABLE
        var message: String? = null
        val reader = ProtoWire.Reader(payload)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                FIELD_CONNECTION_STATUS -> status = field.asInt()
                FIELD_CONNECTION_ERROR -> message = field.stringValue().takeIf { it.isNotEmpty() }
            }
        }
        return AawConnectionStatus(status, message)
    }

    /** `WifiStartResponse { ip_address = 1; port = 2; status = 3; }`. */
    fun decodeStartResponse(payload: ByteArray): AawStartResponse {
        var ip: String? = null
        var port = 0
        var status = Status.WIFI_NETWORK_UNAVAILABLE
        val reader = ProtoWire.Reader(payload)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                FIELD_START_RESPONSE_IP -> ip = field.stringValue().takeIf { it.isNotEmpty() }
                FIELD_START_RESPONSE_PORT -> port = field.asInt()
                FIELD_START_RESPONSE_STATUS -> status = field.asInt()
            }
        }
        return AawStartResponse(ip, port, status)
    }

    /** `WifiVersionResponse { major = 1; minor = 2; device_serial = 3; status = 4; selected = 5; }`. */
    fun decodeVersionResponse(payload: ByteArray): AawVersionResponse {
        var major = 0
        var minor = 0
        var status = Status.WIFI_NETWORK_UNAVAILABLE
        var selectedChannelType: Int? = null
        val reader = ProtoWire.Reader(payload)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                FIELD_VERSION_MAJOR -> major = field.asInt()
                FIELD_VERSION_MINOR -> minor = field.asInt()
                FIELD_VERSION_RESPONSE_STATUS -> status = field.asInt()
                FIELD_VERSION_RESPONSE_SELECTED -> selectedChannelType = field.asInt()
            }
        }
        return AawVersionResponse(major, minor, status, selectedChannelType)
    }

    private const val FIELD_VERSION_MAJOR = 1
    private const val FIELD_VERSION_MINOR = 2
    private const val FIELD_VERSION_CHANNEL_TYPE = 3
    private const val FIELD_VERSION_CAR_INFO = 5
    private const val FIELD_VERSION_RESPONSE_STATUS = 4
    private const val FIELD_VERSION_RESPONSE_SELECTED = 5

    private const val FIELD_START_IP = 1
    private const val FIELD_START_PORT = 2
    private const val FIELD_START_REASON = 3

    private const val FIELD_INFO_SSID = 1
    private const val FIELD_INFO_PASSWORD = 2
    private const val FIELD_INFO_BSSID = 3
    private const val FIELD_INFO_SECURITY_MODE = 4
    private const val FIELD_INFO_ACCESS_POINT_TYPE = 5

    private const val FIELD_CONNECTION_STATUS = 1
    private const val FIELD_CONNECTION_ERROR = 2

    private const val FIELD_START_RESPONSE_IP = 1
    private const val FIELD_START_RESPONSE_PORT = 2
    private const val FIELD_START_RESPONSE_STATUS = 3
}

/**
 * The car description the phone shows in its connection log (`WppCarInfo`).
 *
 * Field numbers 5-8 are open-headunit's reconstruction rather than a published spec, which is why
 * every field is optional there and why sending them is safe: a phone that does not know them
 * ignores them, and nothing in the exchange depends on them.
 */
data class WppCarInfo(
    val make: String? = null,
    val model: String? = null,
    val modelYear: String? = null,
    val vehicleId: String? = null,
    val headUnitMake: String? = null,
    val headUnitModel: String? = null,
    val headUnitSoftwareBuild: String? = null,
    val headUnitSoftwareVersion: String? = null,
) {
    fun encode(): ByteArray {
        val writer = ProtoWire.Writer()
        make?.let { writer.string(1, it) }
        model?.let { writer.string(2, it) }
        modelYear?.let { writer.string(3, it) }
        vehicleId?.let { writer.string(4, it) }
        headUnitMake?.let { writer.string(5, it) }
        headUnitModel?.let { writer.string(6, it) }
        headUnitSoftwareBuild?.let { writer.string(7, it) }
        headUnitSoftwareVersion?.let { writer.string(8, it) }
        return writer.build()
    }
}

/** What the phone reported about joining the access point. */
data class AawConnectionStatus(val status: Int, val message: String?) {
    val joined: Boolean get() = status == AawWpp.Status.SUCCESS
    override fun toString() = "status=${AawWpp.Status.name(status)}" + (message?.let { " \"$it\"" } ?: "")
}

/** What the phone answered to the start request. */
data class AawStartResponse(val ipAddress: String?, val port: Int, val status: Int) {
    val accepted: Boolean get() = status == AawWpp.Status.SUCCESS
    override fun toString() = "status=${AawWpp.Status.name(status)} from=${ipAddress ?: "?"}:$port"
}

/** What the phone answered to the version request. */
data class AawVersionResponse(
    val major: Int,
    val minor: Int,
    val status: Int,
    val selectedChannelType: Int?,
) {
    override fun toString() =
        "WPP $major.$minor status=${AawWpp.Status.name(status)}" +
            (selectedChannelType?.let { " channelType=$it" } ?: "")
}
