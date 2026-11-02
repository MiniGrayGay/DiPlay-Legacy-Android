package com.shilapi.xcertplay.adb

import android.content.Context

/**
 * Where Android mode looks for the phone when the cable is not the path.
 *
 * The cable needs nothing here: the phone is whatever is plugged in. This address is for the case where
 * the phone's `adbd` is already on the network — wireless debugging switched on, or a development
 * handset on the same bench.
 */
internal object AdbPreferences {
    /** adbd's own port; the value the phone itself listens on when TCP debugging is on. */
    const val DEFAULT_PORT = 5555

    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)

    /**
     * Defaults to the head unit itself, because the first thing anyone tries is `adb tcpip 5555` on the
     * head unit and pointing Android mode at it — a full loop with no second device involved.
     */
    fun phoneHost(context: Context): String =
        prefs(context).getString("adb_phone_host", null)?.takeIf { it.isNotBlank() } ?: "127.0.0.1"

    fun savePhoneHost(context: Context, host: String) {
        prefs(context).edit().putString("adb_phone_host", host.trim()).apply()
    }

    fun phonePort(context: Context): Int =
        prefs(context).getInt("adb_phone_port", DEFAULT_PORT).coerceIn(1, 65535)

    fun savePhonePort(context: Context, port: Int) {
        prefs(context).edit().putInt("adb_phone_port", port.coerceIn(1, 65535)).apply()
    }
}
