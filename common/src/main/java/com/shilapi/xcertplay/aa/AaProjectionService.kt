package com.shilapi.xcertplay.aa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.shilapi.xcertplay.compat.realSdkInt
import com.shilapi.xcertplay.host.R

/**
 * Holds the [MediaProjection] the system granted for this session.
 *
 * The object exists because the consent dialog returns its result to an Activity, while the projection
 * outlives that Activity's foreground window and must be reachable from the encoder thread. It is
 * deliberately tiny: [AaProjectionService] owns the lifetime, this only publishes the handle.
 */
internal object AaProjection {
    @Volatile
    var projection: MediaProjection? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun isReady(): Boolean = projection != null

    fun attach(created: MediaProjection) {
        projection = created
        lastError = null
    }

    fun fail(reason: String) {
        lastError = reason
    }

    fun clear() {
        val active = projection ?: return
        projection = null
        try {
            active.stop()
        } catch (_: Throwable) {
        }
    }
}

/**
 * Keeps the screen capture alive in the foreground.
 *
 * Android 14 and later refuse `getMediaProjection` unless a foreground service of type
 * `mediaProjection` is already running, and this app targets 37, so the order here is fixed:
 * `startForeground` first, projection second. Older releases only need the plain foreground call.
 */
class AaProjectionService : Service() {

    companion object {
        const val ACTION_START = "com.shilapi.xcertplay.aa.PROJECTION_START"
        const val ACTION_STOP = "com.shilapi.xcertplay.aa.PROJECTION_STOP"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL = "diplay_android_mode"
        private const val NOTIFICATION_ID = 2

        /** Whether this build and OS combination can capture the screen at all. */
        fun supported(): Boolean = realSdkInt() >= Build.VERSION_CODES.LOLLIPOP

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AaProjectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (realSdkInt() >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AaProjectionService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AaProjection.clear()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForegroundCompat()
                connect(intent)
                return START_NOT_STICKY
            }
        }
        // A restart without the consent Intent cannot recover the grant; drop it rather than keep a
        // notification for a service that will never capture anything.
        stopSelf()
        return START_NOT_STICKY
    }

    private fun connect(intent: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            AaProjection.fail("no MediaProjectionManager")
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (data == null || resultCode == Int.MIN_VALUE) {
            AaProjection.fail("consent result missing")
            return
        }
        val created = try {
            @Suppress("DEPRECATION")
            manager.getMediaProjection(resultCode, data)
        } catch (error: Throwable) {
            AaProjection.fail(error.message ?: error.javaClass.simpleName)
            null
        }
        if (created == null) {
            AaProjection.fail(AaProjection.lastError ?: "projection refused")
            return
        }
        created.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // The system revoked the grant (user tapped "Stop", or another app took over).
                    AaProjection.clear()
                    handler.post { stopForegroundCompat(); stopSelf() }
                }
            },
            handler,
        )
        AaProjection.attach(created)
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (realSdkInt() >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Android mode", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (realSdkInt() >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(this, 0, Intent(this, AaSenderActivity::class.java), pendingFlags)
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AaProjectionService::class.java).setAction(ACTION_STOP),
            pendingFlags,
        )
        val builder = if (realSdkInt() >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) R.drawable.ic_diplay_notification else R.drawable.ic_carplay)
            .setContentTitle(getString(R.string.aa_sender_title))
            .setContentText(getString(R.string.aa_projection_notification))
            .setContentIntent(open)
            .setOngoing(true)
        if (realSdkInt() >= Build.VERSION_CODES.M) {
            builder.addAction(Notification.Action.Builder(null, getString(R.string.aa_projection_stop), stop).build())
        } else {
            @Suppress("DEPRECATION")
            builder.addAction(0, getString(R.string.aa_projection_stop), stop)
        }
        val notification = builder.build()
        if (realSdkInt() >= 29) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (realSdkInt() >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
