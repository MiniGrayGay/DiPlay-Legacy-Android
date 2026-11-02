package com.shilapi.xcertplay.aap

import com.shilapi.xcertplay.proto.ProtoWire

/**
 * The control channel's message bodies.
 *
 * Two of these are not protobuf, and that is not an accident of this implementation:
 *
 * - **Version** is six raw bytes — major, minor, status, each a big-endian 16-bit integer. A
 *   protobuf reader would see field 0 and give up, which is why every port carries a comment about
 *   it somewhere.
 * - **`AuthComplete`** carries an `AuthResponse`, and that *is* protobuf.
 *
 * The rest follow their `.proto` definitions; the comment on each one names the fields it encodes
 * so a mismatch against the phone's definition is visible without opening the schema.
 */
object AapControlMessages {

    /** Protocol version this head unit asks for. The phone answers what it will actually speak. */
    const val PROTOCOL_MAJOR = 1
    const val PROTOCOL_MINOR = 7

    // ------------------------------------------------------------------ version

    /**
     * `VersionRequest`: major (2), minor (2), status (2), all big endian.
     *
     * The status field is meaningless on a request but the length is not: some phone builds read a
     * fixed six bytes and desynchronise the stream on a four-byte request.
     */
    fun encodeVersionRequest(
        major: Int = PROTOCOL_MAJOR,
        minor: Int = PROTOCOL_MINOR,
    ): ByteArray = ByteArray(6).also {
        it[0] = (major shr 8).toByte()
        it[1] = major.toByte()
        it[2] = (minor shr 8).toByte()
        it[3] = minor.toByte()
        // it[4..5] stay 0: STATUS_SUCCESS
    }

    /** What the phone answered. */
    class Version(val major: Int, val minor: Int, val status: Int) {
        val supported: Boolean get() = status == AapStatus.SUCCESS

        override fun toString() = "$major.$minor${if (supported) "" else " (status $status)"}"
    }

    fun decodeVersionResponse(bytes: ByteArray): Version {
        // A phone that answers with fewer than four bytes is not speaking this protocol; reporting
        // 0.0 with a status of "success" would hide that.
        require(bytes.size >= 4) { "version response of ${bytes.size} bytes" }
        fun short(offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        val status = if (bytes.size >= 6) short(4).toShort().toInt() else AapStatus.SUCCESS
        return Version(short(0), short(2), status)
    }

    // ------------------------------------------------------------------ authentication

    /** `AuthResponse { int32 status = 1 }`. Zero means the phone accepted our certificate. */
    fun encodeAuthResponse(status: Int = AapStatus.SUCCESS): ByteArray =
        ProtoWire.Writer().int32(1, status).build()

    fun decodeAuthStatus(bytes: ByteArray): Int = firstVarint(bytes, field = 1, default = 0)

    // ------------------------------------------------------------------ keep alive

    /** `PingRequest { int64 timestamp = 1; }`, in microseconds. */
    fun encodePingRequest(timestampMicros: Long): ByteArray =
        ProtoWire.Writer().int64(1, timestampMicros).build()

    fun decodePingTimestamp(bytes: ByteArray): Long {
        var reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: return 0
            if (field.number == 1) return field.intValue
        }
    }

    /** `PingResponse { int64 timestamp = 1 }`: the request's timestamp echoed back. */
    fun encodePingResponse(timestampMicros: Long): ByteArray =
        ProtoWire.Writer().int64(1, timestampMicros).build()

    // ------------------------------------------------------------------ channels

    /** `ChannelOpenRequest { int32 priority = 1; int32 service_id = 2 }`. */
    class ChannelOpenRequest(val priority: Int, val serviceId: Int) {
        override fun toString() = "service=$serviceId priority=$priority"
    }

    fun decodeChannelOpenRequest(bytes: ByteArray): ChannelOpenRequest {
        var priority = 0
        var serviceId = 0
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> priority = field.asInt()
                2 -> serviceId = field.asInt()
            }
        }
        return ChannelOpenRequest(priority, serviceId)
    }

    /** `ChannelOpenResponse { MessageStatus status = 1 }`. */
    fun encodeChannelOpenResponse(status: Int = AapStatus.SUCCESS): ByteArray =
        ProtoWire.Writer().int32(1, status).build()

    // ------------------------------------------------------------------ session end

    /** `ByeByeReason`: why the head unit is ending the session. */
    object ByeByeReason {
        const val USER_SELECTION = 1
        const val DEVICE_SWITCH = 2
        const val NOT_SUPPORTED = 3
        const val NOT_CURRENTLY_SUPPORTED = 4
        const val PROBE_SUPPORTED = 5
    }

    /** `ByeByeRequest { ByeByeReason reason = 1 }`. */
    fun encodeByeByeRequest(reason: Int = ByeByeReason.USER_SELECTION): ByteArray =
        ProtoWire.Writer().int32(1, reason).build()

    fun decodeByeByeReason(bytes: ByteArray): Int = firstVarint(bytes, 1, ByeByeReason.USER_SELECTION)

    // ------------------------------------------------------------------ discovery

    /**
     * What the phone says about itself in `ServiceDiscoveryRequest`.
     *
     * Only the two strings are read. The icons are large and only used for a "which phone is this"
     * row the head unit draws itself, and the instance id identifies the phone across sessions —
     * both are ignored for now rather than half-supported.
     */
    class ServiceDiscoveryRequest(val deviceName: String, val label: String) {
        /** What to show the driver: the label if the phone sent one, else the model. */
        val display: String get() = label.ifEmpty { deviceName.ifEmpty { "Android phone" } }

        override fun toString() = "name=\"$deviceName\" label=\"$label\""
    }

    fun decodeServiceDiscoveryRequest(bytes: ByteArray): ServiceDiscoveryRequest {
        var name = ""
        var label = ""
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                // label_text is field 4 and device_name field 5, *after* the three icons at 1..3.
                // Reading them as 1 and 3 yields an empty name and no error, which shows up much
                // later as a blank "connected to" line.
                4 -> label = field.stringValue()
                5 -> name = field.stringValue()
            }
        }
        return ServiceDiscoveryRequest(name, label)
    }

    /** `AudioFocusNotification { AudioFocusStateType focus_state = 1; bool unsolicited = 2 }`. */
    class AudioFocus(val state: Int, val unsolicited: Boolean) {
        val hasFocus: Boolean get() = state == GAIN || state == GAIN_TRANSIENT

        override fun toString() = "audio focus state=$state unsolicited=$unsolicited"

        companion object {
            const val GAIN = 1
            const val GAIN_TRANSIENT = 2
            const val LOSS = 3
            const val LOSS_TRANSIENT = 4
        }
    }

    fun decodeAudioFocus(bytes: ByteArray): AudioFocus {
        var state = AudioFocus.LOSS
        var unsolicited = false
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> state = field.asInt()
                2 -> unsolicited = field.intValue != 0L
            }
        }
        return AudioFocus(state, unsolicited)
    }

    /** Reads one varint field, tolerating unknown fields before it. */
    private fun firstVarint(bytes: ByteArray, field: Int, default: Int): Int {
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val next = reader.next() ?: return default
            if (next.number == field) return next.asInt()
        }
    }
}
