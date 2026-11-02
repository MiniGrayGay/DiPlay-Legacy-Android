package com.shilapi.xcertplay.transport

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File

/**
 * Best-effort, device-scoped cdc_ncm detach for the GAEI Android 4.4 head unit.
 *
 * vpnG speaks the tiny smcd protocol itself; it does not execute or depend on EngineerMode's
 * smcd_cmd binary. The system smcd daemon is still the root authority. If SELinux denies this app
 * access to /dev/socket/smcd, callers retain the old force-claim fallback.
 */
internal object CdcNcmDriverDetach {
    data class Result(val readyForNonForcingClaim: Boolean, val diagnostic: String)

    fun detach(configuration: Int, controlInterface: Int, dataInterface: Int): Result {
        require(configuration in 1..255)
        require(controlInterface in 0..255)
        require(dataInterface in 0..255)
        val command = command(configuration, controlInterface, dataInterface)
        val bytes = command.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SMCD_PAYLOAD_BYTES) {
            return Result(false, "vpnG internal smcd command too long bytes=${bytes.size}")
        }
        runCatching { File(RESULT_PATH).delete() }
        val responseBytes = try {
            SmcdSocketClient.exec(bytes)
        } catch (error: Exception) {
            return Result(
                false,
                "vpnG internal smcd unavailable failure=${error.javaClass.simpleName}: ${error.message}",
            )
        } catch (error: Error) {
            return Result(false, "vpnG internal smcd unavailable failure=${error.javaClass.simpleName}")
        }
        val result = runCatching { File(RESULT_PATH).readText().trim() }.getOrNull()
        return if (responseBytes > 0 && result == "0") {
            Result(true, "vpnG internal smcd detached cdc_ncm; ordinary claim enabled")
        } else {
            Result(
                false,
                "vpnG internal smcd detach not confirmed ackBytes=$responseBytes result=${result ?: "unreadable"}",
            )
        }
    }

    /** Kept compact because the vulnerable daemon aborts when a payload exceeds 251 bytes. */
    internal fun command(configuration: Int, controlInterface: Int, dataInterface: Int): String {
        val members = "\$d/*:$configuration.$controlInterface \$d/*:$configuration.$dataInterface"
        return "d=/sys/bus/usb/drivers/cdc_ncm;o=$RESULT_PATH;" +
            "for i in $members;do [ -e \$i ]&&[ \"\$(cat \$i/../idVendor)\" = 05ac ]&&" +
            "echo \${i##*/}>\$d/unbind;done;r=0;" +
            "for i in $members;do [ -e \$i ]&&r=1;done;" +
            "echo \$r>\$o;chmod 666 \$o"
    }

    private object SmcdSocketClient {
        fun exec(payload: ByteArray): Int {
            require(payload.size <= MAX_SMCD_PAYLOAD_BYTES)
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(SMCD_SOCKET, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.soTimeout = SMCD_READ_TIMEOUT_MILLIS
                val packet = ByteArray(payload.size + 3)
                packet[0] = 0x68 // smcd case 'h': system(payload) as root
                packet[1] = 0x61 // protocol discriminator 'a'
                payload.copyInto(packet, destinationOffset = 2)
                packet[packet.lastIndex] = 0
                socket.outputStream.write(packet)
                socket.outputStream.flush()
                return socket.inputStream.read(ByteArray(512)).coerceAtLeast(0)
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private const val SMCD_SOCKET = "/dev/socket/smcd"
    private const val RESULT_PATH = "/data/local/tmp/.diplay_ncm"
    private const val MAX_SMCD_PAYLOAD_BYTES = 251
    private const val SMCD_READ_TIMEOUT_MILLIS = 3_000
}
