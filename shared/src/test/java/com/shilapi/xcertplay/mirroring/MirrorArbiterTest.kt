package com.shilapi.xcertplay.mirroring

import com.shilapi.xcertplay.mirroring.MirrorArbiterEffect.ReleaseAll
import com.shilapi.xcertplay.mirroring.MirrorArbiterEffect.StartMode
import com.shilapi.xcertplay.mirroring.MirrorArbiterEffect.StopMode
import com.shilapi.xcertplay.mirroring.MirrorArbiterEvent.ModeFailed
import com.shilapi.xcertplay.mirroring.MirrorArbiterEvent.ModeStarted
import com.shilapi.xcertplay.mirroring.MirrorArbiterEvent.PhoneAttached
import com.shilapi.xcertplay.mirroring.MirrorArbiterEvent.PhoneDetached
import com.shilapi.xcertplay.mirroring.MirrorArbiterEvent.SwitchTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorArbiterTest {

    @Test
    fun `idle attach starts carplay`() {
        val arbiter = MirrorArbiter()
        assertEquals(listOf(StartMode(MirrorMode.CARPLAY)), arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY)))
        assertEquals(MirrorArbiterState.SWITCHING, arbiter.state)
        assertEquals(MirrorMode.CARPLAY, arbiter.target)
    }

    @Test
    fun `confirmation promotes the target to owner`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY))
        assertEquals(emptyList<MirrorArbiterEffect>(), arbiter.onEvent(ModeStarted(MirrorMode.CARPLAY)))
        assertEquals(MirrorArbiterState.CARPLAY_RUNNING, arbiter.state)
        assertEquals(MirrorMode.CARPLAY, arbiter.owner)
        assertEquals(MirrorMode.NONE, arbiter.target)
    }

    @Test
    fun `attaching the other mode stops the owner first`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY))
        arbiter.onEvent(ModeStarted(MirrorMode.CARPLAY))
        assertEquals(
            listOf(StopMode(MirrorMode.CARPLAY), StartMode(MirrorMode.ANDROID_AUTO)),
            arbiter.onEvent(PhoneAttached(MirrorMode.ANDROID_AUTO)),
        )
        assertEquals(MirrorArbiterState.SWITCHING, arbiter.state)
        arbiter.onEvent(ModeStarted(MirrorMode.ANDROID_AUTO))
        assertEquals(MirrorArbiterState.ANDROID_RUNNING, arbiter.state)
        assertEquals(MirrorMode.ANDROID_AUTO, arbiter.owner)
    }

    @Test
    fun `same phone attached twice is ignored`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY))
        assertEquals(emptyList<MirrorArbiterEffect>(), arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY)))
        arbiter.onEvent(ModeStarted(MirrorMode.CARPLAY))
        assertEquals(emptyList<MirrorArbiterEffect>(), arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY)))
    }

    @Test
    fun `detaching the owner stops it and returns to idle`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY))
        arbiter.onEvent(ModeStarted(MirrorMode.CARPLAY))
        assertEquals(listOf(StopMode(MirrorMode.CARPLAY)), arbiter.onEvent(PhoneDetached(MirrorMode.CARPLAY)))
        assertEquals(MirrorArbiterState.IDLE, arbiter.state)
        assertEquals(MirrorMode.NONE, arbiter.owner)
    }

    @Test
    fun `detaching a phone that does not own anything is ignored`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY))
        arbiter.onEvent(ModeStarted(MirrorMode.CARPLAY))
        assertEquals(emptyList<MirrorArbiterEffect>(), arbiter.onEvent(PhoneDetached(MirrorMode.ANDROID_AUTO)))
        assertEquals(MirrorMode.CARPLAY, arbiter.owner)
    }

    @Test
    fun `detaching the swap target releases everything`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY))
        arbiter.onEvent(ModeStarted(MirrorMode.CARPLAY))
        arbiter.onEvent(PhoneAttached(MirrorMode.ANDROID_AUTO))
        assertEquals(listOf(ReleaseAll), arbiter.onEvent(PhoneDetached(MirrorMode.ANDROID_AUTO)))
        assertEquals(MirrorArbiterState.IDLE, arbiter.state)
        assertEquals(MirrorMode.NONE, arbiter.owner)
    }

    @Test
    fun `a third attach during a swap releases instead of stacking stops`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY))
        arbiter.onEvent(ModeStarted(MirrorMode.CARPLAY))
        arbiter.onEvent(PhoneAttached(MirrorMode.ANDROID_AUTO))
        assertEquals(
            listOf(ReleaseAll, StartMode(MirrorMode.CARPLAY)),
            arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY)),
        )
        assertEquals(MirrorMode.CARPLAY, arbiter.target)
    }

    @Test
    fun `timeout while switching releases and counts the failure`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.ANDROID_AUTO))
        assertEquals(listOf(ReleaseAll), arbiter.onEvent(SwitchTimeout))
        assertEquals(MirrorArbiterState.IDLE, arbiter.state)
        assertEquals(1, arbiter.failedAttempts)
    }

    @Test
    fun `timeout while idle does nothing`() {
        val arbiter = MirrorArbiter()
        assertEquals(emptyList<MirrorArbiterEffect>(), arbiter.onEvent(SwitchTimeout))
        assertEquals(0, arbiter.failedAttempts)
    }

    @Test
    fun `failed start while switching releases everything`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.ANDROID_AUTO))
        assertEquals(listOf(ReleaseAll), arbiter.onEvent(ModeFailed(MirrorMode.ANDROID_AUTO, "no usb")))
        assertEquals(MirrorArbiterState.IDLE, arbiter.state)
        assertEquals(MirrorMode.NONE, arbiter.target)
    }

    @Test
    fun `failure of the running mode stops it and clears resources`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY))
        arbiter.onEvent(ModeStarted(MirrorMode.CARPLAY))
        assertEquals(
            listOf(StopMode(MirrorMode.CARPLAY), ReleaseAll),
            arbiter.onEvent(ModeFailed(MirrorMode.CARPLAY, "controller died")),
        )
        assertEquals(MirrorArbiterState.IDLE, arbiter.state)
    }

    @Test
    fun `a late confirmation of an abandoned mode is stopped`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY))
        arbiter.onEvent(PhoneAttached(MirrorMode.ANDROID_AUTO))
        // CarPlay boots slowly and confirms after the swap already began.
        assertEquals(listOf(StopMode(MirrorMode.CARPLAY)), arbiter.onEvent(ModeStarted(MirrorMode.CARPLAY)))
        assertEquals(MirrorMode.ANDROID_AUTO, arbiter.target)
        assertEquals(MirrorArbiterState.SWITCHING, arbiter.state)
    }

    @Test
    fun `accepts only lets a new mode in`() {
        val arbiter = MirrorArbiter()
        assertTrue(arbiter.accepts(MirrorMode.CARPLAY))
        assertFalse(arbiter.accepts(MirrorMode.NONE))
        arbiter.onEvent(PhoneAttached(MirrorMode.CARPLAY))
        arbiter.onEvent(ModeStarted(MirrorMode.CARPLAY))
        assertFalse(arbiter.accepts(MirrorMode.CARPLAY))
        assertTrue(arbiter.accepts(MirrorMode.ANDROID_AUTO))
    }

    @Test
    fun `successful start clears the failure counter`() {
        val arbiter = MirrorArbiter()
        arbiter.onEvent(PhoneAttached(MirrorMode.ANDROID_AUTO))
        arbiter.onEvent(ModeFailed(MirrorMode.ANDROID_AUTO, "boom"))
        assertEquals(1, arbiter.failedAttempts)
        arbiter.onEvent(PhoneAttached(MirrorMode.ANDROID_AUTO))
        arbiter.onEvent(ModeStarted(MirrorMode.ANDROID_AUTO))
        assertEquals(0, arbiter.failedAttempts)
    }
}
