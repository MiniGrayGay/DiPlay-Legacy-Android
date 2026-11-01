package com.shilapi.xcertplay.mirroring

/** Which phone the driver wants DiPlay to serve. Backs the "Phone type" setting. */
enum class PhoneTypePreference(val key: String) {
    /** Serve whichever phone is plugged in. */
    AUTO("auto"),

    /** Only an iPhone starts a session; Android phones are left alone. */
    CARPLAY_ONLY("carplay_only"),

    /** Only an Android phone starts a session; an iPhone is left alone. */
    ANDROID_ONLY("android_only"),
    ;

    companion object {
        fun fromKey(key: String?): PhoneTypePreference = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** What DiPlay does with a freshly attached USB device. */
enum class MirrorDecision {
    START_CARPLAY,
    START_ANDROID_AUTO,

    /** An Android phone, but this build has no Android Auto receiver yet. */
    ANDROID_RECEIVER_MISSING,

    /** The driver's "Phone type" choice excludes this phone. */
    IGNORE_BY_SETTING,

    /** A dongle, a serial bridge, a dashcam — never a phone, so `usbbt` and CH341 keep it. */
    IGNORE_NOT_A_PHONE,
}

/**
 * The one place that turns "what is plugged in" plus "what the driver asked for" into "what DiPlay
 * starts". Pure logic, so every combination is covered by a JVM test.
 */
object MirrorTransportSelector {
    fun decide(
        kind: PhoneKind,
        preference: PhoneTypePreference,
        androidReceiverAvailable: Boolean = false,
    ): MirrorDecision = when (kind) {
        PhoneKind.IPHONE -> when (preference) {
            PhoneTypePreference.ANDROID_ONLY -> MirrorDecision.IGNORE_BY_SETTING
            else -> MirrorDecision.START_CARPLAY
        }

        PhoneKind.ANDROID -> when (preference) {
            PhoneTypePreference.CARPLAY_ONLY -> MirrorDecision.IGNORE_BY_SETTING
            else -> if (androidReceiverAvailable) {
                MirrorDecision.START_ANDROID_AUTO
            } else {
                MirrorDecision.ANDROID_RECEIVER_MISSING
            }
        }

        PhoneKind.USB_BLUETOOTH_DONGLE,
        PhoneKind.USB_SERIAL_BRIDGE,
        PhoneKind.OTHER,
        -> MirrorDecision.IGNORE_NOT_A_PHONE
    }
}
