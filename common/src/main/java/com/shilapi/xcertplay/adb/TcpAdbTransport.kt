package com.shilapi.xcertplay.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADB over a socket, for the phones whose own `adbd` is reachable at an address.
 *
 * Two uses. On a car it is the fallback when the phone's USB port is already taken by something else
 * (a charge-only hub, a dashcam) and the driver has turned on wireless debugging. In this repository
 * it is also what makes the ADB client testable end to end without a cable: the same bytes, the same
 * streams, only a socket underneath.
 */
class TcpAdbTransport(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMillis: Int = 5_000,
) : AdbTransport {

    private val socket = Socket()
    private val open = AtomicBoolean(false)

    override val input: InputStream
    override val output: OutputStream

    override val peer: String get() = "$host:$port"

    override val isOpen: Boolean get() = open.get()

    init {
        socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        socket.tcpNoDelay = true
        input = socket.getInputStream()
        output = socket.getOutputStream()
        open.set(true)
    }

    override fun close() {
        open.set(false)
        runCatching { socket.close() }
    }

    companion object {
        const val DEFAULT_PORT = 5555

        fun connect(host: String, port: Int = DEFAULT_PORT): TcpAdbTransport = TcpAdbTransport(host, port)
    }
}
