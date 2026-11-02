package com.shilapi.xcertplay.mirroring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneClassifierTest {
    private fun device(vendorId: Int, productId: Int = 0x1234, vararg interfaces: UsbInterfaceIdentity) =
        UsbDeviceIdentity(vendorId, productId, interfaces.toList())

    @Test fun anAppleVendorIdIsAlwaysAnIphone() {
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classify(device(0x05ac, 0x12a8)))
        // Even when the phone also exposes an ADB-like interface, the vendor ID wins.
        assertEquals(
            PhoneKind.IPHONE,
            PhoneClassifier.classify(device(0x05ac, 0x12a8, UsbInterfaceIdentity(0xff, 0x42, 0x01))),
        )
    }

    @Test fun knownAndroidVendorsAreRecognisedWithoutAnyInterfaceDescriptor() {
        for (vendorId in PhoneClassifier.ANDROID_VENDOR_IDS) {
            assertEquals(
                "vendor 0x${vendorId.toString(16)}",
                PhoneKind.ANDROID,
                PhoneClassifier.classify(device(vendorId)),
            )
        }
    }

    @Test fun anAdbInterfaceIdentifiesAnUnlistedAndroidOem() {
        assertEquals(
            PhoneKind.ANDROID,
            PhoneClassifier.classify(device(0x2e17, 0x9001, UsbInterfaceIdentity(0xff, 0x42, 0x01))),
        )
    }

    @Test fun anotherVendorSpecificInterfaceIsNotAdb() {
        assertEquals(
            PhoneKind.OTHER,
            PhoneClassifier.classify(device(0x2e17, 0x9001, UsbInterfaceIdentity(0xff, 0x00, 0x00))),
        )
    }

    @Test fun aBluetoothRadioKeepsGoingToTheDonglePath() {
        assertEquals(
            PhoneKind.USB_BLUETOOTH_DONGLE,
            PhoneClassifier.classify(device(0x10d7, 0x0001, UsbInterfaceIdentity(0xe0, 0x01, 0x01))),
        )
    }

    @Test fun theCh341BridgeKeepsGoingToTheMfiPath() {
        assertEquals(PhoneKind.USB_SERIAL_BRIDGE, PhoneClassifier.classify(device(0x1a86, 0x5512)))
        // Same vendor, different product: not the bridge DiPlay supports.
        assertEquals(PhoneKind.OTHER, PhoneClassifier.classify(device(0x1a86, 0x7523)))
    }

    @Test fun onlyTheTwoPhoneKindsCountAsPhones() {
        assertTrue(PhoneKind.IPHONE.isPhone)
        assertTrue(PhoneKind.ANDROID.isPhone)
        assertFalse(PhoneKind.USB_BLUETOOTH_DONGLE.isPhone)
        assertFalse(PhoneKind.USB_SERIAL_BRIDGE.isPhone)
        assertFalse(PhoneKind.OTHER.isPhone)
    }
}
