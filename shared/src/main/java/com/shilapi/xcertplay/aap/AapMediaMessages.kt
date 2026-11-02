package com.shilapi.xcertplay.aap

import com.shilapi.xcertplay.proto.ProtoWire

/**
 * Message bodies on a media sink channel, from the head unit's side.
 *
 * The exchange a video session goes through, in order:
 *
 * ```
 * phone -> HU   SETUP    Setup{type}                  which codec the phone will send
 * HU -> phone   CONFIG   Config{READY, max_unacked, configuration_indices}
 * phone -> HU   START    Start{session_id, configuration_index}
 * phone -> HU   CODEC_CONFIG   raw SPS/PPS, no protobuf
 * phone -> HU   DATA     raw 8-byte timestamp + frame, no protobuf
 * HU -> phone   ACK      Ack{session_id, ack}         one per DATA, up to max_unacked in flight
 * ```
 *
 * `SETUP` and `CONFIG` share message id 32768 and `DATA` has no protobuf at all, so the direction
 * decides which of these a body is. That is why they live together here rather than in one
 * "message id to parser" table.
 */
object AapMediaMessages {

    /** `Config.Status`: READY is the only one a head unit sends. */
    const val CONFIG_STATUS_WAIT = 1
    const val CONFIG_STATUS_READY = 2

    /**
     * `Config { Status status = 1; uint32 max_unacked = 2; repeated uint32 configuration_indices = 3 }`.
     *
     * [maxUnacked] is the flow-control window: how many DATA messages the phone may have in flight
     * before an ack. Too small throttles video on a slow link; too large lets a fast encoder outrun
     * a decoder that is behind, which shows up as growing latency rather than dropped frames. Four
     * frames is about the largest window that still bounds latency to something a driver tolerates.
     */
    fun encodeConfig(
        maxUnacked: Int = 4,
        configurationIndices: List<Int> = listOf(0),
        status: Int = CONFIG_STATUS_READY,
    ): ByteArray {
        val writer = ProtoWire.Writer().int32(1, status)
        if (maxUnacked > 0) writer.uint32(2, maxUnacked)
        for (index in configurationIndices) writer.uint32(3, index)
        return writer.build()
    }

    /**
     * `MicrophoneRequest { bool open = 1; ... }`: the phone asks the car to start or stop recording.
     *
     * The rest of the message (echo cancellation, noise suppression, the max unacked window) is
     * the phone's preference, not a requirement; a head unit that ignores it still works.
     */
    fun decodeMicrophoneRequestOpen(bytes: ByteArray): Boolean {
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: return false
            if (field.number == 1) return field.intValue != 0L
        }
    }

    /** `MicrophoneResponse { int32 status = 1; int32 session_id = 2 }`. */
    fun encodeMicrophoneResponse(sessionId: Int, status: Int = 0): ByteArray =
        ProtoWire.Writer().int32(1, status).int32(2, sessionId).build()

    /** `Ack { int32 session_id = 1; uint32 ack = 2 }`. */
    fun encodeAck(sessionId: Int, ack: Int = 1): ByteArray =
        ProtoWire.Writer().int32(1, sessionId).uint32(2, ack).build()

    /**
     * The head unit's answer to SETUP: how many frames may be in flight, and which of the
     * configurations the phone offered it picked. A missing `max_unacked` means one.
     */
    class Config(val maxUnacked: Int, val configurationIndices: List<Int>) {
        override fun toString() = "maxUnacked=$maxUnacked indices=$configurationIndices"
    }

    fun decodeConfig(bytes: ByteArray): Config {
        var maxUnacked = 1
        val indices = ArrayList<Int>()
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                2 -> maxUnacked = field.asInt().coerceAtLeast(1)
                3 -> indices += field.asInt()
            }
        }
        return Config(maxUnacked, indices)
    }

    /** How many messages the head unit acknowledged; a missing count means one. */
    fun decodeAck(bytes: ByteArray): Int {
        var ack = 1
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            if (field.number == 2) ack = field.asInt().coerceAtLeast(1)
        }
        return ack
    }

    /** `Setup { MediaCodecType type = 1 }`, the codec the phone intends to send. */
    fun encodeSetup(codec: Int): ByteArray = ProtoWire.Writer().int32(1, codec).build()

    /** `Setup { MediaCodecType type = 1 }`, the codec the phone intends to send. */
    fun decodeSetupCodec(bytes: ByteArray): Int {
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: return 0
            if (field.number == 1) return field.asInt()
        }
    }

    /** `Start { int32 session_id = 1; uint32 configuration_index = 2 }`. */
    class Start(val sessionId: Int, val configurationIndex: Int) {
        override fun toString() = "session=$sessionId config=$configurationIndex"
    }

    fun encodeStart(sessionId: Int, configurationIndex: Int): ByteArray =
        ProtoWire.Writer().int32(1, sessionId).uint32(2, configurationIndex).build()

    fun decodeStart(bytes: ByteArray): Start {
        var session = 0
        var index = 0
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> session = field.asInt()
                2 -> index = field.asInt()
            }
        }
        return Start(session, index)
    }

    /**
     * A DATA body: an 8-byte big-endian timestamp in microseconds, then the frame.
     *
     * The timestamp is the phone's presentation time, not a wall clock — the decoder uses it to
     * pace output, so re-stamping with local time would make playback drift against audio.
     */
    fun dataTimestamp(body: ByteArray, offset: Int = 0): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (body[offset + i].toLong() and 0xff)
        return value
    }

    /** Bytes of the timestamp that precedes a DATA payload. */
    const val DATA_HEADER = 8

    /** `VideoFocusMode`: PROJECTED means the car is showing the phone's screen. */
    object VideoFocusMode {
        const val PROJECTED = 1
        const val NATIVE = 2
        const val NATIVE_TRANSIENT = 3
        /** Projected, but the car keeps input focus for itself (e.g. its own volume knob). */
        const val PROJECTED_NO_INPUT_FOCUS = 4
    }

    /** `VideoFocusReason`. */
    object VideoFocusReason {
        const val UNKNOWN = 0
        const val PHONE_SCREEN_OFF = 1
        const val LAUNCH_NATIVE = 2
    }

    /**
     * `VideoFocusRequestNotification { int32 disp_channel_id = 1 (deprecated); VideoFocusMode mode = 2; VideoFocusReason reason = 3 }`.
     *
     * The phone sends one of these after `START` to ask to be displayed; the head unit answers with
     * a [encodeVideoFocusNotification]. A head unit sends the request form when it wants to hand
     * the screen back to its own UI.
     */
    fun encodeVideoFocusRequest(
        mode: Int,
        reason: Int = VideoFocusReason.UNKNOWN,
    ): ByteArray = ProtoWire.Writer().int32(2, mode).int32(3, reason).build()

    fun decodeVideoFocusRequestMode(bytes: ByteArray): Int {
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: return 0
            if (field.number == 2) return field.asInt()
        }
    }

    /** `VideoFocusNotification { VideoFocusMode focus = 1; bool unsolicited = 2 }`. */
    fun encodeVideoFocusNotification(
        focus: Int = VideoFocusMode.PROJECTED,
        unsolicited: Boolean = false,
    ): ByteArray = ProtoWire.Writer().int32(1, focus).bool(2, unsolicited).build()

    /** Parsed `VideoFocusNotification`. */
    class VideoFocus(val focus: Int, val unsolicited: Boolean) {
        val projected: Boolean
            get() = focus == VideoFocusMode.PROJECTED || focus == VideoFocusMode.PROJECTED_NO_INPUT_FOCUS

        override fun toString() = "video focus=$focus unsolicited=$unsolicited"
    }

    fun decodeVideoFocusNotification(bytes: ByteArray): VideoFocus {
        var focus = 0
        var unsolicited = false
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> focus = field.asInt()
                2 -> unsolicited = field.intValue != 0L
            }
        }
        return VideoFocus(focus, unsolicited)
    }
}
