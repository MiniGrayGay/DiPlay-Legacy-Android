package com.shilapi.xcertplay.aap

import com.shilapi.xcertplay.proto.ProtoWire

/**
 * Sensor reports from the car to the phone.
 *
 * Only two sensors are wired up, and they are the two the phone's UI actually reacts to:
 *
 * - **Night mode** switches the projected UI between its day and night palettes. A head unit that
 *   never reports it leaves the phone guessing from its own clock, which is wrong at dusk and in
 *   tunnels.
 * - **Driving status** is how the phone learns the car is moving and locks out text input. Sending
 *   nothing means the phone lets a driver type at 100 km/h.
 *
 * Everything else the schema carries (speed, gear, fuel, tyres) is data for widgets the phone
 * draws; none of it exists without a CAN bridge, so advertising it would only make the phone ask
 * for values that never arrive.
 */
object AapSensorMessages {

    /** `DrivingStatus`: bit flags, not an enumeration — a car can be both moving and restricted. */
    object DrivingStatus {
        const val UNRESTRICTED = 0
        const val NO_VIDEO = 1
        const val NO_KEYBOARD_INPUT = 2
        const val NO_VOICE_INPUT = 4
        const val NO_CONFIG = 8
        const val LIMIT_MESSAGE_LEN = 16
    }

    /**
     * `SensorBatch { repeated NightModeData night_mode_data = 10; repeated DrivingStatusData driving_status_data = 13 }`.
     *
     * Both fields are repeated because they share the message with every other sensor; a batch is a
     * snapshot, and this head unit only ever has one current value for each.
     */
    fun encodeBatch(nightMode: Boolean? = null, drivingStatus: Int? = null): ByteArray {
        val writer = ProtoWire.Writer()
        if (nightMode != null) {
            // NightModeData { bool night_mode = 1 }
            writer.message(10, ProtoWire.Writer().bool(1, nightMode).build())
        }
        if (drivingStatus != null) {
            // DrivingStatusData { int32 status = 1 }
            writer.message(13, ProtoWire.Writer().int32(1, drivingStatus).build())
        }
        return writer.build()
    }

    /**
     * `SensorResponse { MessageStatus status = 1 }`.
     *
     * Sent before the first batch for a sensor the phone asked for, and with [SENSOR_NOT_SUPPORTED]
     * for one this car does not have. Without it the phone waits for a value that never arrives.
     */
    fun encodeResponse(status: Int = 0): ByteArray = ProtoWire.Writer().int32(1, status).build()

    /** `MessageStatus.SENSOR_NOT_SUPPORTED`; the phone drops the sensor and asks for the next. */
    const val SENSOR_NOT_SUPPORTED = -4

    /** One `SensorRequest { SensorType type = 1; int64 min_update_period = 2 }`. */
    class Request(val type: Int, val minUpdatePeriodMs: Long) {
        override fun toString() = "sensor=$type every ${minUpdatePeriodMs}ms"
    }

    /** Parses a `SensorRequest` the phone sent, or null when the body has no type. */
    fun decodeRequest(bytes: ByteArray): Request? {
        var type = 0
        var period = 0L
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> type = field.asInt()
                2 -> period = field.intValue
            }
        }
        return if (type == 0) null else Request(type, period)
    }

    /** Asks the head unit to start reporting [type]. */
    fun encodeRequest(type: Int, minUpdatePeriodMs: Long = 0): ByteArray =
        ProtoWire.Writer().int32(1, type).int64(2, minUpdatePeriodMs).build()

    /** Reads the night-mode flag out of a `SensorBatch`, or null when this batch does not carry one. */
    fun decodeNightMode(bytes: ByteArray): Boolean? {
        var night: Boolean? = null
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            if (field.number != 10) continue
            val inner = ProtoWire.Reader(field.bytesValue ?: continue)
            while (true) {
                val next = inner.next() ?: break
                if (next.number == 1) night = next.intValue != 0L
            }
        }
        return night
    }

    /** Reads the driving-status flags out of a `SensorBatch`, or null when absent. */
    fun decodeDrivingStatus(bytes: ByteArray): Int? {
        var status: Int? = null
        val reader = ProtoWire.Reader(bytes)
        while (true) {
            val field = reader.next() ?: break
            if (field.number != 13) continue
            val inner = ProtoWire.Reader(field.bytesValue ?: continue)
            while (true) {
                val next = inner.next() ?: break
                if (next.number == 1) status = next.asInt()
            }
        }
        return status
    }
}
