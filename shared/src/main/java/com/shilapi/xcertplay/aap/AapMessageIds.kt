package com.shilapi.xcertplay.aap

/**
 * Message ids, mirrored from the `*MessageId` / `ControlMessageType` enums in the AAP protobuf
 * definitions so the hot paths can switch on plain ints.
 *
 * The protobuf definitions come from aasdk (GPL-3.0-or-later). They are written out as constants
 * here because the head unit only needs the handful it sends or dispatches on, and pulling a
 * protobuf runtime into the shared module for that would be heavier than the protocol itself.
 */
object AapMessageIds {

    /** Control channel (channel 0). */
    object Control {
        const val VERSION_REQUEST = 1
        const val VERSION_RESPONSE = 2
        const val ENCAPSULATED_SSL = 3
        const val AUTH_COMPLETE = 4
        const val SERVICE_DISCOVERY_REQUEST = 5
        const val SERVICE_DISCOVERY_RESPONSE = 6
        const val CHANNEL_OPEN_REQUEST = 7
        const val CHANNEL_OPEN_RESPONSE = 8
        const val CHANNEL_CLOSE_NOTIFICATION = 9
        const val PING_REQUEST = 11
        const val PING_RESPONSE = 12
        const val NAV_FOCUS_REQUEST = 13
        const val NAV_FOCUS_NOTIFICATION = 14
        const val BYEBYE_REQUEST = 15
        const val BYEBYE_RESPONSE = 16
        const val VOICE_SESSION_NOTIFICATION = 17
        const val AUDIO_FOCUS_REQUEST = 18
        const val AUDIO_FOCUS_NOTIFICATION = 19
        const val CAR_CONNECTED_DEVICES_REQUEST = 20
        const val CAR_CONNECTED_DEVICES_RESPONSE = 21
        const val USER_SWITCH_REQUEST = 22
        const val BATTERY_STATUS_NOTIFICATION = 23
        const val CALL_AVAILABILITY_STATUS = 24
        const val USER_SWITCH_RESPONSE = 25
        const val SERVICE_DISCOVERY_UPDATE = 26
        const val UNEXPECTED_MESSAGE = 255
        const val FRAMING_ERROR = 65535
    }

    /** Media sink (video in, audio out) and media source (microphone). */
    object Media {
        const val DATA = 0 // 8-byte timestamp followed by the payload
        const val CODEC_CONFIG = 1 // payload only
        const val SETUP = 0x8000
        const val START = 0x8001
        const val STOP = 0x8002
        const val CONFIG = 0x8003
        const val ACK = 0x8004
        const val MICROPHONE_REQUEST = 0x8005
        const val MICROPHONE_RESPONSE = 0x8006
        const val VIDEO_FOCUS_REQUEST = 0x8007
        const val VIDEO_FOCUS_NOTIFICATION = 0x8008
        const val UPDATE_UI_CONFIG_REQUEST = 0x8009
        const val UPDATE_UI_CONFIG_REPLY = 0x800A
        const val AUDIO_UNDERFLOW_NOTIFICATION = 0x800B
    }

    object Input {
        const val INPUT_REPORT = 0x8001
        const val KEY_BINDING_REQUEST = 0x8002
        const val KEY_BINDING_RESPONSE = 0x8003
        const val INPUT_FEEDBACK = 0x8004
    }

    object Sensor {
        const val REQUEST = 0x8001
        const val RESPONSE = 0x8002
        const val BATCH = 0x8003
        const val ERROR = 0x8004
    }

    object NavigationStatus {
        const val START = 0x8001
        const val STOP = 0x8002
        const val STATUS = 0x8003
        const val TURN_EVENT = 0x8004 // deprecated, still what older head units read
        const val DISTANCE_EVENT = 0x8005 // deprecated, same
        const val STATE = 0x8006
        const val CURRENT_POSITION = 0x8007
    }

    object MediaPlayback {
        const val STATUS = 0x8001
        const val INPUT = 0x8002
        const val METADATA = 0x8003
    }

    object Bluetooth {
        const val PAIRING_REQUEST = 0x8001
        const val PAIRING_RESPONSE = 0x8002
        const val AUTHENTICATION_DATA = 0x8003
        const val AUTHENTICATION_RESULT = 0x8004
    }

    object PhoneStatus {
        const val STATUS = 0x8001
        const val INPUT = 0x8002
    }

    object GenericNotification {
        const val SUBSCRIBE = 0x8001
        const val UNSUBSCRIBE = 0x8002
        const val MESSAGE = 0x8003
        const val ACK = 0x8004
    }

    object WifiProjection {
        const val CREDENTIALS_REQUEST = 0x8001
        const val CREDENTIALS_RESPONSE = 0x8002
    }
}

/**
 * Service ids used in `ServiceDiscoveryResponse`, which is how the head unit tells the phone what
 * it can consume.
 *
 * The numbers come from `Service.proto`. They are what the phone matches on when it decides
 * whether to open a video sink, an input source and so on.
 */
object AapServiceId {
    const val CONTROL = 0
    const val SENSOR_SOURCE = 1
    const val MEDIA_SINK = 2
    const val MEDIA_SOURCE = 3
    const val BLUETOOTH = 4
    const val INPUT_SOURCE = 5
    const val MEDIA_OUTPUT = 6
    const val PHONE_STATUS = 7
    const val MEDIA_BROWSER = 8
    const val VENDOR_EXTENSION = 9
    const val GENERIC_NOTIFICATION = 10
    const val WIFI_PROJECTION = 11
    const val NAVIGATION_STATUS = 12
    const val MEDIA_PLAYBACK_STATUS = 13
    const val INSTRUMENT_CLUSTER = 14
    const val RADIO = 15
}

/**
 * `MessageStatus` values the peer returns. Anything below zero is a refusal, and two of them mean
 * the certificate is the problem rather than the protocol, which is worth telling the driver
 * apart from a generic handshake failure.
 */
object AapStatus {
    const val SUCCESS = 0
    const val AUTHENTICATION_FAILURE = -3
    const val CERT_NOT_YET_VALID = -23
    const val CERT_EXPIRED = -24

    fun describe(status: Int): String = when (status) {
        SUCCESS -> "success"
        AUTHENTICATION_FAILURE -> "the phone rejected this head unit's certificate"
        CERT_NOT_YET_VALID -> "the certificate is not valid yet; check the head unit's clock"
        CERT_EXPIRED -> "the certificate has expired"
        else -> "status $status"
    }
}
