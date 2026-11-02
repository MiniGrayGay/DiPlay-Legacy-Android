package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import kotlin.math.roundToInt

/** Mirrors stream 110, not the separate dashboard-map stream or the Android desktop. Main thread only. */
internal class CarPlaySecondScreen(private val activity: Activity, private val onStarting: () -> Unit) {
    private val manager = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val preferences = activity.getSharedPreferences("carplay_second_screen", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var sink: AndroidMediaSink? = null
    private var width = 1
    private var height = 1
    private var presentation: CarPlayMirrorPresentation? = null
    private var output: Surface? = null
    private var chooser: AlertDialog? = null
    private var selectionCancelled = false
    private var listening = false
    private var videoReady = false
    val active: Boolean get() = presentation != null

    private val retry = Runnable { ensurePresentation() }
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = ensurePresentation()
        override fun onDisplayChanged(displayId: Int) {
            if (presentation?.display?.displayId == displayId) presentation?.updateVideoSize(width, height)
            ensurePresentation()
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) dismissPresentation()
            ensurePresentation()
        }
    }

    fun bind(next: AndroidMediaSink, videoWidth: Int, videoHeight: Int) {
        if (sink !== next) {
            stop()
            sink = next
        }
        width = videoWidth.coerceAtLeast(1)
        height = videoHeight.coerceAtLeast(1)
        presentation?.updateVideoSize(width, height)
    }

    /** Also accepts a retained session whose main stream has already rendered its first frame. */
    fun onVideoReady() {
        if (sink == null) return
        videoReady = true
        if (!listening) {
            manager?.registerDisplayListener(listener, main)
            listening = true
        }
        ensurePresentation()
    }

    private fun ensurePresentation() {
        main.removeCallbacks(retry)
        if (!videoReady || sink == null || activity.isFinishing || activity.isDestroyed) return
        if (presentation?.isShowing == true) return
        val displays = manager?.displays.orEmpty().filter {
            @Suppress("DEPRECATION")
            it.isValid && it.displayId != Display.DEFAULT_DISPLAY && it.displayId != activity.windowManager.defaultDisplay.displayId
        }
        val name = preferences.getString("displayName", null)
        val id = preferences.getInt("displayId", -1)
        val target = if (name != null) {
            val matches = displays.filter { it.name == name }
            matches.firstOrNull { it.displayId == id } ?: matches.singleOrNull()
        } else displays.singleOrNull()
        if (target != null) {
            show(target)
        } else if (name == null && displays.size > 1 && chooser == null && !selectionCancelled && activity.hasWindowFocus()) {
            chooser = AlertDialog.Builder(activity)
                .setTitle(R.string.second_screen_choose)
                .setItems(displays.map { "${it.displayId}: ${it.name}" }.toTypedArray()) { _, index ->
                    val display = displays[index]
                    preferences.edit().putInt("displayId", display.displayId).putString("displayName", display.name).apply()
                    ensurePresentation()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> selectionCancelled = true }
                .setOnCancelListener { selectionCancelled = true }
                .create().also { dialog ->
                    dialog.setOnDismissListener { chooser = null }
                    dialog.show()
                }
        }
        // The car may publish its display late, without another CarPlay connection event.
        if (presentation == null && !selectionCancelled) main.postDelayed(retry, 2_000L)
    }

    private fun show(display: Display) {
        val next = CarPlayMirrorPresentation(activity, display, width, height, ::setSurface)
        next.setOnDismissListener {
            if (presentation === next) {
                setSurface(null)
                presentation = null
                if (videoReady) main.postDelayed(retry, 2_000L)
            }
        }
        presentation = next
        try {
            onStarting()
            next.show()
            preferences.edit().putInt("displayId", display.displayId).putString("displayName", display.name).apply()
            Log.i(TAG, "Mirroring CarPlay to display=${display.displayId}:${display.name} source=${width}x$height")
        } catch (error: RuntimeException) {
            Log.w(TAG, "Second screen is not accessible: ${display.displayId}:${display.name}", error)
            dismissPresentation()
        }
    }

    private fun setSurface(surface: Surface?) {
        if (output === surface) return
        output?.let {
            val detached = sink?.detachMirrorSurface(MAIN_STREAM, MIRROR_KEY, it)
            if (detached == false) Log.w(TAG, "Second-screen decoder did not release its surface before the deadline")
        }
        output = surface
        if (surface != null) sink?.setMirrorSurface(MAIN_STREAM, MIRROR_KEY, surface)
    }

    private fun dismissPresentation() {
        setSurface(null)
        val old = presentation
        presentation = null
        old?.setOnDismissListener(null)
        old?.dismiss()
    }

    fun stop() {
        videoReady = false
        main.removeCallbacksAndMessages(null)
        if (listening) manager?.unregisterDisplayListener(listener)
        listening = false
        chooser?.dismiss()
        chooser = null
        dismissPresentation()
        sink = null
    }

    private companion object {
        const val TAG = "DiPlay-Mirror"
        const val MAIN_STREAM = 110
        const val MIRROR_KEY = "carplay-second-screen"
    }
}

/** An aspect-preserving SurfaceView on a default Presentation, without screen capture. */
internal class CarPlayMirrorPresentation(
    context: Context,
    display: Display,
    private var videoWidth: Int,
    private var videoHeight: Int,
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var video: SurfaceView
    private var attached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            // Never use TYPE_PHONE (2002) or TYPE_SYSTEM_ALERT (2003).
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        video = SurfaceView(context).apply { holder.addCallback(this@CarPlayMirrorPresentation) }
        root.addView(video, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVideoSize(videoWidth, videoHeight) }
        setContentView(root)
        window?.setLayout(-1, -1)
    }

    fun updateVideoSize(width: Int, height: Int) {
        videoWidth = width.coerceAtLeast(1)
        videoHeight = height.coerceAtLeast(1)
        if (!::root.isInitialized || root.width <= 0 || root.height <= 0) return
        val scale = minOf(root.width.toDouble() / videoWidth, root.height.toDouble() / videoHeight)
        val targetWidth = (videoWidth * scale).roundToInt().coerceIn(1, root.width)
        val targetHeight = (videoHeight * scale).roundToInt().coerceIn(1, root.height)
        val params = video.layoutParams as FrameLayout.LayoutParams
        if (params.width == targetWidth && params.height == targetHeight) return
        params.width = targetWidth
        params.height = targetHeight
        video.layoutParams = params
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!attached && width > 0 && height > 0 && holder.surface.isValid) {
            attached = true
            onSurface(holder.surface)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = detach()

    override fun onStop() {
        detach()
        super.onStop()
    }

    private fun detach() {
        if (!attached) return
        attached = false
        // The holder owns its Surface; wait for decoder detachment without releasing it here.
        onSurface(null)
    }
}
