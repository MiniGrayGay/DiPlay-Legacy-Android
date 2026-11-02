package com.shilapi.xcertplay.aa

import com.shilapi.xcertplay.mirroring.aa.AaDuplex
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * [AaDuplex] over a TCP socket. Writes are buffered and flushed per frame so a 60 KiB key frame goes
 * out as one segment instead of two dozen, which matters on a head unit's weak WiFi.
 */
internal class SocketAaDuplex(private val socket: Socket) : AaDuplex {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray) {
        try {
            output.write(bytes)
            output.flush()
        } catch (error: Throwable) {
            // A socket closed from another thread surfaces as a bare NPE inside the stream, so the
            // stack is logged here before the session turns it into a user-visible reason.
            android.util.Log.w("DiPlayAa", "socket write failed (${bytes.size}B)", error)
            throw error
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        fun connect(host: String, port: Int, timeoutMs: Int = 5_000): SocketAaDuplex {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            return SocketAaDuplex(socket)
        }
    }
}
