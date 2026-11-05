package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GuangqiClusterTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun readyIsReportedTheAmapWayToTheClusterServiceOnly() {
        installClusterService()
        val logs = mutableListOf<String>()
        val reporter = GuangqiCluster.ReadyReporter(context, logs::add)

        reporter.update(true)
        reporter.update(true)

        val sent = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
        assertEquals(listOf(GuangqiCluster.STATE_INIT, GuangqiCluster.STATE_FOREGROUND), sent.map { it.getIntExtra("EXTRA_STATE", -1) })
        for (intent in sent) {
            assertEquals("AUTONAVI_STANDARD_BROADCAST_SEND", intent.action)
            assertEquals("gaei.cluster.service", intent.`package`)
            assertEquals(10019, intent.getIntExtra("KEY_TYPE", -1))
        }
        assertTrue(reporter.reported)
        assertEquals(1, logs.size)

        reporter.update(false)
        assertEquals(GuangqiCluster.STATE_EXIT, sent.last().getIntExtra("EXTRA_STATE", -1))
        assertEquals(3, sent.size)
        assertFalse(reporter.reported)
    }

    @Test fun otherHeadUnitsNeverReportReady() {
        val reporter = GuangqiCluster.ReadyReporter(context) {}
        reporter.update(true)
        assertFalse(reporter.reported)
        assertTrue(shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.isEmpty())
    }

    @Test fun guangqiDefaultsLeaveVpuRoomForTheClusterStream() {
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(context))
        assertEquals(CarPlayClusterDisplay.STREAM_SCALE_PERCENT, AirPlayPersistence.loadClusterMapScalePercent(context))
        val upstreamResolution = AirPlayPersistence.loadDisplayScalePercent(context)

        installClusterService()

        assertTrue(AirPlayPersistence.loadClusterMapEnabled(context))
        assertEquals(GuangqiCluster.CLUSTER_SCALE_PERCENT, AirPlayPersistence.loadClusterMapScalePercent(context))
        assertEquals(GuangqiCluster.MAIN_RESOLUTION_PERCENT, AirPlayPersistence.loadDisplayScalePercent(context))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        assertEquals(100, upstreamResolution)

        // The driver's own choices still win.
        AirPlayPersistence.saveDisplayScalePercent(context, 100)
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(context))
    }

    @Test fun clusterStreamIsRequestedAt15FpsAnd856x480() {
        val config = CarPlayClusterDisplay.config(
            1280, 720, GuangqiCluster.CLUSTER_SCALE_PERCENT, fps = GuangqiCluster.CLUSTER_FPS,
        )
        assertEquals(15, config.fps)
        assertEquals(856, config.widthPixels)
        assertEquals(480, config.heightPixels)
        assertEquals(CarPlayClusterDisplay.FPS, CarPlayClusterDisplay.config(1280, 720).fps)
    }

    @Test fun clusterMarkerLandsWhereAmapPutsTheCarOnTheGuangqiCluster() {
        val config = CarPlayClusterDisplay.config(
            1280, 720, GuangqiCluster.CLUSTER_SCALE_PERCENT,
            baseSafeArea = GuangqiCluster.SAFE_AREA_PERCENT, fps = GuangqiCluster.CLUSTER_FPS,
        )
        val safe = config.safeArea!!
        val scale = 1280.0 / config.widthPixels
        // Marker at the centre of the safe area: (640, 480) on the 1280x720 layer.
        assertEquals(640.0, (safe.left + config.widthPixels - safe.right) / 2.0 * scale, 8.0)
        assertEquals(480.0, (safe.top + config.heightPixels - safe.bottom) / 2.0 * scale, 8.0)
        // Inside the area the gauges, clock and trip row leave clear.
        assertTrue(safe.left * scale >= 127 && safe.right * scale >= 127)
        assertTrue(safe.bottom * scale >= 71)
    }

    private fun installClusterService() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = GuangqiCluster.SERVICE_PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = GuangqiCluster.SERVICE_PACKAGE
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
    }
}
