package com.shilapi.xcertplay.carlife

/**
 * A hand-rolled protobuf writer/reader for the handful of CarLife messages the head unit sends.
 *
 * The alternative was `protoc` plus the protobuf Gradle plugin, which would add a code-generation step
 * and a 1 MB runtime to a module that today has no dependencies at all. CarLife's command-channel
 * messages are flat — a few `int32` fields and some strings — so the wire format is 60 lines, and being
 * able to unit-test the exact bytes is worth more than generated classes nobody reads.
 *
 * Only the two wire types CarLife uses are implemented: varint (`0`) and length-delimited (`2`).
 */
internal object CarLifeWire {

    const val TYPE_VARINT = 0
    const val TYPE_LENGTH_DELIMITED = 2

    class Writer {
        private val out = java.io.ByteArrayOutputStream(64)

        /** `int32` field. Protobuf encodes a negative int32 as a 10-byte varint, and so does this. */
        fun int32(field: Int, value: Int): Writer {
            tag(field, TYPE_VARINT)
            varint(value.toLong())
            return this
        }

        fun string(field: Int, value: String): Writer = bytes(field, value.toByteArray(Charsets.UTF_8))

        fun bytes(field: Int, value: ByteArray): Writer {
            tag(field, TYPE_LENGTH_DELIMITED)
            varint(value.size.toLong())
            out.write(value)
            return this
        }

        fun message(field: Int, value: ByteArray): Writer = bytes(field, value)

        fun build(): ByteArray = out.toByteArray()

        private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

        private fun varint(value: Long) {
            var remaining = value
            // Negative values arrive here as 64-bit sign-extended numbers, so the loop runs ten times.
            while (true) {
                if ((remaining and 0x7fL.inv()) == 0L) {
                    out.write(remaining.toInt() and 0x7f)
                    return
                }
                out.write((remaining.toInt() and 0x7f) or 0x80)
                remaining = remaining ushr 7
            }
        }
    }

    /** One decoded field: [number], [wireType] and either [intValue] or [bytesValue]. */
    class Field(val number: Int, val wireType: Int, val intValue: Long, val bytesValue: ByteArray?)

    /**
     * Walks a message, skipping fields the caller does not know about.
     *
     * Skipping rather than failing matters: a newer CarLife app may add fields to `CarlifeDeviceInfo`, and
     * a head unit that rejects unknown fields would stop working after an app update.
     */
    class Reader(private val data: ByteArray) {
        private var offset = 0

        fun next(): Field? {
            if (offset >= data.size) return null
            val key = varint()
            val number = (key ushr 3).toInt()
            val wireType = (key and 0x7L).toInt()
            return when (wireType) {
                TYPE_VARINT -> Field(number, wireType, varint(), null)
                TYPE_LENGTH_DELIMITED -> {
                    val length = varint().toInt()
                    if (length < 0 || offset + length > data.size) {
                        throw IllegalStateException("field $number claims $length bytes past the end")
                    }
                    val slice = data.copyOfRange(offset, offset + length)
                    offset += length
                    Field(number, wireType, 0, slice)
                }
                // 64-bit, 32-bit and group encodings never appear in CarLife; stop instead of guessing.
                else -> throw IllegalStateException("unsupported wire type $wireType on field $number")
            }
        }

        private fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (offset >= data.size) throw IllegalStateException("truncated varint")
                val byte = data[offset++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if ((byte and 0x80) == 0) return result
                shift += 7
                if (shift > 63) throw IllegalStateException("varint longer than 10 bytes")
            }
        }
    }
}

/**
 * The CarLife messages the head unit actually sends or reads, built to the field numbers in Baidu's
 * published `.proto` files.
 *
 * Every number here is copied from `Carlife*Proto.proto` in `CarLife-Vehicle-Lib/LibSource/protobuf`.
 * They are `required`/`optional` in those files but that distinction does not exist on the wire, so a
 * message is simply "whichever fields this build knows".
 */
object CarLifeProto {

    /** `CarlifeProtocolVersion { majorVersion = 1; minorVersion = 2; }` */
    fun encodeProtocolVersion(major: Int, minor: Int): ByteArray = CarLifeWire.Writer()
        .int32(FIELD_VERSION_MAJOR, major)
        .int32(FIELD_VERSION_MINOR, minor)
        .build()

    fun decodeProtocolVersion(payload: ByteArray): CarLifeProtocolVersion {
        var major = 0
        var minor = 0
        val reader = CarLifeWire.Reader(payload)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                FIELD_VERSION_MAJOR -> major = field.intValue.toInt()
                FIELD_VERSION_MINOR -> minor = field.intValue.toInt()
            }
        }
        return CarLifeProtocolVersion(major, minor)
    }

    /**
     * `CarlifeVideoEncoderInfo { width = 1; height = 2; frameRate = 3; }`
     *
     * This is the one message that decides whether the session is usable: the phone encodes at exactly
     * this size, and the head unit maps every touch against it. Sending the screen size instead of the
     * visible-area size is what makes touches land off-target on a wide dash display.
     */
    fun encodeVideoEncoderInfo(width: Int, height: Int, frameRate: Int): ByteArray = CarLifeWire.Writer()
        .int32(FIELD_ENCODER_WIDTH, width)
        .int32(FIELD_ENCODER_HEIGHT, height)
        .int32(FIELD_ENCODER_FRAME_RATE, frameRate)
        .build()

    /** The phone answers `VIDEO_ENCODER_INIT_DONE` with the same shape, so the same decoder reads it. */
    fun decodeVideoEncoderInfo(payload: ByteArray): CarLifeVideoEncoderInfo {
        var width = 0
        var height = 0
        var frameRate = 0
        val reader = CarLifeWire.Reader(payload)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                FIELD_ENCODER_WIDTH -> width = field.intValue.toInt()
                FIELD_ENCODER_HEIGHT -> height = field.intValue.toInt()
                FIELD_ENCODER_FRAME_RATE -> frameRate = field.intValue.toInt()
            }
        }
        return CarLifeVideoEncoderInfo(width, height, frameRate)
    }

    /**
     * Reads the single-number messages: `CarlifeProtocolVersionMatchStatus { matchStatus }` and the
     * smaller acks.
     *
     * Deliberately field-number agnostic — it returns the first varint it meets. Those messages carry one
     * field and nothing else, and pinning a number this build could not verify from the published `.proto`
     * files would turn a harmless difference into a failed handshake.
     */
    fun decodeSingleInt(payload: ByteArray): Int? {
        val reader = CarLifeWire.Reader(payload)
        while (true) {
            val field = reader.next() ?: return null
            if (field.wireType == CarLifeWire.TYPE_VARINT) return field.intValue.toInt()
        }
    }

    /**
     * `CarlifeDeviceInfo` — 23 fields, `os` through `btaddress`.
     *
     * Only the fields DiPlay can fill honestly are written. An empty string is skipped rather than sent
     * as `""`, because the phone treats a present-but-empty `model` as a real model and shows a blank
     * device name in its CarLife screen.
     */
    fun encodeDeviceInfo(info: CarLifeDeviceInfo): ByteArray {
        val writer = CarLifeWire.Writer()
        info.os?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_OS, it) }
        info.board?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_BOARD, it) }
        info.bootloader?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_BOOTLOADER, it) }
        info.brand?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_BRAND, it) }
        info.cpuAbi?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_CPU_ABI, it) }
        info.device?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_DEVICE, it) }
        info.display?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_DISPLAY, it) }
        info.hardware?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_HARDWARE, it) }
        info.manufacturer?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_MANUFACTURER, it) }
        info.model?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_MODEL, it) }
        info.product?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_PRODUCT, it) }
        info.serial?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_SERIAL, it) }
        info.release?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_RELEASE, it) }
        info.sdk?.takeIf { it.isNotEmpty() }?.let { writer.string(FIELD_INFO_SDK, it) }
        if (info.sdkInt > 0) writer.int32(FIELD_INFO_SDK_INT, info.sdkInt)
        return writer.build()
    }

    fun decodeDeviceInfo(payload: ByteArray): CarLifeDeviceInfo {
        var info = CarLifeDeviceInfo()
        val reader = CarLifeWire.Reader(payload)
        while (true) {
            val field = reader.next() ?: break
            val text = field.bytesValue?.toString(Charsets.UTF_8)
            info = when (field.number) {
                FIELD_INFO_OS -> info.copy(os = text)
                FIELD_INFO_BOARD -> info.copy(board = text)
                FIELD_INFO_BOOTLOADER -> info.copy(bootloader = text)
                FIELD_INFO_BRAND -> info.copy(brand = text)
                FIELD_INFO_CPU_ABI -> info.copy(cpuAbi = text)
                FIELD_INFO_DEVICE -> info.copy(device = text)
                FIELD_INFO_DISPLAY -> info.copy(display = text)
                FIELD_INFO_HARDWARE -> info.copy(hardware = text)
                FIELD_INFO_MANUFACTURER -> info.copy(manufacturer = text)
                FIELD_INFO_MODEL -> info.copy(model = text)
                FIELD_INFO_PRODUCT -> info.copy(product = text)
                FIELD_INFO_SERIAL -> info.copy(serial = text)
                FIELD_INFO_RELEASE -> info.copy(release = text)
                FIELD_INFO_SDK -> info.copy(sdk = text)
                FIELD_INFO_SDK_INT -> info.copy(sdkInt = field.intValue.toInt())
                else -> info
            }
        }
        return info
    }

    /** Field numbers, so a typo is a compile error rather than a message the phone ignores. */
    private const val FIELD_VERSION_MAJOR = 1
    private const val FIELD_VERSION_MINOR = 2

    private const val FIELD_ENCODER_WIDTH = 1
    private const val FIELD_ENCODER_HEIGHT = 2
    private const val FIELD_ENCODER_FRAME_RATE = 3

    private const val FIELD_INFO_OS = 1
    private const val FIELD_INFO_BOARD = 2
    private const val FIELD_INFO_BOOTLOADER = 3
    private const val FIELD_INFO_BRAND = 4
    private const val FIELD_INFO_CPU_ABI = 5
    private const val FIELD_INFO_DEVICE = 7
    private const val FIELD_INFO_DISPLAY = 8
    private const val FIELD_INFO_HARDWARE = 10
    private const val FIELD_INFO_MANUFACTURER = 13
    private const val FIELD_INFO_MODEL = 14
    private const val FIELD_INFO_PRODUCT = 15
    private const val FIELD_INFO_SERIAL = 16
    private const val FIELD_INFO_RELEASE = 19
    private const val FIELD_INFO_SDK = 20
    private const val FIELD_INFO_SDK_INT = 21
}

data class CarLifeProtocolVersion(val major: Int, val minor: Int)

data class CarLifeVideoEncoderInfo(val width: Int, val height: Int, val frameRate: Int)

/**
 * The head unit's own device description, as far as DiPlay can fill it in.
 *
 * A `null` field is left off the wire entirely; see [CarLifeProto.encodeDeviceInfo] for why that is not
 * the same as sending an empty string.
 */
data class CarLifeDeviceInfo(
    val os: String? = null,
    val board: String? = null,
    val bootloader: String? = null,
    val brand: String? = null,
    val cpuAbi: String? = null,
    val device: String? = null,
    val display: String? = null,
    val hardware: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val product: String? = null,
    val serial: String? = null,
    val release: String? = null,
    val sdk: String? = null,
    val sdkInt: Int = 0,
)

/**
 * Touch payloads on the control channel.
 *
 * These are **not** protobuf. `S_TOUCH_ACTION_DOWN { u32 x; u32 y; }` is a plain struct that the phone
 * reads with `memcpy`, so the payload is two little-endian 32-bit integers and nothing else — eight
 * bytes for the gesture messages, twelve for the combined one.
 */
object CarLifeTouch {
    const val GESTURE_PAYLOAD_SIZE = 8

    fun encode(x: Int, y: Int): ByteArray {
        val out = ByteArray(GESTURE_PAYLOAD_SIZE)
        putLe(out, 0, x)
        putLe(out, 4, y)
        return out
    }

    fun decode(payload: ByteArray): Pair<Int, Int> {
        require(payload.size >= GESTURE_PAYLOAD_SIZE) { "touch payload is ${payload.size} bytes" }
        return readLe(payload, 0) to readLe(payload, 4)
    }

    /**
     * Maps a point on the head unit's display onto the phone's encoded frame.
     *
     * [rect] is where the video actually landed after letterboxing, so a tap in a black bar has no
     * meaning and is dropped instead of being clamped to the nearest edge — clamping is what makes the
     * phone react to a tap the driver never aimed at the picture.
     */
    fun toFrame(
        xInRect: Float,
        yInRect: Float,
        rectLeft: Int,
        rectTop: Int,
        rectWidth: Int,
        rectHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Pair<Int, Int>? {
        if (rectWidth <= 0 || rectHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
        val localX = xInRect - rectLeft
        val localY = yInRect - rectTop
        if (localX < 0f || localY < 0f || localX > rectWidth || localY > rectHeight) return null
        val px = (localX / rectWidth * frameWidth).toInt().coerceIn(0, frameWidth - 1)
        val py = (localY / rectHeight * frameHeight).toInt().coerceIn(0, frameHeight - 1)
        return px to py
    }

    private fun putLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        target[offset + 2] = ((value ushr 16) and 0xff).toByte()
        target[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    private fun readLe(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)
}
