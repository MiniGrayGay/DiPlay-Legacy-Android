package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.shilapi.xcertplay.host.R

/**
 * Android mode drives the phone through the head unit's own ADB bridge, so USB debugging has to be
 * on. The hint only appears when DiPlay already decided an Android phone is the one to serve —
 * it never runs on the iPhone path, and it never blocks a CarPlay connection.
 */
internal object AskEnableUsbDebugging {
    /** Cheap settings read: no ADB handshake, so it is safe to ask on the main thread. */
    fun needed(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 0
    }.getOrDefault(false)

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_usb_debugging_required_title)
            .setMessage(R.string.settings_usb_debugging_required_message)
            .setPositiveButton(R.string.settings_usb_debugging_open_settings) { _, _ ->
                openDeveloperOptions(activity)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openDeveloperOptions(activity: Activity) {
        val developerOptions = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(developerOptions) }
            .onFailure {
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
    }
}
