package com.shilapi.xcertplay.mirror

/** What a phone plugged into the head unit is, as far as its USB descriptors tell. */
enum class PhoneKind {
    /** Apple vendor ID: CarPlay over iAP2. */
    IPHONE,

    /** Exposes an ADB interface, so the screen can be mirrored. */
    ANDROID,

    /** A known Android vendor without an ADB interface: USB debugging is most likely off. */
    ANDROID_DEBUGGING_OFF,

    UNKNOWN,
}

/** The three class fields of one USB interface descriptor. */
data class UsbInterfaceSummary(val usbClass: Int, val subclass: Int, val protocol: Int) {
    val isAdb: Boolean
        get() = usbClass == ADB_CLASS && subclass == ADB_SUBCLASS && protocol == ADB_PROTOCOL

    companion object {
        /** AOSP adb/protocol: vendor-specific class 0xff, subclass 0x42, protocol 0x01. */
        const val ADB_CLASS = 0xff
        const val ADB_SUBCLASS = 0x42
        const val ADB_PROTOCOL = 0x01
    }
}

/**
 * Tells iPhones from Android phones without opening the device, so it can run on the attach
 * broadcast. Pure Kotlin: the caller reads the descriptors from `UsbDevice`.
 */
object PhoneClassifier {
    /** Same value as `IphoneUsbMatcher.APPLE_VENDOR_ID`; kept here so this file needs no Android classes. */
    const val APPLE_VENDOR_ID = 0x05ac

    /**
     * USB vendor IDs of common Android phone makers (the ones Android's own adb vendor list names).
     * A match without an ADB interface means "an Android phone, but the driver has to switch USB
     * debugging on". It is a hint for the prompt only; a missing vendor never blocks an ADB phone.
     */
    val ANDROID_VENDOR_IDS: Set<Int> = setOf(
        0x18d1, // Google
        0x04e8, // Samsung
        0x2717, // Xiaomi
        0x12d1, // Huawei
        0x22d9, // OPPO
        0x2a70, // OnePlus
        0x2d95, // vivo
        0x19d2, // ZTE
        0x0fce, // Sony
        0x22b8, // Motorola
        0x1004, // LG
        0x2a45, // Meizu
    )

    fun classify(vendorId: Int, interfaces: List<UsbInterfaceSummary>): PhoneKind = when {
        vendorId == APPLE_VENDOR_ID -> PhoneKind.IPHONE
        interfaces.any { it.isAdb } -> PhoneKind.ANDROID
        vendorId in ANDROID_VENDOR_IDS -> PhoneKind.ANDROID_DEBUGGING_OFF
        else -> PhoneKind.UNKNOWN
    }
}
