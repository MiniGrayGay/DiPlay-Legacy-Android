package com.shilapi.xcertplay.aap

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * AAP over a TCP socket, from the head unit's side.
 *
 * This is the transport the wireless path uses: once the phone has joined the head unit's access
 * point, it dials the address and port the bootstrap announced, and everything from the version
 * request onwards runs over that socket. A wired-to-wireless adapter (the "AAWireless" shape) uses
 * the same transport with the adapter as the client instead of the phone.
 *
 * It is also what makes the session testable without hardware: point it at a phone simulator on
 * loopback and the byte stream is identical to a USB bulk pipe.
 *
 * Two socket options are not incidental:
 *
 * - **`TCP_NODELAY` on.** Nagle's algorithm would hold a 4-byte touch report until an ACK or a
 *   full segment, which on a car network is 40-200 ms of input lag. The session's traffic is many
 *   small messages, exactly the pattern Nagle is worst at.
 * - **No read timeout by default.** A head unit spends most of a session with nothing to read;
 *   a timeout would be indistinguishable from a dead link and would tear down healthy sessions.
 *   Ending a session is the caller's job, and closing the socket is what unblocks a read.
 */
class TcpTransport(private val socket: Socket) : AapTransport {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length)

    override fun write(buffer: ByteArray, offset: Int, length: Int) = output.write(buffer, offset, length)

    override fun flush() = output.flush()

    override fun close() {
        // Closing the socket is what releases a thread blocked in read(); a half-close would leave
        // the reader parked until the peer noticed.
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }

    /** The peer address, for the diagnostics log. */
    fun remote(): String = socket.inetAddress?.hostAddress ?: "unknown"

    companion object {
        /** The port Android Auto uses for wireless projection. */
        const val DEFAULT_PORT = 5277

        fun connect(
            host: String,
            port: Int = DEFAULT_PORT,
            connectTimeoutMillis: Int = 8_000,
        ): TcpTransport {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            return TcpTransport(socket)
        }

        /**
         * Accepts one connection, or returns null when [timeoutMillis] elapses first.
         *
         * A timeout that returned null forever would hide a firewall that drops SYNs; the caller
         * decides whether to keep listening, so a null here just means "nothing yet".
         */
        fun accept(server: ServerSocket, timeoutMillis: Int = 30_000): TcpTransport? {
            server.soTimeout = timeoutMillis
            return try {
                val socket = server.accept()
                socket.tcpNoDelay = true
                TcpTransport(socket)
            } catch (_: SocketTimeoutException) {
                null
            }
        }
    }
}

/** Binds the listening side of the wireless path. */
object AapTcpServer {

    /**
     * Binds [port] on [bindAddress].
     *
     * The address matters on a head unit: the phone reaches it over the access point's interface,
     * and binding to `0.0.0.0` on a device that also has mobile data would let a stray connection
     * from the wrong interface start a session. Passing the hotspot address explicitly keeps the
     * listener on the network the phone is actually on.
     */
    fun listen(
        port: Int = TcpTransport.DEFAULT_PORT,
        bindAddress: String? = null,
        backlog: Int = 1,
    ): ServerSocket {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(if (bindAddress == null) InetSocketAddress(port) else InetSocketAddress(bindAddress, port), backlog)
        return server
    }
}

/** A port that could not be bound, with the address in the message so the log is actionable. */
fun tcpErrorMessage(e: IOException, port: Int, bindAddress: String?): String =
    "could not listen on ${bindAddress ?: "0.0.0.0"}:$port: ${e.message}"
