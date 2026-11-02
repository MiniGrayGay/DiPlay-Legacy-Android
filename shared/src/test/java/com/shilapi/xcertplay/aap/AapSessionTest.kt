package com.shilapi.xcertplay.aap

import com.shilapi.xcertplay.aap.phone.AapPhoneSession
import com.shilapi.xcertplay.proto.ProtoWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The head unit and the phone halves of the protocol, wired together and driven through a complete
 * session.
 *
 * These tests are the reason the phone side lives in the product module rather than in the test
 * sources: a mock of the peer would agree with whatever the head unit does, and the bugs that
 * matter here are exactly the places where two independent implementations disagree.
 */
class AapSessionTest {

    @Test
    fun aFreshSessionReachesRunningOnBothEnds() {
        AapTestRig().connect().use { rig ->
            assertEquals(AapSession.State.RUNNING, rig.headUnit.state)
            assertEquals(AapPhoneSession.State.RUNNING, rig.phone.state)
        }
    }

    @Test
    fun theVersionExchangeIsRecordedOnBothSides() {
        AapTestRig().connect().use { rig ->
            assertEquals("1.7", rig.headUnit.phoneVersion)
            assertNotNull(rig.headUnit.phoneFingerprint)
            assertNotNull(rig.phone.headUnitFingerprint)
        }
    }

    @Test
    fun thePhoneLearnsItsNameFromTheServiceDiscoveryRequest() {
        AapTestRig().connect().use { rig ->
            val description = rig.headUnit.phoneDescription
            assertNotNull(description)
            // label_text comes first in the UI, device_name is the fallback.
            assertEquals("Test phone", description!!.display)
            assertEquals("Pixel test", description.deviceName)
        }
    }

    @Test
    fun theServiceListCarriesTheVideoAndTouchGeometry() {
        AapTestRig().connect().use { rig ->
            val discovery = rig.phoneListener.discovery
            assertNotNull(discovery)
            val services = discovery!!.services

            val video = services.first { it.kind == AapServiceDiscovery.Kind.VIDEO }
            assertEquals(1, video.channel)
            assertEquals(AapResolution.P800x480, video.videoConfigs.single().resolution)

            val input = services.first { it.kind == AapServiceDiscovery.Kind.INPUT }
            assertEquals(800, input.touchWidth)
            assertEquals(480, input.touchHeight)

            val audio = services.filter { it.kind == AapServiceDiscovery.Kind.AUDIO }
            assertEquals(1, audio.size)
            assertEquals(AapAudioStreamType.MEDIA, audio.single().audioType)
            assertEquals(48_000, audio.single().audioConfigs.single().sampleRate)

            val sensors = services.first { it.kind == AapServiceDiscovery.Kind.SENSOR }
            assertTrue(sensors.sensors.contains(AapSensorType.NIGHT_MODE))
        }
    }

    @Test
    fun everyAdvertisedChannelIsOpened() {
        AapTestRig().connect().use { rig ->
            val expected = rig.services.channels().toSet()
            // The phone only counts a channel open once the head unit answered it, so this asserts
            // both directions of the handshake at once.
            val opened = rig.phoneListener.opened.toSet()
            assertEquals(expected, opened)
        }
    }

    @Test
    fun aTouchArrivesAtThePhoneWithItsCoordinates() {
        AapTestRig().connect().use { rig ->
            rig.headUnit.sendTouch(timestampMicros = 1_234_567, x = 321, y = 123, action = AapInputMessages.Action.DOWN)

            val message = rig.awaitPhoneMessages(1).single()
            assertEquals(rig.services.inputChannel, message.channel)
            assertEquals(AapMessageIds.Input.INPUT_REPORT, message.id)

            val touch = touchFrom(message.body)
            assertEquals(1_234_567L, touch.timestamp)
            assertEquals(321, touch.x)
            assertEquals(123, touch.y)
            assertEquals(AapInputMessages.Action.DOWN, touch.action)
        }
    }

    @Test
    fun nightModeArrivesOnTheSensorChannel() {
        AapTestRig().connect().use { rig ->
            rig.headUnit.sendNightMode(true)

            val message = rig.awaitPhoneMessages(1).single()
            assertEquals(rig.services.sensorChannel, message.channel)
            assertEquals(AapMessageIds.Sensor.BATCH, message.id)
            // SensorBatch { NightModeData night_mode_data = 10 { bool night_mode = 1 } }
            val reader = ProtoWire.Reader(message.body)
            var night: Boolean? = null
            while (true) {
                val field = reader.next() ?: break
                if (field.number != 10) continue
                val inner = ProtoWire.Reader(field.bytesValue ?: continue)
                while (true) {
                    val next = inner.next() ?: break
                    if (next.number == 1) night = next.intValue != 0L
                }
            }
            assertEquals(true, night)
        }
    }

    @Test
    fun aVideoFocusNotificationCarriesTheMode() {
        AapTestRig().connect().use { rig ->
            rig.headUnit.sendVideoFocus(AapMediaMessages.VideoFocusMode.PROJECTED)

            val message = rig.awaitPhoneMessages(1).single()
            assertEquals(rig.services.videoChannel, message.channel)
            assertEquals(AapMessageIds.Media.VIDEO_FOCUS_NOTIFICATION, message.id)
            assertTrue(AapMediaMessages.decodeVideoFocusNotification(message.body).projected)
        }
    }

    @Test
    fun closingTheHeadUnitEndsThePhoneSession() {
        val rig = AapTestRig().connect()
        rig.headUnit.close()

        repeat(100) {
            if (!rig.phone.isOpen) return@repeat
            Thread.sleep(20)
        }
        assertTrue("phone session should have ended", !rig.phone.isOpen)
        rig.close()
    }

    @Test
    fun aVideoFrameTooBigForOneWireFrameArrivesIntact() {
        AapTestRig().connect().use { rig ->
            // A keyframe at 800x480 regularly lands around this size, which is past the 16 KiB a
            // single frame carries. The phone has to seal it as several records inside one message;
            // if it were sent as several messages the head unit would decode fragments.
            val frame = ByteArray(40_000) { (it * 31 % 251).toByte() }
            rig.phone.send(rig.services.videoChannel, AapMessageIds.Media.DATA, frame)

            val message = rig.awaitHeadUnitMessages(1).single()
            assertEquals(rig.services.videoChannel, message.channel)
            assertEquals(AapMessageIds.Media.DATA, message.id)
            assertEquals(frame.size, message.body.size)
            assertArrayEquals(frame, message.body)
        }
    }

    private class Touch(val timestamp: Long, val x: Int, val y: Int, val action: Int)

    /** Pulls the single pointer out of an `InputReport`. */
    private fun touchFrom(body: ByteArray): Touch {
        var timestamp = 0L
        var x = 0
        var y = 0
        var action = -1
        var actionIndex = 0
        val reader = ProtoWire.Reader(body)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> timestamp = field.intValue
                3 -> {
                    val touch = ProtoWire.Reader(field.bytesValue ?: continue)
                    while (true) {
                        val next = touch.next() ?: break
                        when (next.number) {
                            1 -> {
                                val pointer = ProtoWire.Reader(next.bytesValue ?: continue)
                                while (true) {
                                    val p = pointer.next() ?: break
                                    when (p.number) {
                                        1 -> x = p.asInt()
                                        2 -> y = p.asInt()
                                    }
                                }
                            }

                            2 -> actionIndex = next.asInt()
                            3 -> action = next.asInt()
                        }
                    }
                }
            }
        }
        assertEquals("action_index is always 0 for a single pointer", 0, actionIndex)
        return Touch(timestamp, x, y, action)
    }
}
