package com.shilapi.xcertplay.mirroring

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorTransportSelectorTest {
    private fun decide(
        kind: PhoneKind,
        preference: PhoneTypePreference,
        androidReceiverAvailable: Boolean = false,
    ) = MirrorTransportSelector.decide(kind, preference, androidReceiverAvailable)

    @Test fun autoStartsCarPlayForAnIphone() {
        assertEquals(MirrorDecision.START_CARPLAY, decide(PhoneKind.IPHONE, PhoneTypePreference.AUTO))
    }

    @Test fun autoHandsAnAndroidPhoneToTheAndroidReceiver() {
        assertEquals(
            MirrorDecision.START_ANDROID_AUTO,
            decide(PhoneKind.ANDROID, PhoneTypePreference.AUTO, androidReceiverAvailable = true),
        )
    }

    @Test fun anAndroidPhoneIsReportedMissingWhileTheReceiverDoesNotExist() {
        assertEquals(
            MirrorDecision.ANDROID_RECEIVER_MISSING,
            decide(PhoneKind.ANDROID, PhoneTypePreference.AUTO, androidReceiverAvailable = false),
        )
        assertEquals(
            MirrorDecision.ANDROID_RECEIVER_MISSING,
            decide(PhoneKind.ANDROID, PhoneTypePreference.ANDROID_ONLY, androidReceiverAvailable = false),
        )
    }

    @Test fun carplayOnlyLeavesAnAndroidPhoneAlone() {
        assertEquals(
            MirrorDecision.IGNORE_BY_SETTING,
            decide(PhoneKind.ANDROID, PhoneTypePreference.CARPLAY_ONLY, androidReceiverAvailable = true),
        )
        assertEquals(MirrorDecision.START_CARPLAY, decide(PhoneKind.IPHONE, PhoneTypePreference.CARPLAY_ONLY))
    }

    @Test fun androidOnlyLeavesAnIphoneAlone() {
        assertEquals(MirrorDecision.IGNORE_BY_SETTING, decide(PhoneKind.IPHONE, PhoneTypePreference.ANDROID_ONLY))
        assertEquals(
            MirrorDecision.START_ANDROID_AUTO,
            decide(PhoneKind.ANDROID, PhoneTypePreference.ANDROID_ONLY, androidReceiverAvailable = true),
        )
    }

    @Test fun accessoriesAreNeverStartedWhateverTheSetting() {
        val accessories = listOf(
            PhoneKind.USB_BLUETOOTH_DONGLE,
            PhoneKind.USB_SERIAL_BRIDGE,
            PhoneKind.OTHER,
        )
        for (kind in accessories) {
            for (preference in PhoneTypePreference.entries) {
                assertEquals(
                    "$kind/$preference",
                    MirrorDecision.IGNORE_NOT_A_PHONE,
                    decide(kind, preference, androidReceiverAvailable = true),
                )
            }
        }
    }

    @Test fun theStoredKeySurvivesARoundTripAndUnknownKeysFallBackToAuto() {
        for (preference in PhoneTypePreference.entries) {
            assertEquals(preference, PhoneTypePreference.fromKey(preference.key))
        }
        assertEquals(PhoneTypePreference.AUTO, PhoneTypePreference.fromKey(null))
        assertEquals(PhoneTypePreference.AUTO, PhoneTypePreference.fromKey(""))
        assertEquals(PhoneTypePreference.AUTO, PhoneTypePreference.fromKey("android"))
    }
}
