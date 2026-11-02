package com.shilapi.xcertplay.aap

import com.shilapi.xcertplay.proto.ProtoWire

/**
 * The fixed resolutions Android Auto can name in a video configuration.
 *
 * A head unit cannot ask for an arbitrary size: the phone matches what it will encode against this
 * enum, so "our screen is 1024x600" is not expressible. What a 1024x600 panel does is offer
 * 1280x720 and letterbox, which is what every aftermarket unit does.
 */
enum class AapResolution(val id: Int, val width: Int, val height: Int) {
    P800x480(1, 800, 480),
    P1280x720(2, 1280, 720),
    P1920x1080(3, 1920, 1080),
    P2560x1440(4, 2560, 1440),
    P3840x2160(5, 3840, 2160),
    P720x1280(6, 720, 1280),
    P1080x1920(7, 1080, 1920),
    P1440x2560(8, 1440, 2560),
    P2160x3840(9, 2160, 3840);

    companion object {
        fun of(id: Int): AapResolution? = entries.firstOrNull { it.id == id }
    }
}

/** One `VideoConfiguration`. */
data class AapVideoConfig(
    val resolution: AapResolution,
    val frameRate: Int = 30,
    val density: Int = 160,
    /** Margins in pixels the phone keeps clear for the car's own chrome. */
    val marginWidth: Int = 0,
    val marginHeight: Int = 0,
) {
    init {
        // The enum only has two values; anything else silently encodes as 30.
        require(frameRate == 30 || frameRate == 60) { "frame rate must be 30 or 60" }
    }

    internal fun encode(): ByteArray = ProtoWire.Writer()
        .int32(1, resolution.id)
        .int32(2, if (frameRate == 60) FRAME_RATE_60 else FRAME_RATE_30)
        .uint32(3, marginWidth)
        .uint32(4, marginHeight)
        .uint32(5, density)
        .int32(10, AapCodec.VIDEO_H264_BP)
        .build()

    private companion object {
        const val FRAME_RATE_60 = 1
        const val FRAME_RATE_30 = 2
    }
}

/** `AudioStreamType`: which of the phone's outputs this sink is for. */
object AapAudioStreamType {
    const val GUIDANCE = 1
    const val SYSTEM = 2
    const val MEDIA = 3
    const val TELEPHONY = 4
}

/** One audio sink. The phone sends PCM; it does not encode audio for the car. */
data class AapAudioStream(
    val type: Int,
    val sampleRate: Int = 48_000,
    val bitDepth: Int = 16,
    val channels: Int = 2,
) {
    internal fun encode(): ByteArray = ProtoWire.Writer()
        .uint32(1, sampleRate)
        .uint32(2, bitDepth)
        .uint32(3, channels)
        .build()
}

/** The touch panel the car exposes to the phone, in the phone's video coordinate space. */
data class AapTouchScreen(
    val width: Int,
    val height: Int,
    val type: Int = CAPACITIVE,
) {
    internal fun encode(): ByteArray = ProtoWire.Writer()
        .int32(1, width)
        .int32(2, height)
        .int32(3, type)
        .bool(4, false)
        .build()

    companion object {
        const val CAPACITIVE = 1
        const val RESISTIVE = 2
    }
}

/** Codec ids as they appear in `MediaCodecType`. */
object AapCodec {
    const val AUDIO_PCM = 1
    const val AUDIO_AAC_LC = 2
    const val VIDEO_H264_BP = 3
    const val AUDIO_AAC_LC_ADTS = 4
    const val VIDEO_VP9 = 5
    const val VIDEO_AV1 = 6
    const val VIDEO_H265 = 7
}

/** Sensor ids as they appear in `SensorType`. */
object AapSensorType {
    const val LOCATION = 1
    const val COMPASS = 2
    const val SPEED = 3
    const val RPM = 4
    const val ODOMETER = 5
    const val FUEL = 6
    const val PARKING_BRAKE = 7
    const val GEAR = 8
    const val NIGHT_MODE = 10
    const val DRIVING_STATUS = 13
}

/**
 * What the car says about itself.
 *
 * A phone shows the car in "connected to …" and some versions pick a layout from the year, so an
 * empty answer here is not fatal but does make the projection look like an anonymous adapter. The
 * strings are the only place a head unit gets to name its own hardware.
 */
data class AapHeadUnitInfo(
    val make: String = "DiPlay",
    val model: String = "Head Unit",
    val year: String = "",
    val headUnitMake: String = "DiPlay",
    val headUnitModel: String = "DiPlay",
    val softwareVersion: String = "",
) {
    internal fun encode(): ByteArray = ProtoWire.Writer()
        .string(1, make)
        .string(2, model)
        .string(3, year)
        .string(5, headUnitMake)
        .string(6, headUnitModel)
        .string(8, softwareVersion)
        .build()
}

/**
 * The car's microphone, offered to the phone's voice assistant.
 *
 * A head unit without this still projects, it just cannot listen: the assistant button on the
 * phone's own screen has no audio to record. 16 kHz mono is what Android Auto asks for.
 */
data class AapMicrophoneSpec(
    val sampleRate: Int = 16_000,
    val bitDepth: Int = 16,
    val channels: Int = 1,
) {
    internal fun encodeConfig(): ByteArray = ProtoWire.Writer()
        .uint32(1, sampleRate)
        .uint32(2, bitDepth)
        .uint32(3, channels)
        .build()
}

/**
 * What this head unit offers the phone, and the channel numbers it will answer on.
 *
 * The head unit picks the channel ids; the phone uses them verbatim for everything that follows.
 * Numbering is deterministic and derived from what is enabled, so a log line "channel 3" always
 * means the same thing for a given configuration:
 *
 * ```
 * 1              video sink
 * 2 .. 1+n       audio sinks, in the order given
 * next           microphone     (only if one is offered)
 * next           input source   (only if a touch screen is present)
 * next           sensor source  (only if sensors are listed)
 * next           navigation     (only if enabled)
 * next           now playing    (only if enabled)
 * ```
 *
 * Leaving a service out is meaningful: the phone only opens what it finds here, so a build without
 * audio simply never gets an audio channel instead of opening one and streaming into nothing.
 */
class AapServiceSet(
    val displayName: String,
    val video: List<AapVideoConfig>,
    val audio: List<AapAudioStream> = emptyList(),
    val touchscreen: AapTouchScreen? = null,
    val keycodes: List<Int> = emptyList(),
    val sensors: List<Int> = emptyList(),
    val microphone: AapMicrophoneSpec? = null,
    val headUnit: AapHeadUnitInfo? = null,
    /** Turn-by-turn for the instrument cluster; the phone sends it, the car only has to listen. */
    val navigationStatus: Boolean = false,
    /** Now-playing metadata, for the car's own widgets. Same direction as [navigationStatus]. */
    val mediaPlayback: Boolean = false,
    val pingIntervalMs: Int = 1_000,
    val pingTimeoutMs: Int = 5_000,
) {
    init {
        require(displayName.isNotEmpty()) { "the phone shows this name in its projection prompt" }
        require(video.isNotEmpty() || audio.isNotEmpty()) { "a session with no media sink can never show anything" }
    }

    val videoChannel: Int = VIDEO_CHANNEL

    /** Audio channel id to stream type, in the order the sinks were given. */
    val audioChannels: Map<Int, AapAudioStream> =
        audio.mapIndexed { index, stream -> (VIDEO_CHANNEL + 1 + index) to stream }.toMap()

    val microphoneChannel: Int = VIDEO_CHANNEL + 1 + audio.size

    val inputChannel: Int = microphoneChannel + if (microphone != null) 1 else 0

    val sensorChannel: Int = inputChannel + if (touchscreen != null || keycodes.isNotEmpty()) 1 else 0

    val navigationChannel: Int = sensorChannel + if (sensors.isNotEmpty()) 1 else 0

    val playbackChannel: Int = navigationChannel + if (navigationStatus) 1 else 0

    /** Every channel this set will open, for logging and for the session's bookkeeping. */
    fun channels(): List<Int> = buildList {
        if (video.isNotEmpty()) add(videoChannel)
        addAll(audioChannels.keys)
        if (microphone != null) add(microphoneChannel)
        if (touchscreen != null || keycodes.isNotEmpty()) add(inputChannel)
        if (sensors.isNotEmpty()) add(sensorChannel)
        if (navigationStatus) add(navigationChannel)
        if (mediaPlayback) add(playbackChannel)
    }

    /** The resolution the head unit would prefer if the phone lets it choose, i.e. the first. */
    val preferredVideo: AapVideoConfig? get() = video.firstOrNull()

    /** `ServiceDiscoveryResponse`, ready to send on the control channel. */
    fun encodeResponse(): ByteArray {
        val writer = ProtoWire.Writer()
        for (service in services()) writer.message(1, service)
        headUnit?.let { writer.message(17, it.encode()) }
        // display_name is what the phone puts in "connected to <name>".
        writer.string(14, displayName)
        writer.message(16, connectionConfiguration())
        return writer.build()
    }

    private fun services(): List<ByteArray> = buildList {
        if (video.isNotEmpty()) add(service(VIDEO_CHANNEL, SERVICE_MEDIA_SINK, videoSink()))
        for ((channel, stream) in audioChannels) add(service(channel, SERVICE_MEDIA_SINK, audioSink(stream)))
        microphone?.let { add(service(microphoneChannel, SERVICE_MEDIA_SOURCE, mediaSource(it))) }
        if (touchscreen != null || keycodes.isNotEmpty()) {
            add(service(inputChannel, SERVICE_INPUT_SOURCE, inputSource()))
        }
        if (sensors.isNotEmpty()) add(service(sensorChannel, SERVICE_SENSOR_SOURCE, sensorSource()))
        if (navigationStatus) add(service(navigationChannel, SERVICE_NAVIGATION_STATUS, navigationStatusService()))
        if (mediaPlayback) add(service(playbackChannel, SERVICE_MEDIA_PLAYBACK, ByteArray(0)))
    }

    private fun service(channel: Int, serviceId: Int, body: ByteArray): ByteArray =
        ProtoWire.Writer().int32(1, channel).message(serviceId, body).build()

    private fun videoSink(): ByteArray {
        val writer = ProtoWire.Writer()
            // available_type names the one codec this sink takes. The phone reads it for video;
            // for audio it uses audio_type instead.
            .int32(1, AapCodec.VIDEO_H264_BP)
        for (config in video) writer.message(4, config.encode())
        writer.bool(5, false) // available_while_in_call: nothing here is a phone call
        return writer.build()
    }

    private fun audioSink(stream: AapAudioStream): ByteArray = ProtoWire.Writer()
        .int32(1, AapCodec.AUDIO_PCM)
        .int32(2, stream.type)
        .message(3, stream.encode())
        .bool(5, false)
        .build()

    private fun inputSource(): ByteArray {
        val writer = ProtoWire.Writer()
        if (keycodes.isNotEmpty()) writer.bytes(1, packedVarints(keycodes))
        touchscreen?.let { writer.message(2, it.encode()) }
        return writer.build()
    }

    private fun sensorSource(): ByteArray {
        val writer = ProtoWire.Writer()
        for (type in sensors) writer.message(1, ProtoWire.Writer().int32(1, type).build())
        return writer.build()
    }

    /** `MediaSourceService`: the microphone the phone's assistant records from. */
    private fun mediaSource(mic: AapMicrophoneSpec): ByteArray = ProtoWire.Writer()
        .int32(1, AapCodec.AUDIO_PCM)
        .message(2, mic.encodeConfig())
        .bool(3, false)
        .build()

    /**
     * `NavigationStatusService`.
     *
     * The type says the car reads turn-by-turn as an enum, not as a rendered image, so the phone
     * never has to encode a map panel for the cluster.
     */
    private fun navigationStatusService(): ByteArray = ProtoWire.Writer()
        .uint32(1, NAVIGATION_MIN_INTERVAL_MS)
        .int32(2, CLUSTER_ENUM)
        .build()

    private fun connectionConfiguration(): ByteArray = ProtoWire.Writer()
        .message(
            1,
            ProtoWire.Writer()
                .uint32(1, pingTimeoutMs)
                .uint32(2, pingIntervalMs)
                .build(),
        )
        .build()

    /**
     * `repeated int32 [packed = true]`: the varints back to back with no per-element tag, wrapped in
     * one length-delimited field. Writing them as repeated tagged fields instead is still legal
     * protobuf but doubles the size and some readers do not expect it here.
     */
    private fun packedVarints(values: List<Int>): ByteArray {
        val out = java.io.ByteArrayOutputStream(values.size * 2)
        for (value in values) {
            var remaining = value
            while (true) {
                if (remaining and 0x7f.inv() == 0) {
                    out.write(remaining and 0x7f)
                    break
                }
                out.write((remaining and 0x7f) or 0x80)
                remaining = remaining ushr 7
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val VIDEO_CHANNEL = 1

        /** Field numbers inside `Service`, which are not the same as the ids in `ServiceId`. */
        const val SERVICE_SENSOR_SOURCE = 2
        const val SERVICE_MEDIA_SINK = 3
        const val SERVICE_INPUT_SOURCE = 4
        const val SERVICE_MEDIA_SOURCE = 5
        const val SERVICE_NAVIGATION_STATUS = 8
        const val SERVICE_MEDIA_PLAYBACK = 9

        /** `NavigationStatusService.InstrumentClusterType.ENUM`, which is 2 — IMAGE is 1. */
        const val CLUSTER_ENUM = 2

        /** A turn every second is more than a driver can use; the phone throttles to this. */
        const val NAVIGATION_MIN_INTERVAL_MS = 1_000
    }
}
