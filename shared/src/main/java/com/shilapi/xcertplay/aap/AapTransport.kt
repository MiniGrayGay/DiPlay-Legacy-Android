package com.shilapi.xcertplay.aap

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

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
 * a socket. Built on [PipedInputStream], the JDK's own blocking pipe: it already gets the
 * wait/notify right, and a hand-rolled ring buffer here would only add a way to get it wrong.
 *
 * The two ends must be used from different threads — [PipedInputStream] deadlocks otherwise.
 */
class LoopbackTransport(
    private val source: InputStream,
    private val sink: OutputStream,
) : AapTransport {

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        sink.write(buffer, offset, length)
    }

    override fun flush() = sink.flush()

    override fun close() {
        try {
            source.close()
        } catch (_: Exception) {
        }
        try {
            sink.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Returns the pair, in the order (session, peer). Each must be used from its own thread. */
        fun pair(capacity: Int = 64 * 1024): Pair<LoopbackTransport, LoopbackTransport> {
            val upOut = PipedOutputStream()
            val upIn = PipedInputStream(upOut, capacity)
            val downOut = PipedOutputStream()
            val downIn = PipedInputStream(downOut, capacity)
            return LoopbackTransport(downIn, upOut) to LoopbackTransport(upIn, downOut)
        }
    }
}
