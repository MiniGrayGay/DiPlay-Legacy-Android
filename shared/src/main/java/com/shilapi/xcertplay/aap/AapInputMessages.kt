package com.shilapi.xcertplay.aap

import com.shilapi.xcertplay.proto.ProtoWire

/**
 * Touch and key input from the car to the phone.
 *
 * The head unit reports coordinates in the space it declared in `InputSourceService.touchscreen`,
 * which for this implementation is the video resolution the phone is encoding. Coordinates outside
 * that box are accepted by the phone but land off-screen, so the caller is expected to clip: a
 * finger on the letterbox bars of a 16:9 stream on a wider panel is not a touch on the phone.
 *
 * `InputReport.timestamp` is required and is what the phone uses for gesture velocity and for
 * dropping stale moves. It must be a monotonic microsecond clock; wall-clock time makes a drag
 * register as a flick whenever the clocks disagree.
 */
object AapInputMessages {

    /**
     * `KeyBindingResponse { Status status = 1; repeated KeyCode keycodes_to_bind = 2 }`.
     *
     * Status 0 accepts the phone's proposal. Binding nothing is deliberate: the car forwards raw
     * Android key codes, and every key it can send is one the phone already understands, so asking
     * the phone to remap would only add a failure mode.
     */
    fun encodeKeyBindingResponse(status: Int = 0, keycodesToBind: List<Int> = emptyList()): ByteArray {
        val writer = ProtoWire.Writer().int32(1, status)
        for (code in keycodesToBind) writer.int32(2, code)
        return writer.build()
    }

    /** `PointerAction`. */
    object Action {
        const val DOWN = 0
        const val UP = 1
        const val MOVED = 2
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }

    /**
     * One `InputReport { uint64 timestamp = 1; TouchEvent touch_event = 3 }` with a single pointer.
     *
     * A `MOVED` report is sent for every move event the panel produces. Batching moves would cut
     * messages but make the phone's gesture detection lag by a frame, which is visible as a
     * rubber-banding scroll.
     */
    fun encodeTouch(
        timestampMicros: Long,
        x: Int,
        y: Int,
        action: Int,
        pointerId: Int = 0,
    ): ByteArray {
        val pointer = ProtoWire.Writer()
            .uint32(1, x.coerceAtLeast(0))
            .uint32(2, y.coerceAtLeast(0))
            .uint32(3, pointerId)
            .build()
        val touch = ProtoWire.Writer()
            .message(1, pointer)
            .uint32(2, 0) // action_index
            .int32(3, action)
            .build()
        return ProtoWire.Writer()
            .uint64(1, timestampMicros)
            .message(3, touch)
            .build()
    }

    /**
     * One `InputReport` carrying a key.
     *
     * [keycode] is an Android `KeyEvent` code, which is what `KeyCode` in the schema mirrors
     * one-to-one. The phone only reacts to keys it agreed to bind in `KeyBindingResponse`.
     */
    fun encodeKey(
        timestampMicros: Long,
        keycode: Int,
        down: Boolean,
        longPress: Boolean = false,
    ): ByteArray {
        val key = ProtoWire.Writer()
            .uint32(1, keycode)
            .bool(2, down)
            .uint32(3, 0) // metastate
            .bool(4, longPress)
            .build()
        val keys = ProtoWire.Writer().message(1, key).build()
        return ProtoWire.Writer()
            .uint64(1, timestampMicros)
            .message(4, keys)
            .build()
    }

    /**
     * Parses the first pointer out of an `InputReport`.
     *
     * The head unit only ever sends one pointer, so reading the first is enough; multi-touch would
     * need the whole `pointer_data` list plus `action_index`, which is why the type keeps it.
     */
    fun decodeTouch(bytes: ByteArray): Touch? {
        var timestamp = 0L
        var x = -1
        var y = -1
        var action = -1
        var pointerId = 0
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> timestamp = field.intValue
                3 -> {
                    val touch = ProtoWire.Reader(field.bytesValue ?: continue)
                    while (true) {
                        val next = touch.next() ?: break
                        when (next.number) {
                            1 -> {
                                val pointer = ProtoWire.Reader(next.bytesValue ?: continue)
                                while (true) {
                                    val p = pointer.next() ?: break
                                    when (p.number) {
                                        1 -> x = p.asInt()
                                        2 -> y = p.asInt()
                                        3 -> pointerId = p.asInt()
                                    }
                                }
                            }

                            3 -> action = next.asInt()
                        }
                    }
                }
            }
        }
        return if (x < 0 || y < 0 || action < 0) null else Touch(timestamp, x, y, action, pointerId)
    }

    /** One touch, in the head unit's video coordinates. */
    class Touch(
        val timestamp: Long,
        val x: Int,
        val y: Int,
        val action: Int,
        val pointerId: Int,
    ) {
        val up: Boolean get() = action == Action.UP || action == Action.POINTER_UP

        override fun toString() = "($x,$y) action=$action"
    }
}
