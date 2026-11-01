package com.shilapi.xcertplay.mirroring

/**
 * One USB interface descriptor.
 *
 * Android already caches these in the framework and hands them out from
 * `UsbDevice.getInterface(i)`, so reading them needs neither a permission grant nor an open
 * connection. Keeping the values as plain ints keeps classification a pure JVM decision.
 */
data class UsbInterfaceIdentity(
    val interfaceClass: Int,
    val subclass: Int = 0,
    val protocol: Int = 0,
)

/** The slice of a USB device this package reasons about. */
data class UsbDeviceIdentity(
    val vendorId: Int,
    val productId: Int,
    val interfaces: List<UsbInterfaceIdentity> = emptyList(),
)

/** What sits on the other end of the cable. */
enum class PhoneKind {
    IPHONE,
    ANDROID,

    /** A USB Bluetooth radio, e.g. the dongles handled by `usbbt`. */
    USB_BLUETOOTH_DONGLE,

    /** The CH341 serial bridge used for local MFi signing. */
    USB_SERIAL_BRIDGE,

    OTHER,
    ;

    /** True for the two kinds that can carry a mirroring session. */
    val isPhone: Boolean get() = this == IPHONE || this == ANDROID
}

/**
 * Decides whether an attached device is an iPhone, an Android phone, or neither.
 *
 * The order matters:
 * 1. Apple's vendor ID, so an iPhone is never mistaken for anything else.
 * 2. A known Android OEM vendor ID — this is the only signal available while the phone runs in
 *    a plain file-transfer or charging mode.
 * 3. An ADB interface, which catches phones from OEMs missing from [ANDROID_VENDOR_IDS] as soon as
 *    USB debugging is on.
 * 4. Accessories DiPlay already supports, so they keep their existing handling.
 */
object PhoneClassifier {
    const val APPLE_VENDOR_ID = 0x05ac

    /** CH341 (`VID_1A86&PID_5512`), the bridge named in `usb_device_filter.xml`. */
    const val CH341_VENDOR_ID = 0x1a86
    const val CH341_PRODUCT_ID = 0x5512

    // Wireless Controller / RF / Bluetooth, as declared by every USB Bluetooth radio.
    private const val BLUETOOTH_CLASS = 0xe0
    private const val BLUETOOTH_SUBCLASS_RF = 0x01
    private const val BLUETOOTH_PROTOCOL = 0x01

    // Vendor-specific / vendor-specific subclass / ADB. Android's own ADB interface definition.
    private const val VENDOR_SPECIFIC_CLASS = 0xff
    private const val ADB_SUBCLASS = 0x42
    private const val ADB_PROTOCOL = 0x01

    /**
     * Vendor IDs of Android phones, by OEM. A phone in charging or file-transfer mode exposes no
     * vendor-specific interface, so the vendor ID is the only evidence available before the driver
     * enables USB debugging.
     */
    val ANDROID_VENDOR_IDS: Set<Int> = setOf(
        0x18d1, // Google, Nexus, Pixel
        0x04e8, // Samsung
        0x2717, // Xiaomi, Redmi, POCO
        0x12d1, // Huawei
        0x2a70, // OnePlus
        0x22d9, // OPPO, Realme, OnePlus (BBK)
        0x2d95, // vivo, iQOO
        0x22b8, // Motorola
        0x0fce, // Sony
        0x1004, // LG
        0x0bb4, // HTC
        0x0421, // Nokia
        0x1f3a, // Nubia, ZTE gaming
        0x19d2, // ZTE
    )

    fun classify(device: UsbDeviceIdentity): PhoneKind = when {
        device.vendorId == APPLE_VENDOR_ID -> PhoneKind.IPHONE
        device.vendorId in ANDROID_VENDOR_IDS -> PhoneKind.ANDROID
        device.hasAdbInterface() -> PhoneKind.ANDROID
        device.hasBluetoothRadio() -> PhoneKind.USB_BLUETOOTH_DONGLE
        device.vendorId == CH341_VENDOR_ID && device.productId == CH341_PRODUCT_ID -> PhoneKind.USB_SERIAL_BRIDGE
        else -> PhoneKind.OTHER
    }

    private fun UsbDeviceIdentity.hasAdbInterface(): Boolean = interfaces.any {
        it.interfaceClass == VENDOR_SPECIFIC_CLASS && it.subclass == ADB_SUBCLASS && it.protocol == ADB_PROTOCOL
    }

    private fun UsbDeviceIdentity.hasBluetoothRadio(): Boolean = interfaces.any {
        it.interfaceClass == BLUETOOTH_CLASS && it.subclass == BLUETOOTH_SUBCLASS_RF &&
            it.protocol == BLUETOOTH_PROTOCOL
    }
}
