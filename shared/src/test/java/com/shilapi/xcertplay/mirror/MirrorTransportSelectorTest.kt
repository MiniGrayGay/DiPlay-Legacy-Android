package com.shilapi.xcertplay.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorTransportSelectorTest {
    private val iphone = AttachedPhone("/dev/bus/usb/001/002", PhoneKind.IPHONE)
    private val android = AttachedPhone("/dev/bus/usb/001/003", PhoneKind.ANDROID)
    private val android2 = AttachedPhone("/dev/bus/usb/001/005", PhoneKind.ANDROID)
    private val noDebugging = AttachedPhone("/dev/bus/usb/001/004", PhoneKind.ANDROID_DEBUGGING_OFF)

    @Test
    fun nothingAttachedWaits() {
        assertEquals(MirrorDecision.Wait, MirrorTransportSelector.decide(emptyList()))
    }

    @Test
    fun iphoneStartsCarPlayAndAndroidStartsMirroring() {
        assertEquals(MirrorDecision.StartCarPlay(iphone), MirrorTransportSelector.decide(listOf(iphone)))
        assertEquals(MirrorDecision.StartAndroidMirror(android), MirrorTransportSelector.decide(listOf(android)))
    }

    @Test
    fun bothPhonesKeepCarPlayUnlessAndroidIsPreferred() {
        val both = listOf(android, iphone)
        assertEquals(MirrorDecision.StartCarPlay(iphone), MirrorTransportSelector.decide(both))
        assertEquals(
            MirrorDecision.StartAndroidMirror(android),
            MirrorTransportSelector.decide(both, prefer = PhoneKind.ANDROID),
        )
    }

    @Test
    fun theFirstAttachedAndroidPhoneWins() {
        assertEquals(MirrorDecision.StartAndroidMirror(android), MirrorTransportSelector.decide(listOf(android, android2)))
    }

    @Test
    fun androidWithoutDebuggingAsksOnlyWhenNothingElseIsReady() {
        assertEquals(MirrorDecision.AskEnableUsbDebugging(noDebugging), MirrorTransportSelector.decide(listOf(noDebugging)))
        assertEquals(MirrorDecision.StartCarPlay(iphone), MirrorTransportSelector.decide(listOf(noDebugging, iphone)))
        assertEquals(MirrorDecision.StartAndroidMirror(android), MirrorTransportSelector.decide(listOf(noDebugging, android)))
    }

    @Test
    fun theDriversPreferenceFiltersThePhones() {
        val all = listOf(iphone, android, noDebugging)
        assertEquals(
            MirrorDecision.StartCarPlay(iphone),
            MirrorTransportSelector.decide(all, MirrorPreference.CARPLAY_ONLY, prefer = PhoneKind.ANDROID),
        )
        assertEquals(
            MirrorDecision.StartAndroidMirror(android),
            MirrorTransportSelector.decide(all, MirrorPreference.ANDROID_ONLY),
        )
        assertEquals(MirrorDecision.Wait, MirrorTransportSelector.decide(listOf(android), MirrorPreference.CARPLAY_ONLY))
        assertEquals(MirrorDecision.Wait, MirrorTransportSelector.decide(listOf(iphone), MirrorPreference.ANDROID_ONLY))
    }

    @Test
    fun anUnknownDeviceIsIgnored() {
        val hub = AttachedPhone("/dev/bus/usb/001/009", PhoneKind.UNKNOWN)
        assertEquals(MirrorDecision.Wait, MirrorTransportSelector.decide(listOf(hub)))
        assertEquals(MirrorDecision.StartCarPlay(iphone), MirrorTransportSelector.decide(listOf(hub, iphone)))
    }

    @Test
    fun preferMustNameAPhoneKindThatCanStart() {
        val failed = runCatching { MirrorTransportSelector.decide(listOf(iphone), prefer = PhoneKind.UNKNOWN) }
        assertTrue(failed.exceptionOrNull() is IllegalArgumentException)
    }
}
