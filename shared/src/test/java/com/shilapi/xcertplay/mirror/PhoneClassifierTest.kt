package com.shilapi.xcertplay.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneClassifierTest {
    private val adb = UsbInterfaceSummary(0xff, 0x42, 0x01)
    private val mtp = UsbInterfaceSummary(0x06, 0x01, 0x01)
    private val vendorSpecific = UsbInterfaceSummary(0xff, 0xff, 0x00)

    @Test
    fun appleVendorIsAnIphoneWhateverItExposes() {
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classify(0x05ac, emptyList()))
        assertEquals(PhoneKind.IPHONE, PhoneClassifier.classify(0x05ac, listOf(mtp, vendorSpecific)))
    }

    @Test
    fun anAdbInterfaceMakesAnAndroidPhoneFromAnyVendor() {
        assertEquals(PhoneKind.ANDROID, PhoneClassifier.classify(0x2717, listOf(mtp, adb)))
        assertEquals(PhoneKind.ANDROID, PhoneClassifier.classify(0x1234, listOf(adb)))
    }

    @Test
    fun aKnownAndroidVendorWithoutAdbNeedsUsbDebugging() {
        assertEquals(PhoneKind.ANDROID_DEBUGGING_OFF, PhoneClassifier.classify(0x04e8, listOf(mtp)))
        assertEquals(PhoneKind.ANDROID_DEBUGGING_OFF, PhoneClassifier.classify(0x18d1, emptyList()))
    }

    @Test
    fun otherDevicesAreUnknown() {
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classify(0x1a86, listOf(vendorSpecific)))
        assertEquals(PhoneKind.UNKNOWN, PhoneClassifier.classify(0x0bda, emptyList()))
    }

    @Test
    fun adbIsRecognisedByAllThreeClassFields() {
        assertTrue(adb.isAdb)
        assertFalse(UsbInterfaceSummary(0xff, 0x42, 0x00).isAdb)
        assertFalse(UsbInterfaceSummary(0xff, 0x01, 0x01).isAdb)
        assertFalse(UsbInterfaceSummary(0x08, 0x42, 0x01).isAdb)
    }

    @Test
    fun theVendorListHasNoAppleId() {
        assertFalse(PhoneClassifier.APPLE_VENDOR_ID in PhoneClassifier.ANDROID_VENDOR_IDS)
    }
}
