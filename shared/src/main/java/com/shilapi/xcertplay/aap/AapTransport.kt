package com.shilapi.xcertplay.aap

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock

/**
 * The byte pipe under an AAP session.
 *
 * Two implementations exist on a head unit: USB accessory bulk endpoints, and TCP. The TCP one is
 * not a fallback for shipping — it is what makes the protocol testable against a phone simulator
 * and what a wired-to-wireless adapter would use.
 */
interface AapTransport : Closeable {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun flush() {}

    /** Blocking read that either fills the buffer or fails, since frame headers have no slack. */
    fun readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        var done = 0
        while (done < length) {
            val read = read(buffer, offset + done, length - done)
            if (read < 0) throw java.io.EOFException("AAP transport closed with ${length - done} bytes outstanding")
            done += read
        }
    }

    fun writeFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        // Transports either write everything or throw. A socket that accepted only part of the
        // buffer has to deal with that itself: retrying here would duplicate bytes on a transport
        // that already reported success.
        write(buffer, offset, length)
    }
}

/** An [InputStream] view, so the frame reader can sit on a transport. */
fun AapTransport.inputStream(): InputStream = object : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        val read = this@inputStream.read(one, 0, 1)
        return if (read <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = this@inputStream.read(b, off, len)

    override fun close() = this@inputStream.close()
}

/** An [OutputStream] view, so the frame writer can sit on a transport. */
fun AapTransport.outputStream(): OutputStream = object : OutputStream() {
    override fun write(b: Int) {
        this@outputStream.write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        this@outputStream.write(b, off, len)
    }

    override fun flush() = this@outputStream.flush()

    override fun close() = this@outputStream.close()
}

/**
 * Two pipes wired to each other, for tests and for the in-process simulator.
 *
 * Each side blocks when its buffer is empty, so a test reads like a real session without opening
 * a socket. The two ends are meant for different threads: a single thread would wait on a reply
 * it never sends, exactly as it would on a USB endpoint.
 *
 * Deliberately not built on [java.io.PipedInputStream]. That class decides a pipe is broken when
 * the thread that last wrote to it is no longer alive, which is right for a producer and consumer
 * pair and wrong here: the simulated phone finishes its handshake on one thread and then sends
 * data from another, so a read that happens in between sees a dead writer and throws "Write end
 * dead". That is a harness artefact, but it would surface as a TLS failure and send the next
 * debugging session after the wrong bug. [BytePipe] below waits on a condition instead of
 * inspecting threads, so the only way to end a stream is to close it.
 */
class LoopbackTransport private constructor(
    private val incoming: BytePipe,
    private val outgoing: BytePipe,
) : AapTransport {

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = incoming.read(buffer, offset, length)

    override fun write(buffer: ByteArray, offset: Int, length: Int) = outgoing.write(buffer, offset, length)

    override fun flush() {
        // Nothing to flush: a write is visible to the peer as soon as it is copied.
    }

    override fun close() {
        incoming.close()
        outgoing.close()
    }

    companion object {
        /** Returns the pair, in the order (session, peer). Each must be used from its own thread. */
        fun pair(capacity: Int = 64 * 1024): Pair<LoopbackTransport, LoopbackTransport> {
            val up = BytePipe(capacity)
            val down = BytePipe(capacity)
            return LoopbackTransport(incoming = down, outgoing = up) to
                LoopbackTransport(incoming = up, outgoing = down)
        }
    }
}

/**
 * A bounded byte queue with blocking ends.
 *
 * Writers wait for room, readers wait for bytes. Closing ends the stream once what is already
 * buffered has been read, so a peer that closes right after its last message does not lose it.
 */
internal class BytePipe(private val capacity: Int) {

    private val lock = ReentrantLock()
    private val readable = lock.newCondition()
    private val writable = lock.newCondition()
    private val buffer = ByteArray(capacity)
    private var head = 0
    private var size = 0
    private var closed = false

    fun write(source: ByteArray, offset: Int, length: Int) {
        var written = 0
        lock.lock()
        try {
            while (written < length) {
                if (closed) throw IOException("pipe closed while writing")
                if (size == capacity) {
                    writable.await()
                    continue
                }
                val room = minOf(capacity - size, length - written)
                val tail = (head + size) % capacity
                val first = minOf(room, capacity - tail)
                System.arraycopy(source, offset + written, buffer, tail, first)
                if (room > first) {
                    System.arraycopy(source, offset + written + first, buffer, 0, room - first)
                }
                size += room
                written += room
                readable.signalAll()
            }
        } finally {
            lock.unlock()
        }
    }

    fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        lock.lock()
        try {
            while (size == 0) {
                if (closed) return -1
                readable.await()
            }
            val taken = minOf(size, length)
            val first = minOf(taken, capacity - head)
            System.arraycopy(buffer, head, target, offset, first)
            if (taken > first) {
                System.arraycopy(buffer, 0, target, offset + first, taken - first)
            }
            head = (head + taken) % capacity
            size -= taken
            writable.signalAll()
            return taken
        } finally {
            lock.unlock()
        }
    }

    fun close() {
        lock.lock()
        try {
            closed = true
            readable.signalAll()
            writable.signalAll()
        } finally {
            lock.unlock()
        }
    }
}
