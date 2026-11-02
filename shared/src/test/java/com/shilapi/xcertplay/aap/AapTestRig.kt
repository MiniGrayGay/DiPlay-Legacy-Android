package com.shilapi.xcertplay.aap

import com.shilapi.xcertplay.aap.phone.AapPhoneSession
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A head unit session wired to a phone session over an in-memory pipe.
 *
 * Both sides are the real implementations: this is not a mock of either. What it does not have is
 * a socket, a USB bus or a clock, so it can assert on exact ordering.
 */
class AapTestRig(
    val services: AapServiceSet = defaultServices(),
    /** Refuse every channel the phone opens, the way a head unit with a full queue would. */
    private val phoneSeesChannels: Boolean = true,
) : Closeable {

    private val pair = LoopbackTransport.pair()
    private val headUnitSide = pair.first
    private val phoneSide = pair.second

    val headUnitLog = CopyOnWriteArrayList<String>()
    val phoneLog = CopyOnWriteArrayList<String>()

    /** Service-channel messages the phone received, in order. */
    val phoneReceived = CopyOnWriteArrayList<AapMessage>()

    /** Service-channel messages the head unit received, in order. */
    val headUnitReceived = CopyOnWriteArrayList<AapMessage>()

    val phoneListener = RecordingPhoneListener()

    /**
     * Records what the head unit received. Needed because the interesting direction for media is
     * the phone's: a frame that has to cross several wire frames is only proved correct if the head
     * unit gets it back byte for byte.
     */
    private val recordingHeadUnitListener = object : AapSession.Listener {
        override fun onServiceMessage(channel: Int, message: AapMessage) {
            headUnitReceived += message
        }
    }

    val headUnit = AapSession(
        transport = headUnitSide,
        identity = AapIdentity.generate("Test head unit"),
        services = services,
        phaseTimeoutMillis = 5_000,
        log = { headUnitLog += it },
    ).apply { listener = recordingHeadUnitListener }

    val phone = AapPhoneSession(
        transport = phoneSide,
        identity = AapIdentity.generate("Test phone"),
        deviceName = "Pixel test",
        label = "Test phone",
        log = { phoneLog += it },
    ).apply { listener = phoneListener }

    private val stopped = AtomicBoolean(false)
    private val phoneThread = Thread({
        phone.connect()
        phone.run()
    }, "test-phone")
    private val headUnitThread = Thread({ headUnit.run() }, "test-head-unit")

    init {
        phoneThread.isDaemon = true
    }

    /** Starts the phone, connects the head unit, and returns once both are running. */
    fun connect(): AapTestRig {
        phoneThread.start()
        val opened = headUnit.connect()
        check(opened) { "head unit failed to connect: ${headUnitLog.lastOrNull()}" }
        headUnitThread.isDaemon = true
        headUnitThread.start()
        check(phoneListener.ready.await(5, TimeUnit.SECONDS)) {
            "phone never reported a discovery\n  phone: ${phoneLog.takeLast(6)}\n  head unit: ${headUnitLog.takeLast(6)}"
        }
        return this
    }

    /** Waits until the head unit has seen [count] service messages. */
    fun awaitHeadUnitMessages(count: Int): List<AapMessage> {
        repeat(100) {
            if (headUnitReceived.size >= count) return headUnitReceived.toList()
            Thread.sleep(20)
        }
        return headUnitReceived.toList()
    }

    /** Waits until the phone has seen [count] service messages. */
    fun awaitPhoneMessages(count: Int): List<AapMessage> {
        repeat(100) {
            if (phoneReceived.size >= count) return phoneReceived.toList()
            Thread.sleep(20)
        }
        return phoneReceived.toList()
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { headUnit.close() }
        runCatching { phone.close() }
        phoneThread.interrupt()
        headUnitThread.interrupt()
    }

    /** Records what the phone saw, so a test can assert on it. */
    inner class RecordingPhoneListener : AapPhoneSession.Listener {
        val ready = CountDownLatch(1)
        var discovery: AapServiceDiscovery.Discovery? = null
        val opened = CopyOnWriteArrayList<Int>()

        override fun onDiscovery(discovery: AapServiceDiscovery.Discovery) {
            this.discovery = discovery
            ready.countDown()
        }

        override fun onChannelOpen(channel: Int, service: AapServiceDiscovery.Service) {
            if (phoneSeesChannels) opened += channel
        }

        override fun onServiceMessage(channel: Int, message: AapMessage) {
            phoneReceived += message
        }
    }

    companion object {
        /** A configuration with one of each service, which is what the app ships. */
        fun defaultServices(): AapServiceSet = AapServiceSet(
            displayName = "DiPlay test",
            video = listOf(AapVideoConfig(AapResolution.P800x480, frameRate = 30, density = 160)),
            audio = listOf(AapAudioStream(AapAudioStreamType.MEDIA)),
            touchscreen = AapTouchScreen(800, 480),
            sensors = listOf(AapSensorType.NIGHT_MODE, AapSensorType.DRIVING_STATUS),
        )
    }
}
