package com.shilapi.xcertplay

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small protocol log for the projection screen.
 *
 * Android Auto failures are almost always visible in the message sequence — a session that stops
 * after `AuthComplete` is a certificate problem, one that stops after `SETUP` is a media-config
 * mismatch — and none of that is observable from a black screen. Keeping the last few hundred lines
 * in memory lets the screen show them without a debugger attached, and writes the same lines to
 * logcat so `adb logcat` is enough during a real install.
 */
object AapDiagnostics {

    private const val TAG = "DiPlayAap"
    private const val CAPACITY = 400

    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(scope: String, message: String) {
        val line = "${clock.format(Date())} $scope: $message"
        Log.i(TAG, line)
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
        }
    }

    /** The most recent lines, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }
}
