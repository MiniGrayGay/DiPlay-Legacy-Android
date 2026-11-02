package com.shilapi.xcertplay.adb

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * A raw byte pipe to a phone's `adbd`.
 *
 * The bytes are the plain ADB protocol either way; only the pipe differs. On the cable it is the two
 * bulk endpoints of the phone's ADB interface, on the diagnostics path a socket to a device that has
 * `adb tcpip` turned on. Keeping the ADB protocol above this seam means the session, the streams and
 * their flow control are tested once, on a JVM, and the USB implementation only has to move bytes.
 */
interface AdbTransport : Closeable {
    val input: InputStream
    val output: OutputStream

    /** Shown in diagnostics: a USB path or `host:port`. */
    val peer: String

    /** True once the far end is gone; implementations may also use it to abort a blocked read. */
    val isOpen: Boolean
}
