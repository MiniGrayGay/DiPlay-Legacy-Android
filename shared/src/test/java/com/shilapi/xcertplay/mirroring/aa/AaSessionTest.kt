package com.shilapi.xcertplay.mirroring.aa

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives a complete sender/receiver conversation over an in-memory pipe. This is the same code path
 * the two emulators exercise, minus the socket and MediaCodec, so a protocol regression fails here
 * in milliseconds instead of needing two devices.
 */
class AaSessionTest {

    private val threads = ArrayList<Thread>()

    @After
    fun stopThreads() {
        for (thread in threads) thread.interrupt()
    }

    @Test(timeout = 10_000)
    fun `handshake establishes both ends`() {
        val harness = Harness()
        harness.start()
        assertTrue(harness.receiverEstablished.await(5, TimeUnit.SECONDS))
        assertTrue(harness.senderEstablished.await(5, TimeUnit.SECONDS))
        assertEquals(640, harness.receiver.hello!!.width)
        assertEquals(360, harness.receiver.hello!!.height)
        assertEquals(AaSessionState.STREAMING, harness.receiverSession.state)
        assertEquals(AaSessionState.STREAMING, harness.senderSession.state)
        assertEquals("DiPlay", harness.sender.ack!!.receiverName)
    }

    @Test(timeout = 10_000)
    fun `a refusal reaches the phone with its reason`() {
        val harness = Harness(accept = { "receiver busy" })
        harness.start()
        assertTrue(harness.sender.closedLatch.await(5, TimeUnit.SECONDS))
        val reason = harness.sender.closedReason
        assertNotNull(reason)
        assertTrue("unexpected reason: $reason", reason!!.contains("receiver busy"))
        // The phone closes the moment it reads the refusal; the receiver closes a tick later.
        assertTrue(harness.receiver.closedLatch.await(5, TimeUnit.SECONDS))
        assertEquals(AaSessionState.CLOSED, harness.senderSession.state)
        assertEquals(AaSessionState.CLOSED, harness.receiverSession.state)
    }

    @Test(timeout = 10_000)
    fun `video config and frames reach the receiver`() {
        val harness = Harness()
        harness.start()
        assertTrue(harness.receiverEstablished.await(5, TimeUnit.SECONDS))
        harness.senderSession.sendConfig(AaWire.Codec.H264, byteArrayOf(0, 0, 1, 0x67.toByte()))
        harness.senderSession.sendVideo(AaWire.Codec.H264, 42L, byteArrayOf(0, 0, 1, 0x65))
        assertTrue(harness.receiver.frameLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, harness.receiver.configs.size)
        assertEquals(42L, harness.receiver.framePts)
        assertEquals(1, harness.receiverSession.videoFramesReceived)
    }

    @Test(timeout = 10_000)
    fun `touch events reach the phone`() {
        val harness = Harness()
        harness.start()
        assertTrue(harness.senderEstablished.await(5, TimeUnit.SECONDS))
        harness.receiverSession.sendTouch(AaMessages.Touch(AaWire.TouchAction.DOWN, 0, 0.5f, 0.5f))
        assertTrue(harness.sender.touchLatch.await(5, TimeUnit.SECONDS))
        assertEquals(0.5f, harness.sender.touch!!.x, 1e-6f)
    }

    @Test(timeout = 10_000)
    fun `ping is echoed and timed`() {
        val harness = Harness()
        harness.start()
        assertTrue(harness.receiverEstablished.await(5, TimeUnit.SECONDS))
        harness.receiverSession.ping()
        assertTrue(harness.receiver.roundTripLatch.await(5, TimeUnit.SECONDS))
        assertTrue(harness.receiver.roundTripUs >= 0L)
    }

    @Test(timeout = 10_000)
    fun `bye closes the peer`() {
        val harness = Harness()
        harness.start()
        assertTrue(harness.receiverEstablished.await(5, TimeUnit.SECONDS))
        harness.senderSession.bye("done")
        assertTrue(harness.receiver.closedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(harness.receiver.closedReason!!.contains("done"))
    }

    @Test(timeout = 10_000)
    fun `stats reach the receiver`() {
        val harness = Harness()
        harness.start()
        assertTrue(harness.receiverEstablished.await(5, TimeUnit.SECONDS))
        harness.senderSession.sendStats(
            AaMessages.Stats(encodedFrames = 10, droppedFrames = 1, lastFrameBytes = 900, keyFrames = 2, width = 640, height = 360),
        )
        assertTrue(harness.receiver.statsLatch.await(5, TimeUnit.SECONDS))
        assertEquals(10L, harness.receiver.stats!!.encodedFrames)
    }

    private inner class Harness(accept: (AaMessages.Hello) -> String? = { null }) {
        val aToB = Pipe()
        val bToA = Pipe()

        val receiver = Recorder()
        val sender = Recorder()
        val receiverEstablished = CountDownLatch(1)
        val senderEstablished = CountDownLatch(1)

        val receiverSession = AaSession(
            duplex = PipeDuplex(out = bToA, input = aToB),
            role = AaSession.Role.RECEIVER,
            listener = receiver,
            receiverName = "DiPlay",
            acceptHello = accept,
        )

        val senderSession = AaSession(
            duplex = PipeDuplex(out = aToB, input = bToA),
            role = AaSession.Role.SENDER,
            listener = sender,
            hello = AaMessages.Hello(width = 640, height = 360, densityDpi = 160, codec = AaWire.Codec.H264, deviceName = "emu"),
        )

        init {
            receiver.established = { receiverEstablished.countDown() }
            sender.established = { senderEstablished.countDown() }
        }

        fun start() {
            threads += Thread({ receiverSession.run() }, "receiver").apply { isDaemon = true }
            threads += Thread({ senderSession.run() }, "sender").apply { isDaemon = true }
            threads.forEach { it.start() }
        }
    }

    private class Recorder : AaSession.Listener {
        var established: (() -> Unit)? = null
        @Volatile var hello: AaMessages.Hello? = null
        @Volatile var ack: AaMessages.HelloAck? = null
        val configs = CopyOnWriteArrayList<ByteArray>()
        @Volatile var framePts: Long = -1
        @Volatile var touch: AaMessages.Touch? = null
        @Volatile var stats: AaMessages.Stats? = null
        @Volatile var roundTripUs: Long = -1
        @Volatile var closedReason: String? = null

        val frameLatch = CountDownLatch(1)
        val touchLatch = CountDownLatch(1)
        val statsLatch = CountDownLatch(1)
        val roundTripLatch = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)

        override fun onEstablished(hello: AaMessages.Hello, ack: AaMessages.HelloAck?) {
            this.hello = hello
            this.ack = ack
            established?.invoke()
        }

        override fun onConfig(codec: Int, payload: ByteArray) {
            configs.add(payload)
        }

        override fun onVideo(codec: Int, ptsUs: Long, payload: ByteArray) {
            framePts = ptsUs
            frameLatch.countDown()
        }

        override fun onTouch(touch: AaMessages.Touch) {
            this.touch = touch
            touchLatch.countDown()
        }

        override fun onStats(stats: AaMessages.Stats) {
            this.stats = stats
            statsLatch.countDown()
        }

        override fun onRoundTripUs(micros: Long) {
            roundTripUs = micros
            roundTripLatch.countDown()
        }

        override fun onClosed(reason: String) {
            closedReason = reason
            closedLatch.countDown()
        }
    }

    private class PipeDuplex(private val out: Pipe, private val input: Pipe) : AaDuplex {
        override fun read(buffer: ByteArray): Int = input.read(buffer)
        override fun write(bytes: ByteArray) = out.write(bytes)

        override fun close() {
            out.close()
            input.close()
        }
    }

    private class Pipe {
        private val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false
        private var pending: ByteArray? = null
        private var offset = 0

        fun write(bytes: ByteArray) {
            if (closed) throw IOException("pipe closed")
            queue.offer(bytes)
        }

        fun read(into: ByteArray): Int {
            while (true) {
                val current = pending
                if (current != null) {
                    val count = minOf(into.size, current.size - offset)
                    System.arraycopy(current, offset, into, 0, count)
                    offset += count
                    if (offset >= current.size) {
                        pending = null
                        offset = 0
                    }
                    return count
                }
                if (closed && queue.isEmpty()) return -1
                pending = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
            }
        }

        fun close() {
            closed = true
        }
    }
}
