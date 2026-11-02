package com.shilapi.xcertplay.aap

/**
 * Android Open Accessory (AOA) handshake, from the head unit's side.
 *
 * A head unit cannot open a projection session with a plain Android phone: the phone exposes
 * MTP/PTP and nothing that speaks AAP. The way in is AOA, which every Android build since 2.3.4
 * implements in the platform:
 *
 * 1. the host reads the phone's AOA protocol version (control request 51),
 * 2. the host uploads up to six identity strings (request 52),
 * 3. the host sends START (request 53),
 * 4. the phone re-enumerates as a USB *accessory*, with vendor id 0x18D1,
 * 5. the host opens that accessory and finds one interface with two bulk endpoints,
 * 6. from there the register reads and writes carry AAP frames.
 *
 * This object holds the parts that are pure data so they can be tested on the JVM: the constants,
 * the request sequence and the version parsing. The USB transfers themselves live in the Android
 * module.
 */
object Aoa {

    /** Vendor id a phone reports after entering accessory mode. */
    const val ACCESSORY_VID = 0x18D1

    /** Accessory only. */
    const val ACCESSORY_PID = 0x2D00

    /** Accessory with ADB still available (what a phone shows with USB debugging on). */
    const val ACCESSORY_PID_WITH_ADB = 0x2D01

    /** Accessory with audio support (AOA 2.0). */
    const val ACCESSORY_PID_WITH_AUDIO = 0x2D02

    const val ACCESSORY_PID_WITH_AUDIO_AND_ADB = 0x2D03

    const val PROTOCOL_VERSION_1 = 1
    const val PROTOCOL_VERSION_2 = 2

    // Control requests, from the AOA specification.
    const val REQUEST_GET_PROTOCOL = 51
    const val REQUEST_SEND_STRING = 52
    const val REQUEST_START = 53
    const val REQUEST_REGISTER_HID = 54
    const val REQUEST_UNREGISTER_HID = 55
    const val REQUEST_SET_HID_REPORT = 56
    const val REQUEST_SEND_AUDIO = 58
    const val REQUEST_GET_AUDIO = 59
    const val REQUEST_ACCESSORY_REGISTER_HID = 57

    /** bmRequestType values: vendor, device, host-to-device and device-to-host. */
    const val TYPE_OUT = 0x40
    const val TYPE_IN = 0xC0

    // String indices for SEND_STRING.
    const val STRING_MANUFACTURER = 0
    const val STRING_MODEL = 1
    const val STRING_DESCRIPTION = 2
    const val STRING_VERSION = 3
    const val STRING_URI = 4
    const val STRING_SERIAL = 5

    /** VID/PID a phone presents while still in normal (MTP) mode — these are not accessories. */
    fun isAccessory(vendorId: Int, productId: Int): Boolean =
        vendorId == ACCESSORY_VID && productId in ACCESSORY_PID..ACCESSORY_PID_WITH_AUDIO_AND_ADB

    /**
     * Reads the 16-bit little-endian version from the GET_PROTOCOL reply.
     *
     * A short reply is a failure, not a zero: some cables cut the transfer off and returning 0
     * would look like "this phone does not support AOA" instead of "the cable is bad".
     */
    fun protocolVersion(reply: ByteArray?): Int {
        if (reply == null || reply.size < 2) return 0
        return (reply[0].toInt() and 0xff) or ((reply[1].toInt() and 0xff) shl 8)
    }

    /** AOA 1 is enough to start a session; 2 adds audio and HID. */
    fun canStart(version: Int): Boolean = version >= PROTOCOL_VERSION_1

    fun supportsAudio(version: Int): Boolean = version >= PROTOCOL_VERSION_2
}

/** The strings a phone shows and stores for an accessory. */
data class AoaIdentity(
    val manufacturer: String,
    val model: String,
    val description: String,
    val version: String,
    val uri: String = "",
    val serial: String = "",
) {
    companion object {
        /**
         * What DiPlay reports itself as.
         *
         * The model is what Android shows in the "connected to" notification and what the phone
         * keys the accessory on, so it must stay stable across releases: a new value makes the
         * phone treat it as a different accessory.
         */
        fun diplay(): AoaIdentity = AoaIdentity(
            manufacturer = "DiPlay",
            model = "DiPlay Head Unit",
            description = "Android Auto projection receiver",
            version = "1.0",
        )
    }
}

/** One USB control transfer, expressed as data so the handshake can be checked without hardware. */
data class AoaRequest(
    val requestType: Int,
    val request: Int,
    val value: Int,
    val index: Int,
    /** Bytes to send, or the expected reply length when [requestType] is [Aoa.TYPE_IN]. */
    val payload: ByteArray?,
    val replyLength: Int = 0,
) {
    val isInbound: Boolean get() = requestType == Aoa.TYPE_IN

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AoaRequest) return false
        return requestType == other.requestType && request == other.request &&
            value == other.value && index == other.index && replyLength == other.replyLength &&
            (payload?.contentEquals(other.payload ?: ByteArray(0)) ?: (other.payload == null))
    }

    override fun hashCode(): Int {
        var result = requestType
        result = 31 * result + request
        result = 31 * result + value
        result = 31 * result + index
        result = 31 * result + replyLength
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Builds the control transfers that put a phone into accessory mode.
 *
 * Kept as a pure function on purpose: getting the order wrong (START before the strings, or
 * reading the version after START) makes the phone enumerate as an accessory with no identity,
 * and that failure is indistinguishable from a broken cable when you only have logs.
 */
object AoaHandshake {

    /** SEND_STRING carries a UTF-8 string with a terminating NUL. */
    fun stringPayload(value: String): ByteArray {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        return utf8 + byteArrayOf(0)
    }

    fun getProtocol(): AoaRequest = AoaRequest(
        requestType = Aoa.TYPE_IN,
        request = Aoa.REQUEST_GET_PROTOCOL,
        value = 0,
        index = 0,
        payload = null,
        replyLength = 2,
    )

    fun sendString(index: Int, value: String): AoaRequest = AoaRequest(
        requestType = Aoa.TYPE_OUT,
        request = Aoa.REQUEST_SEND_STRING,
        value = 0,
        index = index,
        payload = stringPayload(value),
    )

    fun start(): AoaRequest = AoaRequest(
        requestType = Aoa.TYPE_OUT,
        request = Aoa.REQUEST_START,
        value = 0,
        index = 0,
        payload = null,
    )

    /**
     * The full sequence for a phone that reported [protocolVersion].
     *
     * Returns an empty list when the version is too old: sending SEND_STRING to a phone without
     * AOA support does nothing useful and leaves the endpoint claimed for no reason.
     */
    fun plan(protocolVersion: Int, identity: AoaIdentity = AoaIdentity.diplay()): List<AoaRequest> {
        if (!Aoa.canStart(protocolVersion)) return emptyList()
        val out = ArrayList<AoaRequest>(8)
        out += sendString(Aoa.STRING_MANUFACTURER, identity.manufacturer)
        out += sendString(Aoa.STRING_MODEL, identity.model)
        out += sendString(Aoa.STRING_DESCRIPTION, identity.description)
        out += sendString(Aoa.STRING_VERSION, identity.version)
        // An empty URI and serial are legal, but a phone that gets an empty URI may show the
        // accessory without a launch intent, so only send them when they are set.
        if (identity.uri.isNotEmpty()) out += sendString(Aoa.STRING_URI, identity.uri)
        if (identity.serial.isNotEmpty()) out += sendString(Aoa.STRING_SERIAL, identity.serial)
        out += start()
        return out
    }
}
