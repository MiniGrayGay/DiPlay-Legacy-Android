package com.shilapi.xcertplay.adb

import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ADB client is the part of Android mode that cannot be exercised by watching a screen, so the
 * protocol is driven here against a scripted adbd: every packet the client must send, in order, and
 * every interleaving it must survive.
 */
class AdbSessionTest {

    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test(timeout = 20_000)
    fun handshakeWithKnownKeyReachesReady() {
        val phone = Phone()
        phone.reply { packet ->
            assertEquals(AdbPacket.CNXN, packet.command)
            phone.cnxn("device::ro.product.model=Pixel 8\u0000")
        }
        assertTrue(phone.session.connect(2_000) == AdbSession.State.READY)
        assertTrue(phone.session.deviceBanner.contains("Pixel 8"))
        phone.close()
    }

    /** A phone that has never seen DiPlay's key signs nothing; it has to be shown the public key. */
    @Test(timeout = 20_000)
    fun unknownKeyIsSignedThenOffered() {
        val phone = Phone()
        phone.reply { first ->
            assertEquals(AdbPacket.CNXN, first.command)
            phone.send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_TOKEN, 0, ByteArray(20) { it.toByte() }))
            val signature = phone.read()
            assertEquals(AdbPacket.AUTH, signature.command)
            assertEquals(AdbPacket.AUTH_SIGNATURE, signature.arg0)
            assertEquals(256, signature.payload.size)
            phone.send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_TOKEN, 0, ByteArray(20)))
            val publicKey = phone.read()
            assertEquals(AdbPacket.AUTH_PUBLIC_KEY, publicKey.arg0)
            assertTrue(String(publicKey.payload, Charsets.UTF_8).endsWith("\u0000"))
            phone.cnxn("device::ro.product.model=Mi 14\u0000")
        }
        assertTrue(phone.session.connect(5_000) == AdbSession.State.READY)
        phone.close()
    }

    @Test(timeout = 20_000)
    fun execReturnsTheCommandsOutput() {
        val phone = Phone()
        phone.reply { packet ->
            assertEquals(AdbPacket.CNXN, packet.command)
            phone.cnxn("device::\u0000")
            val open = phone.read()
            assertEquals(AdbPacket.OPEN, open.command)
            assertEquals("exec:getprop ro.product.model\u0000", String(open.payload, Charsets.UTF_8))
            val local = open.arg0
            phone.send(AdbPacket(AdbPacket.OKAY, 7, local, ByteArray(0)))
            phone.send(AdbPacket(AdbPacket.WRTE, 7, local, "Pixel 8\n".toByteArray()))
            val ack = phone.read()
            assertEquals(AdbPacket.OKAY, ack.command)
            assertEquals(local, ack.arg0)
            assertEquals(7, ack.arg1)
            phone.send(AdbPacket(AdbPacket.CLSE, 7, local, ByteArray(0)))
        }
        phone.session.connect(2_000)
        assertEquals("Pixel 8", phone.session.exec("getprop ro.product.model", 3_000))
        phone.close()
    }

    /** The video pipe must not hold the touch pipe hostage: two streams advance independently. */
    @Test(timeout = 20_000)
    fun twoStreamsInterleaveWithoutMixing() {
        val phone = Phone()
        phone.reply { packet ->
            assertEquals(AdbPacket.CNXN, packet.command)
            phone.cnxn("device::\u0000")
            val videoOpen = phone.read()
            // Each OPEN is answered before the next arrives: the client does not send a second one
            // until the first is granted, exactly as two streams are opened for real.
            phone.send(AdbPacket(AdbPacket.OKAY, 11, videoOpen.arg0, ByteArray(0)))
            val touchOpen = phone.read()
            val video = videoOpen.arg0
            val touch = touchOpen.arg0
            phone.send(AdbPacket(AdbPacket.OKAY, 12, touch, ByteArray(0)))
            repeat(3) { round ->
                phone.send(AdbPacket(AdbPacket.WRTE, 11, video, "v$round".toByteArray()))
                phone.send(AdbPacket(AdbPacket.WRTE, 12, touch, "t$round".toByteArray()))
            }
            repeat(6) { phone.read() } // the client acknowledges each WRTE
            phone.send(AdbPacket(AdbPacket.CLSE, 11, video, ByteArray(0)))
            phone.send(AdbPacket(AdbPacket.CLSE, 12, touch, ByteArray(0)))
        }
        phone.session.connect(2_000)
        val video = phone.session.open("exec:screenrecord --output-format=h264 -", 3_000)
        val touch = phone.session.open("shell:input tap 1 2", 3_000)
        assertNotNull(video)
        assertNotNull(touch)
        assertEquals("v0v1v2", String(video!!.readFully(3_000), Charsets.UTF_8))
        assertEquals("t0t1t2", String(touch!!.readFully(3_000), Charsets.UTF_8))
        video.close()
        touch.close()
        phone.close()
    }

    @Test(timeout = 20_000)
    fun refusedOpenIsNull() {
        val phone = Phone()
        phone.reply { packet ->
            assertEquals(AdbPacket.CNXN, packet.command)
            phone.cnxn("device::\u0000")
            val open = phone.read()
            // adbd answers a service it will not start with CLSE whose remote id is zero.
            phone.send(AdbPacket(AdbPacket.CLSE, 0, open.arg0, ByteArray(0)))
        }
        phone.session.connect(2_000)
        assertNull(phone.session.open("exec:no-such-binary", 2_000))
        phone.close()
    }

    /** Large writes are chunked and each chunk waits for its own acknowledgement. */
    @Test(timeout = 30_000)
    fun writesAreChunkedAndAcknowledged() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val received = ArrayList<Byte>()
        val phone = Phone()
        phone.reply { packet ->
            assertEquals(AdbPacket.CNXN, packet.command)
            phone.cnxn("device::\u0000")
            val open = phone.read()
            val local = open.arg0
            phone.send(AdbPacket(AdbPacket.OKAY, 21, local, ByteArray(0)))
            while (received.size < payload.size) {
                val write = phone.read()
                if (write.command != AdbPacket.WRTE) break
                write.payload.forEach { received.add(it) }
                phone.send(AdbPacket(AdbPacket.OKAY, 21, local, ByteArray(0)))
            }
            phone.send(AdbPacket(AdbPacket.CLSE, 21, local, ByteArray(0)))
        }
        phone.session.connect(2_000)
        val stream = phone.session.open("exec:cat > /dev/null", 3_000)
        assertNotNull(stream)
        stream!!.write(payload)
        assertArrayEquals(payload, received.toByteArray())
        phone.close()
    }

    /**
     * A scripted phone: two byte pipes and a thread that answers the client. Everything the test
     * asserts is checked inside the script, so a wrong packet fails the test rather than hanging it.
     */
    private inner class Phone {
        private val clientToPhone = BytePipe()
        private val phoneToClient = BytePipe()
        private var scriptThread: Thread? = null

        val transport = object : AdbTransport {
            override val input: InputStream = phoneToClient.input
            override val output: OutputStream = clientToPhone.output
            override val peer: String = "fake-adbd"
            override val isOpen: Boolean = true
            override fun close() {
                clientToPhone.close()
                phoneToClient.close()
            }
        }

        val session = AdbSession(transport, key)

        fun reply(script: (AdbPacket) -> Unit) {
            scriptThread = thread(isDaemon = true, name = "fake-adbd") {
                val first = AdbPacket.read(clientToPhone.input)
                script(first)
            }
        }

        fun read(): AdbPacket = AdbPacket.read(clientToPhone.input)

        fun send(packet: AdbPacket) {
            phoneToClient.output.write(packet.encode())
            phoneToClient.output.flush()
        }

        fun cnxn(banner: String) = send(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, 256 * 1024, banner.toByteArray()))

        fun close() {
            session.close()
            scriptThread?.join(1_000)
        }
    }

    /** A one-way byte pipe that ends the stream when nothing arrives for a while. */
    private class BytePipe {
        private val chunks = LinkedBlockingDeque<ByteArray>()
        private var head: ByteArray? = null
        private var offset = 0
        private var closed = false

        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed) return
                chunks.addLast(b.copyOfRange(off, off + len))
            }
        }

        val input = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                var current = head
                if (current == null || offset >= current.size) {
                    val next = chunks.pollFirst(3, TimeUnit.SECONDS)
                    if (next == null) return -1
                    head = next
                    current = next
                    offset = 0
                }
                val count = minOf(len, current.size - offset)
                System.arraycopy(current, offset, b, off, count)
                offset += count
                return count
            }
        }

        fun close() {
            closed = true
            chunks.clear()
        }
    }
}
