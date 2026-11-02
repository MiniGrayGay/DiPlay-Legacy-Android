package com.shilapi.xcertplay.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbCommandsTest {

    /**
     * `screenrecord` needs the format flag before the output, and `-` must stay last: an argument after
     * it is treated as a filename and the command fails on the phone instead of at compile time.
     */
    @Test
    fun screenRecordStreamsRawH264ToStdout() {
        val command = AdbCommands.screenRecord()
        assertTrue(command.startsWith("screenrecord --output-format=h264"))
        assertTrue(command.endsWith(" -"))
        assertTrue(command.contains("--bit-rate 8000000"))
        assertTrue(command.contains("--time-limit 180"))
    }

    @Test
    fun screenRecordClampsWhatTheEncoderRejects() {
        val command = AdbCommands.screenRecord(bitRate = 1, timeLimitSeconds = 9_000)
        assertTrue(command.contains("--bit-rate 1000000"))
        // Three minutes is screenrecord's own ceiling; asking for more makes it exit immediately.
        assertTrue(command.contains("--time-limit 180"))
    }

    /** An odd width makes the H.264 encoder refuse the configuration. */
    @Test
    fun screenRecordRoundsSizeToEvenPixels() {
        val command = AdbCommands.screenRecord(width = 641, height = 361)
        assertTrue(command.contains("--size 640x360"))
    }

    @Test
    fun sizeIsOmittedUnlessBothDimensionsAreGiven() {
        assertTrue(!AdbCommands.screenRecord(width = 640).contains("--size"))
    }

    @Test
    fun tapAndSwipeUseDisplayPixels() {
        assertEquals("input tap 540 1200", AdbCommands.tap(540, 1200))
        assertEquals("input swipe 100 200 900 200 250", AdbCommands.swipe(100, 200, 900, 200, 250))
    }

    /** A negative coordinate is a bug in the mapping, not something to hand to `input`. */
    @Test
    fun negativeCoordinatesAreClamped() {
        assertEquals("input tap 0 0", AdbCommands.tap(-5, -1))
    }

    @Test
    fun normalisedPointMapsInsideTheVideoRectangle() {
        assertEquals(0 to 0, AdbCommands.toDevice(0f, 0f, 1080, 2340))
        assertEquals(1079 to 2339, AdbCommands.toDevice(1f, 1f, 1080, 2340))
        assertEquals(540 to 1170, AdbCommands.toDevice(0.5f, 0.5f, 1080, 2340))
    }

    /** A single-pixel stream must not divide by zero. */
    @Test
    fun degenerateVideoSizeDoesNotCrash() {
        assertEquals(0 to 0, AdbCommands.toDevice(1f, 1f, 1, 1))
        assertEquals(0 to 0, AdbCommands.toDevice(1f, 1f, 0, 0))
    }
}
