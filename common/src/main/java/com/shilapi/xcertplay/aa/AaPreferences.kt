package com.shilapi.xcertplay.aa

import android.content.Context

/**
 * Persistence for the Android mode's loopback test pair. Kept apart from [com.shilapi.xcertplay.DiPlayPreferences]
 * so the experimental entries can be removed without touching the connection settings.
 */
internal object AaPreferences {
    /** The wireless Android Auto port is 5277; a distinct port keeps a real phone from colliding. */
    const val DEFAULT_PORT = 8790

    /** An Android emulator reaches its host through 10.0.2.2, which is where the sender forwards to. */
    const val DEFAULT_HOST = "10.0.2.2"

    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)

    /** Address of the phone acting as sender, as typed by the driver. */
    fun receiverHost(context: Context): String =
        prefs(context).getString("aa_receiver_host", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST

    fun saveReceiverHost(context: Context, host: String) {
        prefs(context).edit().putString("aa_receiver_host", host.trim()).apply()
    }

    fun receiverPort(context: Context): Int =
        prefs(context).getInt("aa_receiver_port", DEFAULT_PORT).coerceIn(1, 65535)

    fun saveReceiverPort(context: Context, port: Int) {
        prefs(context).edit().putInt("aa_receiver_port", port.coerceIn(1, 65535)).apply()
    }

    /** Port the sender listens on. Forwarded by adb when both ends run on one workstation. */
    fun senderPort(context: Context): Int =
        prefs(context).getInt("aa_sender_port", DEFAULT_PORT).coerceIn(1, 65535)

    fun saveSenderPort(context: Context, port: Int) {
        prefs(context).edit().putInt("aa_sender_port", port.coerceIn(1, 65535)).apply()
    }

    /** H.264 by default; the raw path exists for devices whose encoder is unusable. */
    fun codec(context: Context): Int =
        prefs(context).getInt("aa_codec", com.shilapi.xcertplay.mirroring.aa.AaWire.Codec.H264)

    fun saveCodec(context: Context, codec: Int) {
        prefs(context).edit().putInt("aa_codec", codec).apply()
    }
}
