package com.shilapi.xcertplay.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The head unit half of the wireless bootstrap, driven against a simulated phone.
 *
 * These tests are the reason [AawBootstrap] has no timers: every step is driven by an incoming frame,
 * so the whole exchange runs on an in-memory pipe in a few milliseconds instead of depending on a
 * real Bluetooth link.
 */
class AawBootstrapTest {

    @Test
    fun thePhoneJoinsAndTheBootstrapReportsReady() {
        val rig = AawRig({ AawTestPhone(it) })
        val outcome = rig.bootstrap.run()
        rig.phoneThread.join(5_000)
        rig.assertPhoneHealthy()

        assertTrue("expected Ready, got $outcome", outcome is AawOutcome.Ready)
        assertEquals(AawBootstrap.State.READY, rig.bootstrap.state)

        // The phone learned where to find the AAP server and what to join.
        assertEquals("192.168.43.1", rig.phone.startRequestIp)
        assertEquals(5277, rig.phone.startRequestPort)
        assertEquals("DiPlay-AA", rig.phone.ssid)
        assertEquals("carplay0001", rig.phone.password)
        assertEquals("02:11:22:33:44:55", rig.phone.bssid)
        assertEquals(AawWpp.SecurityMode.WPA2_PERSONAL, rig.phone.securityMode)
    }

    @Test
    fun theCredentialsAreSentEvenWhenThePhoneNeverAsksForThem() {
        // A phone that skips WifiInfoRequest would otherwise stall until the caller's deadline.
        val rig = AawRig({ AawTestPhone(it, asksForCredentials = false) })
        val outcome = rig.bootstrap.run()
        rig.phoneThread.join(5_000)
        rig.assertPhoneHealthy()

        assertTrue("expected Ready, got $outcome", outcome is AawOutcome.Ready)
        assertEquals("DiPlay-AA", rig.phone.ssid)
        assertEquals(1, rig.phone.infoResponseCount)
    }

    @Test
    fun aCredentialsRequestIsAnsweredAgain() {
        // Asking twice must not leave the phone without credentials: the second answer is a re-send.
        val rig = AawRig({ AawTestPhone(it) })
        rig.bootstrap.run()
        rig.phoneThread.join(5_000)
        rig.assertPhoneHealthy()
        assertTrue(rig.phone.infoResponseCount >= 1)
    }

    @Test
    fun aPhoneThatCannotJoinIsReportedWithItsOwnStatus() {
        val rig = AawRig({ AawTestPhone(it, joinStatus = AawWpp.Status.PHONE_WIFI_DISABLED) })
        val outcome = rig.bootstrap.run()
        rig.phoneThread.join(5_000)
        rig.assertPhoneHealthy()

        assertTrue("expected Failed, got $outcome", outcome is AawOutcome.Failed)
        val failed = outcome as AawOutcome.Failed
        assertEquals(AawWpp.Status.PHONE_WIFI_DISABLED, failed.status)
        assertEquals(AawBootstrap.State.FAILED, rig.bootstrap.state)
    }

    @Test
    fun aPhoneThatRefusesTheEndpointFailsBeforeTryingToJoin() {
        val rig = AawRig({ AawTestPhone(it, startStatus = AawWpp.Status.INVALID_HOST) })
        val outcome = rig.bootstrap.run()
        rig.phoneThread.join(5_000)
        rig.assertPhoneHealthy()

        assertTrue("expected Failed, got $outcome", outcome is AawOutcome.Failed)
        assertEquals(AawWpp.Status.INVALID_HOST, (outcome as AawOutcome.Failed).status)
    }

    @Test
    fun aPingIsAnsweredWhileTheBootstrapIsStillWaiting() {
        // The phone pings every couple of seconds while it joins; a head unit that only answers
        // after the join has already lost the link.
        val rig = AawRig({ AawTestPhone(it, pingOnce = true) })
        val outcome = rig.bootstrap.run()
        rig.phoneThread.join(5_000)
        rig.assertPhoneHealthy()

        assertTrue("expected Ready, got $outcome", outcome is AawOutcome.Ready)
        // The phone sent the ping; the head unit's answer is what the transcript records.
        assertTrue(rig.bootstrap.transcript().contains("WifiPingResponse"))
    }

    @Test
    fun aPhoneThatHangsUpFailsInsteadOfHanging() {
        val rig = AawRig({ AawTestPhone(it, hangUpImmediately = true) })
        val outcome = rig.bootstrap.run()
        rig.phoneThread.join(5_000)

        assertTrue("expected Failed, got $outcome", outcome is AawOutcome.Failed)
        assertTrue((outcome as AawOutcome.Failed).reason.contains("closed"))
        assertEquals(AawBootstrap.State.FAILED, rig.bootstrap.state)
    }

    @Test
    fun thePassphraseNeverReachesTheTranscript() {
        val rig = AawRig({ AawTestPhone(it) })
        rig.bootstrap.run()
        rig.phoneThread.join(5_000)
        rig.assertPhoneHealthy()
        assertTrue(
            "the transcript is shown on screen and pasted into bug reports",
            !rig.bootstrap.transcript().contains("carplay0001"),
        )
    }

    @Test
    fun anOpenAccessPointCannotBeBuilt() {
        // AARE refuses an open network outright, so this is a hard requirement of the phone side.
        try {
            AawAccessPoint(ssid = "DiPlay-AA", password = "")
            fail("an access point with no passphrase must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("passphrase"))
        }
    }

    @Test
    fun anEndpointWithoutAnAddressCannotBeBuilt() {
        try {
            AawServerEndpoint(ipAddress = "", port = 5277)
            fail("the phone needs an address to connect back to")
        } catch (expected: IllegalArgumentException) {
            assertNull(null)
        }
    }
}
