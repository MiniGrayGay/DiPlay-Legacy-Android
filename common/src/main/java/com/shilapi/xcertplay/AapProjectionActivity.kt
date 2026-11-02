package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.aap.AapInputMessages

/**
 * The projected screen: what the driver sees when an Android phone is connected.
 *
 * The activity is a view and nothing else. [AapProjectionController] owns the session, the decoder
 * and the audio; this class lays out a surface at the phone's aspect ratio, turns touches into
 * input reports in the phone's coordinate space, and shows a status line until video arrives.
 *
 * Launch it either way:
 *
 * ```
 * am start -n <pkg>/com.shilapi.xcertplay.AapProjectionActivity            # listen on 5277
 * am start ... --es host 10.0.2.2 --ei port 5277                           # dial a phone
 * ```
 */
class AapProjectionActivity : Activity(), AapProjectionController.Listener {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var controller: AapProjectionController
    private val main = Handler(Looper.getMainLooper())

    private var surfaceReady = false
    private var overlayVisible = true
    private var videoWidth = 0
    private var videoHeight = 0
    private var lastTouchX = -1
    private var lastTouchY = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        root.addView(buildOverlay())
        setContentView(root)

        controller = AapProjectionController(this, this)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                controller.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                controller.setSurface(holder.surface)
                layoutSurface()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                controller.setSurface(null)
            }
        })

        surfaceView.setOnTouchListener { view, event -> onVideoTouch(view, event) }
        root.setOnClickListener { toggleOverlay() }

        startFrom(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            controller.stop()
            startFrom(it)
        }
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Every hardware key the car has is forwarded to the phone except back, which is the way
        // out of projection; mapping it too would trap the driver on a screen with no exit.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            controller.stop()
            finish()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            -> {
                controller.sendKey(keyCode, down = true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        if (KeyEvent.KEYCODE_DPAD_UP <= keyCode && keyCode <= KeyEvent.KEYCODE_VOLUME_DOWN) {
            controller.sendKey(keyCode, down = false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------------ controller callbacks

    override fun onStatus(text: String) {
        main.post {
            status.text = text
            logView.text = AapDiagnostics.snapshot().takeLast(6).joinToString("\n")
        }
    }

    override fun onVideoSize(width: Int, height: Int) {
        main.post {
            videoWidth = width
            videoHeight = height
            layoutSurface()
        }
    }

    override fun onReady() {
        main.post { status.text = "视频通道已就绪，等待首帧…" }
    }

    /**
     * The overlay stays up until a frame is actually on the surface: a stream that connects and then
     * fails to decode is exactly when the log on it is worth reading.
     */
    override fun onFirstFrame() {
        main.post {
            if (overlayVisible) toggleOverlay()
        }
    }

    override fun onEnded(reason: String) {
        main.post {
            status.text = "已断开：$reason"
            logView.text = AapDiagnostics.snapshot().takeLast(6).joinToString("\n")
            if (!overlayVisible) toggleOverlay()
        }
    }

    // ------------------------------------------------------------------ layout

    private fun buildOverlay(): View {
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.argb(200, 0, 0, 0))
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            text = "准备中…"
        }
        logView = TextView(this).apply {
            setTextColor(Color.argb(220, 170, 220, 170))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 12, 0, 0)
        }
        column.addView(status)
        column.addView(logView)
        val wrapper = FrameLayout(this)
        wrapper.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { setMargins(24, 24, 24, 24) },
        )
        wrapper.setOnClickListener { toggleOverlay() }
        return wrapper.also { overlay = it }
    }

    private lateinit var overlay: View

    private fun toggleOverlay() {
        overlayVisible = !overlayVisible
        overlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
    }

    /** Fits the surface to the phone's aspect ratio, centred, so nothing is stretched. */
    private fun layoutSurface() {
        val parentWidth = root.width
        val parentHeight = root.height
        if (parentWidth == 0 || parentHeight == 0) return
        val width = if (videoWidth > 0) videoWidth else 16
        val height = if (videoHeight > 0) videoHeight else 9
        val scale = minOf(parentWidth.toFloat() / width, parentHeight.toFloat() / height)
        val viewWidth = (width * scale).toInt()
        val viewHeight = (height * scale).toInt()
        surfaceView.layoutParams = FrameLayout.LayoutParams(viewWidth, viewHeight, Gravity.CENTER)
    }

    private fun goImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // ------------------------------------------------------------------ input

    /**
     * Maps a touch on the letterboxed surface onto the phone's video coordinates.
     *
     * Touches on the black bars are dropped rather than clamped: a clamp turns a tap on the car's
     * own chrome into a tap at the edge of the phone's screen, which presses whatever button
     * happens to be there.
     */
    private fun onVideoTouch(view: View, event: MotionEvent): Boolean {
        if (videoWidth <= 0 || videoHeight <= 0 || view.width == 0 || view.height == 0) return false
        val x = (event.x / view.width * videoWidth).toInt()
        val y = (event.y / view.height * videoHeight).toInt()
        if (x < 0 || y < 0 || x >= videoWidth || y >= videoHeight) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                controller.sendTouch(x, y, AapInputMessages.Action.DOWN)
            }

            MotionEvent.ACTION_MOVE -> {
                // A move that lands on the same pixel is not worth a message; a drag on a slow link
                // otherwise spends its bandwidth repeating itself.
                if (x == lastTouchX && y == lastTouchY) return true
                lastTouchX = x
                lastTouchY = y
                controller.sendTouch(x, y, AapInputMessages.Action.MOVED)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastTouchX = -1
                lastTouchY = -1
                controller.sendTouch(x, y, AapInputMessages.Action.UP)
            }
        }
        return true
    }

    private fun startFrom(intent: Intent?) {
        val deviceId = intent?.getIntExtra(EXTRA_USB_DEVICE_ID, -1) ?: -1
        if (deviceId >= 0) {
            val usb = getSystemService(Context.USB_SERVICE) as? UsbManager
            val device = usb?.deviceList?.values?.firstOrNull { it.deviceId == deviceId }
            if (usb == null || device == null) {
                status.text = "手机已拔出"
                return
            }
            if (!usb.hasPermission(device)) {
                // The system dialog is as far as this can go without the grant; the attach path
                // asks again on the next plug-in rather than pretending to serve a phone it cannot
                // open.
                status.text = "等待 USB 授权"
                usb.requestPermission(device, null)
                return
            }
            controller.serveUsb(usb, device)
            return
        }
        val host = intent?.getStringExtra(EXTRA_HOST)
        val port = intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it > 0 }
        if (host.isNullOrEmpty()) {
            controller.listen(port ?: DEFAULT_PORT)
        } else {
            controller.connect(host, port ?: DEFAULT_PORT)
        }
    }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_USB_DEVICE_ID = "usb_device_id"

        /** Same port Android Auto uses; whatever a real phone expects to dial. */
        const val DEFAULT_PORT = 5277

        /** Opens the projection screen for an Android phone that is already plugged in. */
        fun intentForUsb(context: Context, deviceId: Int): Intent =
            Intent(context, AapProjectionActivity::class.java).putExtra(EXTRA_USB_DEVICE_ID, deviceId)

        /** Opens the projection screen listening for a phone. */
        fun intentForListening(context: Context, port: Int = DEFAULT_PORT): Intent =
            Intent(context, AapProjectionActivity::class.java).putExtra(EXTRA_PORT, port)

        /** Opens the projection screen dialling a phone or adapter. */
        fun intentForDialing(context: Context, host: String, port: Int = DEFAULT_PORT): Intent =
            Intent(context, AapProjectionActivity::class.java).putExtra(EXTRA_HOST, host).putExtra(EXTRA_PORT, port)
    }
}
