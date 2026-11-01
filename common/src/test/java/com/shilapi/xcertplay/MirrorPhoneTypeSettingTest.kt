package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbDevice
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.mirroring.MirrorDecision
import com.shilapi.xcertplay.mirroring.PhoneClassifier
import com.shilapi.xcertplay.mirroring.PhoneKind
import com.shilapi.xcertplay.mirroring.PhoneTypePreference
import com.shilapi.xcertplay.settings.ConnectionSettingsSection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class MirrorPhoneTypeSettingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
    }

    @After fun resetPreferences() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
    }

    @Test fun theDefaultKeepsServingAnIphone() {
        assertEquals(PhoneTypePreference.AUTO, DiPlayPreferences.phoneType(context))
        assertEquals(MirrorDecision.START_CARPLAY, MirrorUsbAttach.decision(context, device(IPHONE_VENDOR_ID)))
    }

    @Test fun everyOptionSurvivesARestart() {
        for (preference in PhoneTypePreference.entries) {
            DiPlayPreferences.savePhoneType(context, preference)
            assertEquals(preference, DiPlayPreferences.phoneType(context))
        }
    }

    @Test fun carplayOnlyStopsAnAndroidPhoneFromBeingServed() {
        DiPlayPreferences.savePhoneType(context, PhoneTypePreference.CARPLAY_ONLY)
        assertEquals(MirrorDecision.IGNORE_BY_SETTING, MirrorUsbAttach.decision(context, device(ANDROID_VENDOR_ID)))
    }

    @Test fun androidOnlyStopsAnIphoneFromStartingCarPlay() {
        DiPlayPreferences.savePhoneType(context, PhoneTypePreference.ANDROID_ONLY)
        assertEquals(MirrorDecision.IGNORE_BY_SETTING, MirrorUsbAttach.decision(context, device(IPHONE_VENDOR_ID)))
        // This build has no `:autoreceiver` module, so an Android phone is recognised but idle.
        assertEquals(
            MirrorDecision.ANDROID_RECEIVER_MISSING,
            MirrorUsbAttach.decision(context, device(ANDROID_VENDOR_ID)),
        )
    }

    @Test fun anAccessoryIsNeverStartedWhateverTheSetting() {
        for (preference in PhoneTypePreference.entries) {
            DiPlayPreferences.savePhoneType(context, preference)
            assertEquals(
                "$preference",
                MirrorDecision.IGNORE_NOT_A_PHONE,
                MirrorUsbAttach.decision(context, device(0x1a86, productId = 0x5512)),
            )
        }
    }

    @Test fun theBridgeClassifiesDescriptorsWithoutOpeningTheDevice() {
        val identity = MirrorUsbAttach.identity(device(ANDROID_VENDOR_ID, productId = 0x4ee1))
        assertEquals(ANDROID_VENDOR_ID, identity.vendorId)
        assertEquals(0x4ee1, identity.productId)
        assertTrue(identity.interfaces.isEmpty())
        assertEquals(PhoneKind.ANDROID, PhoneClassifier.classify(identity))
    }

    @Test fun theChoiceRowOrderMatchesTheEnumItIndexes() {
        val options = ConnectionSettingsSection.phoneTypeOptions(context)
        assertEquals(PhoneTypePreference.entries.size, options.size)
        assertEquals(context.getString(R.string.settings_phone_type_option_auto), options[0])
        assertEquals(context.getString(R.string.settings_phone_type_option_carplay), options[1])
        assertEquals(context.getString(R.string.settings_phone_type_option_android), options[2])
        // Only the Android option is experimental, and it says so in its own label.
        assertFalse(options[0].contains("experimental"))
        assertFalse(options[1].contains("experimental"))
        assertTrue(options[2].contains("experimental"))
    }

    private fun device(vendorId: Int, productId: Int = 0x12a8): UsbDevice {
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(vendorId)
        `when`(device.productId).thenReturn(productId)
        `when`(device.interfaceCount).thenReturn(0)
        return device
    }

    private companion object {
        const val IPHONE_VENDOR_ID = 0x05ac
        const val ANDROID_VENDOR_ID = 0x18d1
    }
}
