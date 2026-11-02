package com.shilapi.xcertplay.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The ADB protocol carried on the cable: the two bulk endpoints of the phone's ADB interface.
 *
 * This is the piece that makes Android mode need nothing installed on the phone. An Android phone with
 * USB debugging on already exposes that interface and is already running `adbd` behind it — the head
 * unit only has to claim it and speak. No accessory-mode switch, no re-enumeration, no app launch on
 * the phone, and nothing for the driver to tap after the one-time key approval.
 */
class UsbAdbTransport(
    private val connection: UsbDeviceConnection,
    private val inbound: UsbEndpoint,
    private val outbound: UsbEndpoint,
    override val peer: String,
) : AdbTransport {

    private val open = AtomicBoolean(true)

    override val input: InputStream = object : InputStream() {
        private val one = ByteArray(1)

        override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            // A bulk read that times out is not an error: adbd simply had nothing to say for a moment.
            // Re-submitting once a second keeps close() able to end the read on kernels where a
            // pending request cannot be cancelled.
            while (open.get()) {
                val count = connection.bulkTransfer(inbound, b, off, len, READ_TIMEOUT_MS)
                if (count > 0) return count
                if (!open.get()) return -1
            }
            return -1
        }

        override fun close() = this@UsbAdbTransport.close()
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var sent = 0
            while (sent < len) {
                if (!open.get()) throw IOException("USB transport closed")
                // bulkTransfer may accept less than asked; the remainder goes in the next request.
                val count = connection.bulkTransfer(outbound, b, off + sent, len - sent, WRITE_TIMEOUT_MS)
                if (count <= 0) throw IOException("USB write failed ($count)")
                sent += count
            }
        }

        override fun close() = this@UsbAdbTransport.close()
    }

    override val isOpen: Boolean get() = open.get()

    override fun close() {
        if (!open.compareAndSet(true, false)) return
        runCatching { connection.close() }
    }

    companion object {
        private const val TAG = "DiPlayUsbAdb"
        private const val READ_TIMEOUT_MS = 1_000
        private const val WRITE_TIMEOUT_MS = 5_000

        /**
         * Claims the phone's ADB interface and opens the connection.
         *
         * Returns null when the phone exposes no ADB interface — USB debugging is off, or the cable is
         * plugged into a port the phone treats as charging only. Callers turn that into the on-screen
         * hint rather than a silent failure.
         */
        fun open(device: UsbDevice, connection: UsbDeviceConnection): UsbAdbTransport? {
            val index = adbInterfaceIndex(device)
            if (index < 0) {
                Log.i(TAG, "${device.deviceName}: no ADB interface (USB debugging off?)")
                return null
            }
            val usbInterface = device.getInterface(index)
            var inbound: UsbEndpoint? = null
            var outbound: UsbEndpoint? = null
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                val bulk = endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                if (bulk && endpoint.direction == UsbConstants.USB_DIR_IN) inbound = endpoint
                if (bulk && endpoint.direction == UsbConstants.USB_DIR_OUT) outbound = endpoint
            }
            if (inbound == null || outbound == null) {
                Log.i(TAG, "${device.deviceName}: ADB interface has no bulk pair")
                return null
            }
            if (!connection.claimInterface(usbInterface, true)) {
                Log.i(TAG, "${device.deviceName}: ADB interface is claimed by someone else")
                return null
            }
            val name = "${device.productName ?: "android"} ${device.deviceName}"
            Log.i(TAG, "ADB interface claimed on $name")
            return UsbAdbTransport(connection, inbound, outbound, name)
        }

        /**
         * Index of the vendor-specific / subclass 0x42 / protocol 0x01 interface, which is how Android
         * declares ADB everywhere. [com.shilapi.xcertplay.mirroring.PhoneClassifier] already relies on
         * the same triple to recognise a phone before this class is ever asked to open it.
         */
        fun adbInterfaceIndex(device: UsbDevice): Int {
            for (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    usbInterface.interfaceSubclass == ADB_SUBCLASS &&
                    usbInterface.interfaceProtocol == ADB_PROTOCOL
                ) {
                    return index
                }
            }
            return -1
        }

        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
    }
}
