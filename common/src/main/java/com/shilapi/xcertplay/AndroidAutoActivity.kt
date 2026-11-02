package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.aap.AapInputMessages
import com.shilapi.xcertplay.host.R

/**
 * The Android Auto desktop: what a driver sees instead of the head unit's own launcher.
 *
 * This is the Android counterpart of the CarPlay screen, and it is deliberately the same shape:
 * the car does not mirror the phone, it hands the whole screen to the phone, and the phone paints
 * its own car UI (navigation card, media card, the icon rail) into it. What belongs to the car
 * stays here — the waiting screen before the phone connects, the exit back to DiPlay, night mode,
 * and the key/touch contract that the phone never sees.
 *
 * Two surfaces, one activity:
 *
 * - **Waiting.** A DiPlay panel with the link state and how the phone is expected to arrive. It
 *   stays until a decoded frame is on the screen, not until the channel opens: a session that
 *   connects and then fails to decode is exactly the case worth reading text about.
 * - **Projected.** The phone's frame, letterboxed to its aspect ratio, with a control capsule that
 *   appears on demand from a touch strip along the bottom edge. The strip exists so that ordinary
 *   taps in the middle of the screen are still touches on the phone's UI.
 *
 * Launch it either way:
 *
 * ```
 * am start -n <pkg>/com.shilapi.xcertplay.AndroidAutoActivity             # wait for a phone
 * am start ... --es host 10.0.2.2 --ei port 5278                          # dial a phone
 * ```
 */
class AndroidAutoActivity : Activity(), AapProjectionController.Listener {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var waitingPanel: View
    private lateinit var waitingTitle: TextView
    private lateinit var waitingStatus: TextView
    private lateinit var waitingDetail: TextView
    private lateinit var waitingDot: View
    private lateinit var controlBar: View
    private lateinit var controlStatus: TextView
    private lateinit var logPanel: TextView
    private lateinit var touchStrip: View
    private lateinit var controller: AapProjectionController
    private val main = Handler(Looper.getMainLooper())

    private var videoWidth = 0
    private var videoHeight = 0
    private var lastTouchX = -1
    private var lastTouchY = -1
    private var projected = false
    private var controlBarVisible = false
    private var logVisible = false
    private var phoneName: String? = null
    private var lastStatus: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        root.addView(buildWaitingPanel())
        root.addView(buildLogPanel())
        root.addView(buildTouchStrip())
        root.addView(buildControlBar())
        setContentView(root)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutChrome() }
        root.post { layoutChrome() }

        controller = AapProjectionController(this, this)

        surfaceView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                controller.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                controller.setSurface(holder.surface)
                layoutSurface()
            }

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                controller.setSurface(null)
            }
        })
        surfaceView.setOnTouchListener { view, event -> onVideoTouch(view, event) }

        startFrom(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            controller.stop()
            projected = false
            showWaiting(getString(R.string.aa_desktop_reconnecting))
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

    // ------------------------------------------------------------------ keys

    /**
     * Every car key goes to the phone, back included: inside Android Auto, back is "leave this
     * screen", which is the phone's decision. Leaving the projection is the capsule's job, and the
     * bottom strip makes it reachable without a keyboard.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            -> {
                if (!projected) return super.onKeyDown(keyCode, event)
                controller.sendKey(keyCode, down = true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!projected) return super.onKeyUp(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            controller.sendKey(keyCode, down = false)
            return true
        }
        if (KeyEvent.KEYCODE_DPAD_UP <= keyCode && keyCode <= KeyEvent.KEYCODE_VOLUME_DOWN) {
            controller.sendKey(keyCode, down = false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Brings the waiting panel back, e.g. when the caller asks for a different phone or link. */
    private fun showWaiting(status: String) {
        waitingTitle.text = getString(R.string.aa_desktop_title)
        waitingStatus.text = status
        waitingDetail.text = detailLine()
        waitingDot.setBackgroundColor(ACCENT)
        waitingPanel.visibility = View.VISIBLE
        waitingPanel.alpha = 1f
    }

    // ------------------------------------------------------------------ controller callbacks

    override fun onStatus(text: String) {
        lastStatus = text
        main.post {
            if (!projected) {
                waitingStatus.text = text
                waitingDetail.text = detailLine()
            } else {
                controlStatus.text = text
            }
            logPanel.text = AapDiagnostics.snapshot().takeLast(8).joinToString("\n")
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
        main.post {
            waitingStatus.text = getString(R.string.aa_desktop_video_ready)
            waitingDetail.text = detailLine()
        }
    }

    override fun onFirstFrame() {
        main.post {
            projected = true
            waitingPanel.visibility = View.GONE
            showControlBar(temporarily = true)
        }
    }

    override fun onPhone(name: String) {
        phoneName = name
        main.post {
            if (!projected) waitingDetail.text = detailLine()
        }
    }

    override fun onEnded(reason: String) {
        main.post {
            projected = false
            hideControlBar()
            waitingTitle.text = getString(R.string.aa_desktop_disconnected)
            waitingStatus.text = reason
            waitingDetail.text = detailLine()
            waitingPanel.visibility = View.VISIBLE
            waitingPanel.alpha = 1f
            waitingDot.setBackgroundColor(WARN)
        }
    }

    // ------------------------------------------------------------------ waiting panel

    private fun buildWaitingPanel(): View {
        val panel = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(BACKGROUND, BACKGROUND_LIFT),
            )
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val badge = FrameLayout(this).apply {
            background = rounded(26f, ACCENT_SOFT)
            addView(
                TextView(this@AndroidAutoActivity).apply {
                    text = "AA"
                    setTextColor(ACCENT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        column.addView(badge, LinearLayout.LayoutParams(dp(96), dp(96)).apply { bottomMargin = dp(22) })

        waitingTitle = TextView(this).apply {
            text = getString(R.string.aa_desktop_title)
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        column.addView(waitingTitle)

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        waitingDot = View(this).apply { background = circle(4f, ACCENT) }
        statusRow.addView(waitingDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) })
        waitingStatus = TextView(this).apply {
            text = getString(R.string.aa_desktop_starting)
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        statusRow.addView(waitingStatus)
        column.addView(statusRow)

        waitingDetail = TextView(this).apply {
            text = detailLine()
            setTextColor(FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(18), dp(40), 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        column.addView(waitingDetail)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        }
        actions.addView(
            capsuleButton(getString(R.string.aa_desktop_leave)) { finish() },
            LinearLayout.LayoutParams(dp(132), dp(46)).apply { rightMargin = dp(12) },
        )
        actions.addView(
            capsuleButton(getString(R.string.aa_desktop_show_log), quiet = true) { toggleLog() },
            LinearLayout.LayoutParams(dp(132), dp(46)),
        )
        column.addView(actions)

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        wrap.addView(column)
        panel.addView(
            wrap,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        panel.setOnClickListener { showControlBar(temporarily = true) }
        return panel.also { waitingPanel = it }
    }

    /** The line under the status: how the phone is expected to arrive, once it is known. */
    private fun detailLine(): String = phoneName?.let { getString(R.string.aa_desktop_phone_line, it) }
        ?: getString(R.string.aa_desktop_waiting_hint)

    // ------------------------------------------------------------------ control capsule

    private fun buildControlBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(28f, CAPSULE, stroke = 1f, strokeColor = CAPSULE_STROKE)
            visibility = View.GONE
            alpha = 0f
        }
        controlStatus = TextView(this).apply {
            text = getString(R.string.aa_desktop_connected)
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), 0, dp(10), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(controlStatus, LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(
            capsuleButton(getString(R.string.aa_desktop_leave), compact = true) { leaveProjection() },
            LinearLayout.LayoutParams(dp(104), dp(38)),
        )
        bar.addView(
            capsuleButton(getString(R.string.aa_desktop_show_log), quiet = true, compact = true) { toggleLog() },
            LinearLayout.LayoutParams(dp(88), dp(38)).apply { leftMargin = dp(8) },
        )
        return bar.also { controlBar = it }
    }

    private fun showControlBar(temporarily: Boolean) {
        if (!projected) return
        controlBarVisible = true
        controlBar.visibility = View.VISIBLE
        controlBar.animate().alpha(1f).setDuration(160).start()
        main.removeCallbacks(hideControlBarTask)
        if (temporarily) main.postDelayed(hideControlBarTask, CONTROL_BAR_LINGER_MS)
    }

    private fun hideControlBar() {
        controlBarVisible = false
        main.removeCallbacks(hideControlBarTask)
        controlBar.animate().alpha(0f).setDuration(160).withEndAction {
            if (!controlBarVisible) controlBar.visibility = View.GONE
        }.start()
    }

    private val hideControlBarTask = Runnable { hideControlBar() }

    /**
     * The strip along the bottom edge that brings the capsule back.
     *
     * A strip rather than "tap anywhere": a tap in the middle of the screen belongs to the phone's
     * UI, and stealing it to reveal car chrome is how a projection starts feeling laggy.
     */
    private fun buildTouchStrip(): View {
        val strip = View(this)
        strip.setOnClickListener {
            if (logVisible) toggleLog() else showControlBar(temporarily = true)
        }
        return strip.also { touchStrip = it }
    }

    private fun toggleLog() {
        logVisible = !logVisible
        logPanel.visibility = if (logVisible) View.VISIBLE else View.GONE
        logPanel.text = AapDiagnostics.snapshot().takeLast(8).joinToString("\n")
        if (logVisible) showControlBar(temporarily = false) else showControlBar(temporarily = true)
    }

    private fun buildLogPanel(): View = TextView(this).apply {
        setTextColor(Color.rgb(150, 214, 178))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.MONOSPACE
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = rounded(14f, Color.argb(232, 10, 16, 24), stroke = 1f, strokeColor = CAPSULE_STROKE)
        visibility = View.GONE
    }.also { text -> logPanel = text }

    private fun leaveProjection() {
        controller.stop()
        finish()
    }

    // ------------------------------------------------------------------ layout

    /** Fits the surface to the phone's aspect ratio, centred, so nothing is stretched. */
    private fun layoutSurface() {
        val parentWidth = root.width
        val parentHeight = root.height
        if (parentWidth == 0 || parentHeight == 0) return
        val width = if (videoWidth > 0) videoWidth else 16
        val height = if (videoHeight > 0) videoHeight else 9
        val scale = minOf(parentWidth.toFloat() / width, parentHeight.toFloat() / height)
        surfaceView.layoutParams = FrameLayout.LayoutParams(
            (width * scale).toInt(),
            (height * scale).toInt(),
            Gravity.CENTER,
        )
    }

    /**
     * Places the pieces that depend on the screen size.
     *
     * Called from a layout listener rather than once at start: a head unit that boots in one
     * orientation and then rotates (or a resizable window on a tablet) must not keep capsule sizes
     * computed for the old width.
     */
    private fun layoutChrome() {
        if (root.width == 0 || root.height == 0) return
        if (root.width == chromeWidth && root.height == chromeHeight) return
        chromeWidth = root.width
        chromeHeight = root.height
        layoutSurface()
        val stripParams = touchStrip.layoutParams as? FrameLayout.LayoutParams
        if (stripParams == null || stripParams.height != dp(STRIP_HEIGHT_DP)) {
            touchStrip.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(STRIP_HEIGHT_DP),
                Gravity.BOTTOM,
            )
        }
        controlBar.layoutParams = FrameLayout.LayoutParams(
            minOf(root.width - dp(24), dp(430)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = dp(16) }
        logPanel.layoutParams = FrameLayout.LayoutParams(
            minOf(root.width - dp(32), dp(620)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = dp(12) }
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
        if (controlBarVisible && event.actionMasked == MotionEvent.ACTION_DOWN) {
            // A tap anywhere while the capsule is up only dismisses the capsule: the driver was
            // reaching for the car's controls, not for the phone's UI.
            hideControlBar()
            return true
        }
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

    // ------------------------------------------------------------------ start

    private fun startFrom(intent: Intent?) {
        shownAt = System.currentTimeMillis()
        val deviceId = intent?.getIntExtra(EXTRA_USB_DEVICE_ID, -1) ?: -1
        if (deviceId >= 0) {
            val usb = getSystemService(Context.USB_SERVICE) as? UsbManager
            val device = usb?.deviceList?.values?.firstOrNull { it.deviceId == deviceId }
            if (usb == null || device == null) {
                waitingTitle.text = getString(R.string.aa_desktop_phone_unplugged)
                waitingStatus.text = ""
                waitingDot.setBackgroundColor(WARN)
                return
            }
            if (!usb.hasPermission(device)) {
                waitingStatus.text = getString(R.string.aa_desktop_waiting_usb_permission)
                usb.requestPermission(device, null)
                return
            }
            waitingDetail.text = getString(R.string.aa_desktop_usb_hint)
            controller.serveUsb(usb, device)
            return
        }
        val host = intent?.getStringExtra(EXTRA_HOST)
        val port = intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it > 0 }
        if (host.isNullOrEmpty()) {
            waitingDetail.text = getString(R.string.aa_desktop_network_hint)
            controller.listen(port ?: DEFAULT_PORT)
        } else {
            waitingDetail.text = getString(R.string.aa_desktop_dialing, "$host:${port ?: DEFAULT_PORT}")
            controller.connect(host, port ?: DEFAULT_PORT)
        }
    }

    private var shownAt = 0L
    private var chromeWidth = 0
    private var chromeHeight = 0

    // ------------------------------------------------------------------ small builders

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(radiusDp: Float, fill: Int, stroke: Float = 0f, strokeColor: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setColor(fill)
            if (stroke > 0f) setStroke(dp(stroke.toInt()).coerceAtLeast(1), strokeColor)
        }

    private fun circle(radiusDp: Float, fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setSize(dp(radiusDp.toInt() * 2), dp(radiusDp.toInt() * 2))
    }

    private fun capsuleButton(
        text: String,
        quiet: Boolean = false,
        compact: Boolean = false,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (quiet) {
            setTextColor(MUTED)
            background = rounded(19f, QUIET)
        } else {
            setTextColor(Color.rgb(9, 14, 22))
            background = rounded(19f, ACCENT)
        }
        setOnClickListener { onClick() }
    }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_USB_DEVICE_ID = "usb_device_id"

        /** Android Auto's own port; what AARE's "head unit server" forwards from adb. */
        const val DEFAULT_PORT = 5277

        private const val CONTROL_BAR_LINGER_MS = 4_500L
        private const val STRIP_HEIGHT_DP = 40

        private val BACKGROUND = Color.rgb(8, 12, 19)
        private val BACKGROUND_LIFT = Color.rgb(17, 25, 38)
        private val INK = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
        private val FAINT = Color.rgb(122, 137, 158)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val ACCENT_SOFT = Color.rgb(26, 40, 62)
        private val WARN = Color.rgb(255, 176, 120)
        private val CAPSULE = Color.argb(232, 14, 21, 32)
        private val CAPSULE_STROKE = Color.argb(46, 190, 210, 240)
        private val QUIET = Color.argb(28, 255, 255, 255)

        fun intentForListening(context: Context, port: Int = DEFAULT_PORT): Intent =
            Intent(context, AndroidAutoActivity::class.java).putExtra(EXTRA_PORT, port)

        fun intentForDialing(context: Context, host: String, port: Int = DEFAULT_PORT): Intent =
            Intent(context, AndroidAutoActivity::class.java).putExtra(EXTRA_HOST, host).putExtra(EXTRA_PORT, port)

        fun intentForUsb(context: Context, deviceId: Int): Intent =
            Intent(context, AndroidAutoActivity::class.java).putExtra(EXTRA_USB_DEVICE_ID, deviceId)
    }
}
