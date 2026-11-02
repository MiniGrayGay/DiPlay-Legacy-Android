package com.shilapi.xcertplay.aap

import com.shilapi.xcertplay.proto.ProtoWire

/**
 * Parses the head unit's `ServiceDiscoveryResponse`.
 *
 * This is the phone's view of [AapServiceSet]: the same message, read the other way. It exists in
 * the shared module because two things need it — the protocol tests, and the phone simulator used
 * to exercise a head unit without a real phone in the loop.
 *
 * Only the fields a projection client acts on are decoded. Everything else in `Service` (radio,
 * media browser, vendor extensions...) is skipped by the reader rather than rejected, so a head
 * unit that offers more than this understands still works.
 */
object AapServiceDiscovery {

    /** What a channel is for, decided by which sub-message the service carries. */
    enum class Kind { VIDEO, AUDIO, INPUT, SENSOR, OTHER }

    class VideoConfig(
        val resolution: AapResolution,
        val frameRate: Int,
        val density: Int,
        val widthMargin: Int,
        val heightMargin: Int,
    ) {
        override fun toString() = "${resolution.width}x${resolution.height}@$frameRate"
    }

    class AudioConfig(val sampleRate: Int, val bitDepth: Int, val channels: Int) {
        override fun toString() = "${sampleRate}Hz/${bitDepth}bit/${channels}ch"
    }

    class Service(
        val channel: Int,
        val kind: Kind,
        val videoConfigs: List<VideoConfig> = emptyList(),
        val audioType: Int = 0,
        val audioConfigs: List<AudioConfig> = emptyList(),
        val touchWidth: Int = 0,
        val touchHeight: Int = 0,
        val keycodes: List<Int> = emptyList(),
        val sensors: List<Int> = emptyList(),
    ) {
        override fun toString() = when (kind) {
            Kind.VIDEO -> "video#$channel ${videoConfigs.joinToString()}"
            Kind.AUDIO -> "audio#$channel type=$audioType ${audioConfigs.joinToString()}"
            Kind.INPUT -> "input#$channel ${touchWidth}x$touchHeight keys=${keycodes.size}"
            Kind.SENSOR -> "sensors#$channel ${sensors.size}"
            Kind.OTHER -> "other#$channel"
        }
    }

    /** The head unit's whole answer. */
    class Discovery(val services: List<Service>, val displayName: String, val pingIntervalMs: Int, val pingTimeoutMs: Int) {

        val video: Service? get() = services.firstOrNull { it.kind == Kind.VIDEO }
        val input: Service? get() = services.firstOrNull { it.kind == Kind.INPUT }
        val sensors: Service? get() = services.firstOrNull { it.kind == Kind.SENSOR }
        val audio: List<Service> get() = services.filter { it.kind == Kind.AUDIO }

        /** Audio channels whose stream type matches [type]. */
        fun audioFor(type: Int): Service? = audio.firstOrNull { it.audioType == type }

        override fun toString() = "$displayName: ${services.joinToString()}"
    }

    fun parse(bytes: ByteArray): Discovery {
        val services = ArrayList<Service>()
        var displayName = ""
        var pingInterval = 0
        var pingTimeout = 0
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> field.bytesValue?.let { services += parseService(it) }
                14 -> displayName = field.stringValue()
                16 -> field.bytesValue?.let {
                    val config = parsePingConfiguration(it)
                    pingInterval = config.first
                    pingTimeout = config.second
                }
            }
        }
        return Discovery(services, displayName, pingInterval, pingTimeout)
    }

    private fun parsePingConfiguration(bytes: ByteArray): Pair<Int, Int> {
        var interval = 0
        var timeout = 0
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            // ConnectionConfiguration { PingConfiguration ping_configuration = 1 }
            if (field.number != 1) continue
            val inner = ProtoWire.Reader(field.bytesValue ?: continue)
            while (true) {
                val next = inner.next() ?: break
                when (next.number) {
                    1 -> timeout = next.asInt()
                    2 -> interval = next.asInt()
                }
            }
        }
        return interval to timeout
    }

    private fun parseService(bytes: ByteArray): Service {
        var channel = 0
        var kind = Kind.OTHER
        var videoConfigs = listOf<VideoConfig>()
        var audioType = 0
        var audioConfigs = listOf<AudioConfig>()
        var touchWidth = 0
        var touchHeight = 0
        var keycodes = listOf<Int>()
        var sensors = listOf<Int>()
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> channel = field.asInt()
                2 -> {
                    kind = Kind.SENSOR
                    sensors = parseSensorSource(field.bytesValue ?: ByteArray(0))
                }

                3 -> {
                    val sink = field.bytesValue ?: ByteArray(0)
                    val parsed = parseMediaSink(sink)
                    if (parsed.videoConfigs.isNotEmpty()) {
                        kind = Kind.VIDEO
                        videoConfigs = parsed.videoConfigs
                    } else {
                        kind = Kind.AUDIO
                        audioType = parsed.audioType
                        audioConfigs = parsed.audioConfigs
                    }
                }

                4 -> {
                    kind = Kind.INPUT
                    val input = parseInputSource(field.bytesValue ?: ByteArray(0))
                    touchWidth = input.first
                    touchHeight = input.second
                    keycodes = input.third
                }
            }
        }
        return Service(channel, kind, videoConfigs, audioType, audioConfigs, touchWidth, touchHeight, keycodes, sensors)
    }

    private class MediaSinkFields(
        val videoConfigs: List<VideoConfig>,
        val audioType: Int,
        val audioConfigs: List<AudioConfig>,
    )

    private fun parseMediaSink(bytes: ByteArray): MediaSinkFields {
        val videos = ArrayList<VideoConfig>()
        val audios = ArrayList<AudioConfig>()
        var audioType = 0
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                2 -> audioType = field.asInt()
                3 -> field.bytesValue?.let { audios += parseAudioConfig(it) }
                4 -> field.bytesValue?.let { videos += parseVideoConfig(it) }
            }
        }
        return MediaSinkFields(videos, audioType, audios)
    }

    private fun parseVideoConfig(bytes: ByteArray): VideoConfig {
        var resolution = AapResolution.P800x480
        var frameRate = 30
        var density = 160
        var widthMargin = 0
        var heightMargin = 0
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> resolution = AapResolution.of(field.asInt()) ?: resolution
                2 -> frameRate = if (field.asInt() == 1) 60 else 30
                3 -> widthMargin = field.asInt()
                4 -> heightMargin = field.asInt()
                5 -> density = field.asInt()
            }
        }
        return VideoConfig(resolution, frameRate, density, widthMargin, heightMargin)
    }

    private fun parseAudioConfig(bytes: ByteArray): AudioConfig {
        var rate = 48_000
        var depth = 16
        var channels = 2
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> rate = field.asInt()
                2 -> depth = field.asInt()
                3 -> channels = field.asInt()
            }
        }
        return AudioConfig(rate, depth, channels)
    }

    /** Returns (width, height, keycodes). */
    private fun parseInputSource(bytes: ByteArray): Triple<Int, Int, List<Int>> {
        var width = 0
        var height = 0
        var keycodes = listOf<Int>()
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> keycodes = field.bytesValue?.let { unpackVarints(it) } ?: emptyList()
                2 -> {
                    val touch = ProtoWire.Reader(field.bytesValue ?: continue)
                    while (true) {
                        val next = touch.next() ?: break
                        when (next.number) {
                            1 -> width = next.asInt()
                            2 -> height = next.asInt()
                        }
                    }
                }
            }
        }
        return Triple(width, height, keycodes)
    }

    private fun parseSensorSource(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            if (field.number != 1) continue
            // Sensor { SensorType sensor_type = 1 }
            val sensor = ProtoWire.Reader(field.bytesValue ?: continue)
            while (true) {
                val next = sensor.next() ?: break
                if (next.number == 1) out += next.asInt()
            }
        }
        return out
    }

    /** `repeated int32 [packed = true]`: varints back to back, no per-element tag. */
    private fun unpackVarints(bytes: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var value = 0
        var shift = 0
        for (byte in bytes) {
            val b = byte.toInt() and 0xff
            value = value or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) {
                out += value
                value = 0
                shift = 0
            } else {
                shift += 7
            }
        }
        return out
    }
}
