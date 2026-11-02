package com.shilapi.xcertplay.carlife

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the wiring against Baidu's own head unit.
 *
 * These numbers come from `CConnectManager.h` in `ApolloAuto/apollo-DuerOS` (`ADB_FORWARD_*` and
 * `*_SOCKET_PORT_*`). They are the whole contract for reaching the phone: a wrong port does not produce
 * an error, it produces a connect timeout, so they are asserted rather than trusted.
 */
class CarLifeChannelTest {

    @Test
    fun portPairsMatchTheReferenceHeadUnit() {
        val expected = mapOf(
            CarLifeChannel.CMD to (7200 to 7240),
            CarLifeChannel.VIDEO to (8200 to 8240),
            CarLifeChannel.MEDIA to (9200 to 9240),
            CarLifeChannel.TTS to (9201 to 9241),
            CarLifeChannel.VR to (9202 to 9242),
            CarLifeChannel.CTRL to (9300 to 9340),
        )
        assertEquals(CarLifeChannel.entries.toSet(), expected.keys)
        expected.forEach { (channel, ports) ->
            assertEquals(channel.name, ports.first, channel.huPort)
            assertEquals(channel.name, ports.second, channel.mdPort)
        }
    }

    @Test
    fun forwardCommandsAreTheOnesBaiduRuns() {
        assertEquals("adb forward tcp:7200 tcp:7240", CarLifeChannel.CMD.forwardCommand)
        assertEquals("adb forward tcp:8200 tcp:8240", CarLifeChannel.VIDEO.forwardCommand)
        assertEquals("adb forward tcp:9200 tcp:9240", CarLifeChannel.MEDIA.forwardCommand)
        assertEquals("adb forward tcp:9201 tcp:9241", CarLifeChannel.TTS.forwardCommand)
        assertEquals("adb forward tcp:9202 tcp:9242", CarLifeChannel.VR.forwardCommand)
        assertEquals("adb forward tcp:9300 tcp:9340", CarLifeChannel.CTRL.forwardCommand)
    }

    @Test
    fun adbServiceIsThePhoneSidePortBecauseForwardReducesToAnOpen() {
        // `adb forward tcp:7200 tcp:7240` is exactly "open a listener that OPENs tcp:7240 on the device",
        // so DiPlay asks adbd for the far end directly and never binds a host port.
        CarLifeChannel.entries.forEach { channel ->
            assertEquals("tcp:${channel.mdPort}", channel.adbService)
        }
    }

    @Test
    fun everyChannelHasRoomForItsLargestDocumentedMessage() {
        // Baidu's limits: cmd 40 KiB, video 600 KiB, media 100 KiB, tts/vr 50 KiB, ctrl 10 KiB.
        assertEquals(40 * 1024, CarLifeChannel.CMD.maxPayload)
        assertEquals(600 * 1024, CarLifeChannel.VIDEO.maxPayload)
        assertEquals(100 * 1024, CarLifeChannel.MEDIA.maxPayload)
        assertEquals(50 * 1024, CarLifeChannel.TTS.maxPayload)
        assertEquals(50 * 1024, CarLifeChannel.VR.maxPayload)
        assertEquals(10 * 1024, CarLifeChannel.CTRL.maxPayload)
    }
}
