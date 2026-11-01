package com.shilapi.xcertplay.mirroring.aa

/**
 * Payload shapes for the messages in [AaWire]. Text payloads are UTF-8 `key=value` pairs joined by
 * `|`; binary payloads are fixed layouts. Both ends are in this repository, so the contract is
 * tested on the JVM rather than inferred from a spec.
 */
object AaMessages {

    const val PROTOCOL = "diplay-aa/1"

    /** What the phone tells the head unit before the first frame. */
    data class Hello(
        val protocol: String = PROTOCOL,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val codec: Int,
        val deviceName: String,
        val screenRotation: Int = 0,
        /** How often the phone will send [AaWire.Type.STATS]; 0 when it does not. */
        val statsIntervalMs: Int = 1000,
    ) {
        fun encode(): ByteArray = encodePairs(
            linkedMapOf(
                "protocol" to protocol,
                "width" to width.toString(),
                "height" to height.toString(),
                "density" to densityDpi.toString(),
                "codec" to codec.toString(),
                "name" to deviceName.replace('|', '/'),
                "rotation" to screenRotation.toString(),
                "stats" to statsIntervalMs.toString(),
            ),
        )

        companion object {
            fun decode(payload: ByteArray): Hello? {
                val pairs = decodePairs(payload)
                val protocol = pairs["protocol"] ?: return null
                if (protocol != PROTOCOL) return null
                return Hello(
                    protocol = protocol,
                    width = pairs["width"]?.toIntOrNull() ?: return null,
                    height = pairs["height"]?.toIntOrNull() ?: return null,
                    densityDpi = pairs["density"]?.toIntOrNull() ?: 160,
                    codec = pairs["codec"]?.toIntOrNull() ?: AaWire.Codec.H264,
                    deviceName = pairs["name"] ?: "android",
                    screenRotation = pairs["rotation"]?.toIntOrNull() ?: 0,
                    statsIntervalMs = pairs["stats"]?.toIntOrNull() ?: 1000,
                )
            }
        }
    }

    /** The head unit's reply. [accepted] false carries a reason the phone shows to its user. */
    data class HelloAck(val accepted: Boolean, val reason: String = "", val receiverName: String = "") {
        fun encode(): ByteArray = encodePairs(
            linkedMapOf(
                "accept" to if (accepted) "1" else "0",
                "reason" to reason.replace('|', '/'),
                "receiver" to receiverName.replace('|', '/'),
            ),
        )

        companion object {
            fun decode(payload: ByteArray): HelloAck {
                val pairs = decodePairs(payload)
                return HelloAck(
                    accepted = pairs["accept"] == "1",
                    reason = pairs["reason"].orEmpty(),
                    receiverName = pairs["receiver"].orEmpty(),
                )
            }
        }
    }

    /** A pointer event. Coordinates are normalised to 0..1 so the two ends may differ in size. */
    data class Touch(val action: Int, val pointerId: Int, val x: Float, val y: Float) {
        fun encode(): ByteArray {
            val out = ByteArray(SIZE)
            out[0] = action.toByte()
            out[1] = pointerId.toByte()
            putFloat(out, 2, x)
            putFloat(out, 6, y)
            return out
        }

        companion object {
            const val SIZE = 10

            fun decode(payload: ByteArray): Touch? {
                if (payload.size < SIZE) return null
                return Touch(
                    action = payload[0].toInt() and 0xFF,
                    pointerId = payload[1].toInt() and 0xFF,
                    x = getFloat(payload, 2),
                    y = getFloat(payload, 6),
                )
            }

            /**
             * Clamps before the cast so a stray value from a broken sender cannot move a pointer
             * outside the phone's screen.
             */
            fun clamp(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
        }
    }

    /** A hardware or media key from the car's buttons or steering wheel. */
    data class Key(val action: Int, val keyCode: Int, val repeat: Int = 0) {
        fun encode(): ByteArray {
            val out = ByteArray(SIZE)
            out[0] = action.toByte()
            AaWire.putInt(out, 1, keyCode)
            AaWire.putInt(out, 5, repeat)
            return out
        }

        companion object {
            const val SIZE = 9

            fun decode(payload: ByteArray): Key? {
                if (payload.size < SIZE) return null
                return Key(
                    action = payload[0].toInt() and 0xFF,
                    keyCode = AaWire.getInt(payload, 1),
                    repeat = AaWire.getInt(payload, 5),
                )
            }
        }
    }

    /** Phone-side telemetry, shown in the receiver's overlay so a stall is visible immediately. */
    data class Stats(
        val encodedFrames: Long,
        val droppedFrames: Long,
        val lastFrameBytes: Int,
        val keyFrames: Long,
        val width: Int,
        val height: Int,
    ) {
        fun encode(): ByteArray = encodePairs(
            linkedMapOf(
                "frames" to encodedFrames.toString(),
                "dropped" to droppedFrames.toString(),
                "bytes" to lastFrameBytes.toString(),
                "keys" to keyFrames.toString(),
                "width" to width.toString(),
                "height" to height.toString(),
            ),
        )

        companion object {
            fun decode(payload: ByteArray): Stats? {
                val pairs = decodePairs(payload)
                return Stats(
                    encodedFrames = pairs["frames"]?.toLongOrNull() ?: return null,
                    droppedFrames = pairs["dropped"]?.toLongOrNull() ?: 0L,
                    lastFrameBytes = pairs["bytes"]?.toIntOrNull() ?: 0,
                    keyFrames = pairs["keys"]?.toLongOrNull() ?: 0L,
                    width = pairs["width"]?.toIntOrNull() ?: 0,
                    height = pairs["height"]?.toIntOrNull() ?: 0,
                )
            }
        }
    }

    fun nonce(value: Long): ByteArray = ByteArray(8).also { AaWire.putLong(it, 0, value) }

    fun nonceOf(payload: ByteArray): Long? = if (payload.size < 8) null else AaWire.getLong(payload, 0)

    fun reason(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    fun reasonOf(payload: ByteArray): String = String(payload, Charsets.UTF_8)

    fun encodePairs(pairs: Map<String, String>): ByteArray =
        pairs.entries.joinToString("|") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8)

    fun decodePairs(payload: ByteArray): Map<String, String> {
        val text = String(payload, Charsets.UTF_8)
        if (text.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in text.split('|')) {
            val index = part.indexOf('=')
            if (index <= 0) continue
            result[part.substring(0, index)] = part.substring(index + 1)
        }
        return result
    }

    fun putFloat(target: ByteArray, offset: Int, value: Float) {
        AaWire.putInt(target, offset, java.lang.Float.floatToIntBits(value))
    }

    fun getFloat(source: ByteArray, offset: Int): Float =
        java.lang.Float.intBitsToFloat(AaWire.getInt(source, offset))
}
