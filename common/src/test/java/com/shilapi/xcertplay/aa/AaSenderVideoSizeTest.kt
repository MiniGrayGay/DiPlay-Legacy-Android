package com.shilapi.xcertplay.aa

import com.shilapi.xcertplay.mirroring.aa.AaWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The mirrored video has to keep the phone's shape: a 16:9 video of a 9:16 phone would arrive
 * squashed, and touches would land in the wrong place. [AaSenderActivity.videoSizeFor] is the pure
 * half of that logic, so the maths is checked here without an emulator.
 */
class AaSenderVideoSizeTest {

    private fun aspect(width: Int, height: Int): Float = width.toFloat() / height

    @Test fun portraitPhoneKeepsItsAspect() {
        val (width, height) = AaSenderActivity.videoSizeFor(1080, 1920)
        assertEquals(360, width)
        assertEquals(640, height)
        assertTrue("aspect drifted", abs(aspect(width, height) - aspect(1080, 1920)) < 0.01f)
    }

    @Test fun landscapePhoneKeepsItsAspect() {
        val (width, height) = AaSenderActivity.videoSizeFor(1920, 1080)
        assertEquals(640, width)
        assertEquals(360, height)
        assertTrue(abs(aspect(width, height) - aspect(1920, 1080)) < 0.01f)
    }

    @Test fun oddScreensStillProduceEvenDimensions() {
        // 1440x3120 is a real phone resolution; scaling it lands on fractional pixels.
        val (width, height) = AaSenderActivity.videoSizeFor(1440, 3120)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
        assertEquals(640, maxOf(width, height))
    }

    @Test fun tinyOrBrokenDisplayStillYieldsAUsableSize() {
        val (width, height) = AaSenderActivity.videoSizeFor(0, 0)
        assertTrue(width >= 2)
        assertTrue(height >= 2)
    }

    @Test fun wireActionsMapOntoGestures() {
        assertEquals("down", AaTouchInjector.actionOf(AaWire.TouchAction.DOWN))
        assertEquals("move", AaTouchInjector.actionOf(AaWire.TouchAction.MOVE))
        assertEquals("up", AaTouchInjector.actionOf(AaWire.TouchAction.UP))
        assertEquals(null, AaTouchInjector.actionOf(9))
    }
}
