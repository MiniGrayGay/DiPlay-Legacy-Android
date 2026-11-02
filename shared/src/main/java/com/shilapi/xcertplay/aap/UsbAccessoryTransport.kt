package com.shilapi.xcertplay.aap

import android.annotation.SuppressLint
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * AAP over USB, from the head unit's side — the wired half of "same as CarPlay".
 *
 * Two phases, and they see the phone differently:
 *
 * 1. **Before** the AOA handshake the phone is an ordinary [UsbDevice] offering MTP. Pushing it
 *    into accessory mode is a run of vendor control transfers, done by [UsbAoaHandshake]. When the
 *    last one lands the phone re-enumerates and this device path disappears.
 * 2. **After** re-enumeration the phone comes back as a [UsbDevice] with vendor id `0x18D1` and
 *    product id `0x2D00`–`0x2D03`, carrying one interface with two bulk endpoints. That is the
 *    device this transport opens.
 *
 * Note which type that is. A head unit is the USB **host**, so it never gets a
 * `android.hardware.usb.UsbAccessory` — that class is what a device sees when it is the accessory,
 * i.e. the phone's view. Opening the accessory type on the host side yields a `ParcelFileDescriptor`
 * and no endpoints at all, so a transport built that way compiles, connects, and then has nothing
 * to read from.
 */
@SuppressLint("NewApi")
class UsbAccessoryTransport(
    private val connection: UsbDeviceConnection,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
    private val claimedInterface: UsbInterface,
    /**
     * How long a read waits for bytes. Zero blocks until data arrives or the phone goes away, which
     * is what a session wants: the phone sends nothing while the car screens sit idle.
     */
    private val readTimeoutMillis: Int = 0,
) : AapTransport {

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // A blocking bulk read reports a detached phone by returning -1, the same value an
        // exhausted socket stream returns, so the frame reader's EOF handling covers both.
        val read = connection.bulkTransfer(input, buffer, offset, length, readTimeoutMillis)
        return if (read < 0) -1 else read
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        // One bulk transfer is not guaranteed to be the whole buffer: the host controller splits at
        // its own packet boundaries and reports a short count rather than an error. Looping here is
        // the difference between working video and a stream that desyncs after the first big frame.
        var written = 0
        while (written < length) {
            val sent = connection.bulkTransfer(output, buffer, offset + written, length - written, WRITE_TIMEOUT_MILLIS)
            if (sent <= 0) throw IOException("USB bulk write failed after $written of $length bytes")
            written += sent
        }
    }

    override fun close() {
        // Releasing the interface is what lets the phone re-open its own side after a replug; a
        // connection left claimed survives until the process dies and looks like a dead port.
        runCatching { connection.releaseInterface(claimedInterface) }
        runCatching { connection.close() }
    }

    companion object {
        /**
         * Long enough for a 16 KiB frame on a busy bus, short enough that a wedged endpoint is
         * reported instead of parking the writer thread forever.
         */
        const val WRITE_TIMEOUT_MILLIS = 2_000

        /**
         * Opens the accessory-mode phone and locates its bulk pair.
         *
         * Returns null when the device has no bulk endpoints, which means the phone enumerated but
         * not as a projection accessory — worth reporting distinctly from "no permission", because
         * a driver cannot fix it by re-plugging.
         */
        fun open(usb: UsbManager, device: UsbDevice): UsbAccessoryTransport? {
            val pair = bulkEndpoints(device) ?: return null
            val connection = usb.openDevice(device) ?: return null
            val iface = device.getInterface(pair.third)
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                return null
            }
            return UsbAccessoryTransport(connection, pair.first, pair.second, iface)
        }

        /**
         * Finds the accessory's bulk in/out pair, plus the interface that carries them.
         *
         * Direction is from the host's point of view: `USB_DIR_IN` is data coming *from* the phone,
         * which is video and audio; `USB_DIR_OUT` carries input reports and control messages.
         * Swapping them produces a session that connects and then goes silent in one direction,
         * which reads like a protocol bug rather than a plumbing one.
         */
        fun bulkEndpoints(device: UsbDevice): Triple<UsbEndpoint, UsbEndpoint, Int>? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var inbound: UsbEndpoint? = null
                var outbound: UsbEndpoint? = null
                for (j in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(j)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    when (endpoint.direction) {
                        UsbConstants.USB_DIR_IN -> if (inbound == null) inbound = endpoint
                        UsbConstants.USB_DIR_OUT -> if (outbound == null) outbound = endpoint
                    }
                }
                val input = inbound
                val output = outbound
                if (input != null && output != null) return Triple(input, output, i)
            }
            return null
        }
    }
}

/**
 * Executes the AOA handshake against a phone that is still a plain USB device.
 *
 * The request sequence itself lives in [AoaHandshake] as data so it can be unit tested; this is
 * only the part that needs a real [UsbDeviceConnection]. Every step is checked, because the failure
 * modes here all look alike from the outside: a phone that ignores `SEND_STRING` still answers
 * `START`, re-enumerates, and then presents an accessory with no model string — a phone the user
 * will not recognise and that some builds refuse to re-open.
 */
@SuppressLint("NewApi")
object UsbAoaHandshake {

    /** Control transfer timeout in milliseconds. AOA specifies 1000 as a minimum. */
    private const val TIMEOUT_MILLIS = 2_000

    /** Reads the phone's AOA protocol version, or 0 when it does not speak AOA. */
    fun protocolVersion(connection: UsbDeviceConnection): Int {
        val reply = ByteArray(2)
        val read = connection.controlTransfer(
            Aoa.TYPE_IN,
            Aoa.REQUEST_GET_PROTOCOL,
            0,
            0,
            reply,
            reply.size,
            TIMEOUT_MILLIS,
        )
        return if (read < 0) 0 else Aoa.protocolVersion(reply.copyOf(read))
    }

    /**
     * Runs the whole sequence: version, identity strings, START.
     *
     * Returns the protocol version that was detected; the caller re-scans for the accessory only if
     * this is non-zero, since a zero means the phone never entered accessory mode and there will be
     * nothing to scan for.
     */
    fun run(
        connection: UsbDeviceConnection,
        identity: AoaIdentity = AoaIdentity.diplay(),
    ): Int {
        val version = protocolVersion(connection)
        if (!Aoa.canStart(version)) return version
        for (request in AoaHandshake.plan(version, identity)) {
            val payload = request.payload
            val sent = connection.controlTransfer(
                request.requestType,
                request.request,
                request.value,
                request.index,
                payload,
                payload?.size ?: 0,
                TIMEOUT_MILLIS,
            )
            if (sent < 0) {
                throw IOException("AOA request ${request.request} index ${request.index} was refused")
            }
        }
        return version
    }

    /**
     * Whether [device] is a phone that has not entered accessory mode yet.
     *
     * Anything with vendor id `0x18D1` that is already in the accessory product-id range is
     * excluded: that is what [UsbAccessoryTransport] opens, and running the handshake against it
     * again resets a working accessory.
     */
    fun isCandidate(device: UsbDevice): Boolean =
        !Aoa.isAccessory(device.vendorId, device.productId) && device.interfaceCount > 0

    /** Whether [device] is a phone that has already re-enumerated as an accessory. */
    fun isAccessory(device: UsbDevice): Boolean = Aoa.isAccessory(device.vendorId, device.productId)
}
