package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.compat.realSdkInt

/** Warms the car's display route before CarPlay starts; existing connection code owns the hotspot. */
internal class CarPlayStartup(
    private val activity: Activity,
    private val requestOverlayPermission: (Intent) -> Unit,
    private val onReady: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var started = false
    private var overlayPermissionPending = false
    private var resumed = false
    private var returningFromGaode = false
    private var gaodeReturnAttempts = 0
    var ready = false
        private set

    private fun activityUnavailable(): Boolean = activity.isFinishing || activity.isDestroyed

    private fun canDrawOverlays(): Boolean = Build.VERSION.SDK_INT < 23 || realSdkInt() < 23 ||
        Settings.canDrawOverlays(activity)

    fun start() {
        if (closed || started) return
        started = true
        if (Build.VERSION.SDK_INT >= 23 && realSdkInt() >= 23 && !canDrawOverlays() && !overlayPermissionAsked) {
            overlayPermissionAsked = true
            overlayPermissionPending = true
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
            if (runCatching { requestOverlayPermission(intent) }
                    .onFailure { Log.w(TAG, "Cannot open overlay permission page", it) }.isSuccess) return
            overlayPermissionPending = false
        }
        warmGaode()
    }

    fun onOverlayPermissionResult() {
        if (closed || !overlayPermissionPending) return
        overlayPermissionPending = false
        Log.i(TAG, "Overlay permission granted=${canDrawOverlays()}")
        warmGaode()
    }

    fun onResume() {
        resumed = true
        if (returningFromGaode) {
            returningFromGaode = false
            Log.i(TAG, "CarPlay task resumed after Gaode warmup")
        }
    }

    fun onPause() {
        resumed = false
    }

    private fun warmGaode() {
        if (closed || ready) return
        if (!gaodeAttempted) {
            gaodeAttempted = true
            val runningPackage = runningGaodePackage()
            if (runningPackage != null) {
                Log.i(TAG, "Gaode already running: $runningPackage; skipping display-route warmup")
                ready = true
                onReady()
                return
            }
            // Android 10+ may open Gaode but block our return without overlay permission.
            val canReturnFromBackground = realSdkInt() < 29 || canDrawOverlays()
            if (!canReturnFromBackground) {
                Log.w(TAG, "Skipping Gaode warmup: overlay permission is required for automatic background return")
            }
            for (name in if (canReturnFromBackground) GAODE_PACKAGES else emptyList()) {
                val intent = activity.packageManager.getLaunchIntentForPackage(name) ?: continue
                if (runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Log.w(TAG, "Cannot launch $name", it) }.isSuccess) {
                    gaodeReadyAt = SystemClock.elapsedRealtime() + 2_000L
                    Log.i(TAG, "Gaode started: $name; waiting 2 seconds for the car display route")
                    break
                }
            }
            if (gaodeReadyAt == 0L) Log.i(TAG, "Gaode is not available; continuing without display-route warmup")
        }
        val remaining = (gaodeReadyAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        main.postDelayed({
            if (closed || ready || activityUnavailable()) return@postDelayed
            ready = true
            // Consume once; a replacement Activity may finish an interrupted warmup, not replay it.
            val needsReturn = gaodeReadyAt != 0L
            gaodeReadyAt = 0L
            if (needsReturn && !resumed) returnToCarPlay()
            onReady()
        }, remaining)
    }

    private fun runningGaodePackage(): String? = runCatching {
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Process visibility varies by firmware; an empty query falls back to one launch per process.
        val processes = manager.runningAppProcesses.orEmpty()
        GAODE_PACKAGES.firstOrNull { name ->
            processes.any { process ->
                process.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE &&
                    (process.pkgList?.contains(name) == true || process.processName == name ||
                        process.processName?.startsWith("$name:") == true)
            }
        }
    }.onFailure { Log.w(TAG, "Cannot inspect Gaode processes; using one-time launch fallback", it) }.getOrNull()

    private fun returnToCarPlay() {
        if (closed || resumed || activityUnavailable()) return
        returningFromGaode = true
        gaodeReturnAttempts++
        Log.i(TAG, "Returning to CarPlay after Gaode warmup; attempt=$gaodeReturnAttempts/${MAX_GAODE_RETURN_RETRIES + 1}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                @Suppress("DEPRECATION")
                val task = manager.appTasks.firstOrNull { it.taskInfo?.id == activity.taskId }
                task?.moveToFront()
            }.onFailure { Log.w(TAG, "Cannot move the existing CarPlay task to front", it) }
        }
        runCatching {
            activity.startActivity(Intent(activity, CarPlayHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { Log.w(TAG, "Cannot return to CarPlay automatically; open DiPlayDuo to continue", it) }
        // A background launch denial may not throw; onResume is the success signal.
        main.postDelayed({
            if (closed || !returningFromGaode || resumed || activityUnavailable()) return@postDelayed
            if (gaodeReturnAttempts <= MAX_GAODE_RETURN_RETRIES) {
                Log.w(TAG, "CarPlay task did not resume; retrying in 2 seconds after attempt=$gaodeReturnAttempts")
                main.postDelayed({
                    if (returningFromGaode) returnToCarPlay()
                }, 2_000L)
            } else {
                returningFromGaode = false
                Log.w(TAG, "CarPlay task did not resume after $gaodeReturnAttempts attempts; check overlay/background launch permissions and open DiPlayDuo manually")
            }
        }, 1_000L)
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val TAG = "DiPlay-Startup"
        const val MAX_GAODE_RETURN_RETRIES = 3
        val GAODE_PACKAGES = listOf("com.autonavi.amapauto", "com.autonavi.minimap")
        var overlayPermissionAsked = false
        var gaodeAttempted = false
        var gaodeReadyAt = 0L
    }
}
