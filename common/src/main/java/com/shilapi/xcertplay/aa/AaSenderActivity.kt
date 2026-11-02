package com.shilapi.xcertplay.aa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.mirroring.aa.AaMessages
import com.shilapi.xcertplay.mirroring.aa.AaSession
import com.shilapi.xcertplay.mirroring.aa.AaSessionState
import com.shilapi.xcertplay.mirroring.aa.AaWire
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * The phone half of the loopback pair: mirrors this phone's real screen to the head unit and injects
 * the head unit's touches back into it.
 *
 * Screen capture needs the system's per-session consent, so this Activity asks for it on start and
 * hands the grant to [AaProjectionService], which keeps the capture alive once the driver switches to
 * the app they actually want to see. If the driver declines, the encoder falls back to the test
 * pattern rather than streaming a black rectangle.
 *
 * What this is not: the USB accessory handshake and Android Auto's own protocol. Those need real
 * hardware and Google's transport, and are documented as gaps in `docs/ANDROID_MODE.md`.
 */
class AaSenderActivity : Activity(), AaSession.Listener {

    companion object {
        private const val EXTRA_PORT = "aa_sender_port"
        private const val EXTRA_CODEC = "aa_sender_codec"
        private const val REQUEST_PROJECTION = 0x0AA1
        private const val TAG = "DiPlayAa"

        /** Long edge of the mirrored video: readable on a head unit, cheap for a phone encoder. */
        const val LONG_EDGE = 640
        const val FPS = 15

        /** Video dimensions that keep the phone's aspect ratio, so the mirror is not squashed. */
        fun videoSize(context: Context): Pair<Int, Int> {
            val screen = realScreenSize(context)
            return videoSizeFor(screen.first, screen.second)
        }

        /**
         * The actual mapping, kept free of Android types so the aspect maths is unit-testable.
         * Both edges are rounded to even numbers, because some encoders reject odd dimensions.
         */
        fun videoSizeFor(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
            val long = max(screenWidth, screenHeight).coerceAtLeast(1)
            val scale = LONG_EDGE.toFloat() / long
            val width = even((screenWidth * scale).toInt())
            val height = even((screenHeight * scale).toInt())
            return width to height
        }

        /** Physical display size in pixels: what the projection produces and touches must hit. */
        fun realScreenSize(context: Context): Pair<Int, Int> {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return 1080 to 1920
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = manager.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
            }
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealMetrics(metrics)
            val width = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
            val height = if (metrics.heightPixels > 0) metrics.heightPixels else 1920
            return width to height
        }

        /** Video encoders want even dimensions; an odd one silently fails on some codecs. */
        private fun even(value: Int): Int = (value.coerceAtLeast(2) / 2) * 2

        fun intent(
            context: Context,
            port: Int = AaPreferences.senderPort(context),
            codec: Int = AaPreferences.codec(context),
        ): Intent = Intent(context, AaSenderActivity::class.java)
            .putExtra(EXTRA_PORT, port)
            .putExtra(EXTRA_CODEC, codec)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var touchView: TextView

    private var port = AaPreferences.DEFAULT_PORT
    private var codecKind = AaWire.Codec.H264
    private var videoWidth = 360
    private var videoHeight = 640

    @Volatile
    private var session: AaSession? = null

    @Volatile
    private var pipeline: AaSenderPipeline? = null

    private var server: ServerSocket? = null
    private var serverThread: Thread? = null

    /** Written by the session listener, read by the UI tick; volatile keeps the hand-off visible. */
    @Volatile
    private var lastTouch = ""

    /** Where the current gesture started, so a drag reaches the phone as one stroke. */
    private var pressX = 0f
    private var pressY = 0f
    private var pressAt = 0L

    private var consentRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        port = intent?.getIntExtra(EXTRA_PORT, AaPreferences.senderPort(this)) ?: AaPreferences.senderPort(this)
        codecKind = intent?.getIntExtra(EXTRA_CODEC, AaPreferences.codec(this)) ?: AaPreferences.codec(this)
        val size = videoSize(this)
        videoWidth = size.first
        videoHeight = size.second
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        running.set(true)
        startServer()
        handler.post(statusTick)
        requestScreenCapture()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(dp(20), dp(24), dp(20), dp(20))
            gravity = Gravity.TOP
        }
        root.addView(
            TextView(this).apply {
                text = getString(com.shilapi.xcertplay.host.R.string.aa_sender_title)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            },
        )
        hintView = TextView(this).apply {
            text = getString(com.shilapi.xcertplay.host.R.string.aa_sender_waiting, port)
            setTextColor(Color.parseColor("#90A4AE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(10), 0, dp(14))
        }
        root.addView(hintView)
        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#CFD8DC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        touchView = TextView(this).apply {
            setTextColor(Color.parseColor("#80CBC4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(touchView)
        touchView.setOnClickListener { if (!AaTouchInjector.isReady()) openAccessibilitySettings() }
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // --- Screen capture consent ---

    private fun requestScreenCapture() {
        if (!AaProjectionService.supported()) {
            AaProjection.fail("screen capture needs Android 5.0")
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            AaProjection.fail("no MediaProjectionManager")
            return
        }
        consentRequested = true
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
        } catch (error: Throwable) {
            AaProjection.fail(error.message ?: error.javaClass.simpleName)
        }
    }

    @Deprecated("The consent dialog has no result API alternative before API 30")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            AaProjectionService.start(this, resultCode, data)
        } else {
            AaProjection.fail(getString(com.shilapi.xcertplay.host.R.string.aa_projection_denied))
        }
    }

    /** Opens the accessibility settings page so the driver can enable touch injection. */
    private fun openAccessibilitySettings() {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Throwable) {
        }
    }

    // --- Transport ---

    private fun startServer() {
        serverThread = Thread({
            try {
                val listener = ServerSocket()
                listener.reuseAddress = true
                listener.bind(InetSocketAddress(port), 1)
                server = listener
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val socket = try {
                        listener.accept()
                    } catch (error: Throwable) {
                        break
                    }
                    socket.tcpNoDelay = true
                    android.util.Log.i(TAG, "receiver connected from ${socket.inetAddress?.hostAddress}")
                    val created = AaSession(
                        duplex = SocketAaDuplex(socket),
                        role = AaSession.Role.SENDER,
                        listener = this,
                        hello = AaMessages.Hello(
                            width = videoWidth,
                            height = videoHeight,
                            densityDpi = resources.displayMetrics.densityDpi,
                            codec = codecKind,
                            deviceName = Build.MODEL ?: "android",
                            statsIntervalMs = 1_000,
                        ),
                    )
                    session = created
                    created.begin()
                    created.run()
                    session = null
                    pipeline?.stop()
                    pipeline = null
                    runOnUiThread { onPeerGone("receiver disconnected") }
                }
            } catch (error: Throwable) {
                android.util.Log.w(TAG, "listen on $port failed", error)
                runOnUiThread { onPeerGone(error.message ?: "listen failed") }
            }
        }, "aa-listen").apply {
            isDaemon = true
            start()
        }
    }

    private fun onPeerGone(reason: String) {
        hintView.text = getString(com.shilapi.xcertplay.host.R.string.aa_sender_waiting, port)
        statusView.text = reason
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statusTick)
        running.set(false)
        pipeline?.stop()
        pipeline = null
        session?.close("activity destroyed")
        session = null
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        serverThread?.interrupt()
        AaProjectionService.stop(this)
    }

    private val statusTick = object : Runnable {
        override fun run() {
            val active = session
            val producer = pipeline
            val lines = StringBuilder()
            lines.append("port ").append(port)
            lines.append("   codec ").append(if (codecKind == AaWire.Codec.H264) "H.264" else "raw")
            lines.append('\n')
            lines.append("state ").append(active?.state?.name ?: "LISTENING").append('\n')
            lines.append("source ").append(producer?.source ?: if (AaProjection.isReady()) "screen" else "pattern")
            lines.append("   ").append(videoWidth).append('x').append(videoHeight)
            lines.append(" @").append(FPS).append("fps\n")
            if (active?.state == AaSessionState.STREAMING) {
                lines.append("frames ").append(producer?.frameCount ?: 0L)
                lines.append("   sent ").append((producer?.bytesSent ?: 0L) / 1024).append(" KiB\n")
            }
            lines.append("inject ").append(if (AaTouchInjector.isReady()) "ready" else "off")
            if (AaTouchInjector.isReady()) lines.append("   ").append(AaTouchInjector.gestureCount())
            lines.append('\n')
            AaProjection.lastError?.let { lines.append("capture: ").append(it) }
            statusView.text = lines
            hintView.text = when {
                active?.state == AaSessionState.STREAMING ->
                    getString(com.shilapi.xcertplay.host.R.string.aa_sender_streaming)

                AaProjection.lastError != null -> AaProjection.lastError

                !AaProjection.isReady() && consentRequested ->
                    getString(com.shilapi.xcertplay.host.R.string.aa_projection_waiting)

                else -> getString(com.shilapi.xcertplay.host.R.string.aa_sender_waiting, port)
            }
            touchView.text = lastTouch
            handler.postDelayed(this, 500)
        }
    }

    // --- AaSession.Listener, on the listener thread ---

    override fun onEstablished(hello: AaMessages.Hello, ack: AaMessages.HelloAck?) {
        android.util.Log.i(TAG, "receiver ready: ${ack?.receiverName}")
        val active = session ?: return
        val producer = AaSenderPipeline(
            codecKind = codecKind,
            width = videoWidth,
            height = videoHeight,
            fps = FPS,
            densityDpi = resources.displayMetrics.densityDpi,
            sink = object : AaSenderPipeline.Sink {
                override fun onConfig(payload: ByteArray) = active.sendConfig(codecKind, payload)

                override fun onFrame(codec: Int, ptsUs: Long, payload: ByteArray) =
                    active.sendVideo(codec, ptsUs, payload)

                override fun onPacingTick(sentFrames: Long, bytes: Long) {
                    active.sendStats(
                        AaMessages.Stats(
                            encodedFrames = sentFrames,
                            droppedFrames = 0,
                            lastFrameBytes = (bytes / sentFrames.coerceAtLeast(1)).toInt(),
                            keyFrames = 0,
                            width = videoWidth,
                            height = videoHeight,
                        ),
                    )
                }
            },
            log = { android.util.Log.i(TAG, it) },
        )
        // Read the grant per session: the consent dialog may be answered after the car already
        // connected, and the next session must pick up the projection without restarting the app.
        producer.projection = AaProjection.projection
        pipeline = producer
        producer.start()
        runOnUiThread {
            hintView.text = getString(com.shilapi.xcertplay.host.R.string.aa_sender_streaming)
        }
    }

    override fun onTouch(touch: AaMessages.Touch) {
        // The pattern fallback still draws a marker, so a driver whose capture was refused can see
        // the input arriving even though the phone screen is not on screen.
        pipeline?.setTouch(touch.x, touch.y)
        val injectorReady = AaTouchInjector.isReady()
        when (AaTouchInjector.actionOf(touch.action)) {
            "down" -> {
                pressX = touch.x
                pressY = touch.y
                pressAt = System.currentTimeMillis()
            }

            "up" -> {
                val duration = (System.currentTimeMillis() - pressAt).coerceIn(1L, 600L)
                val screen = realScreenSize(this)
                val injected = if (injectorReady) {
                    AaTouchInjector.inject(screen.first, screen.second, pressX, pressY, touch.x, touch.y, duration)
                } else {
                    false
                }
                lastTouch = buildString {
                    append("tap ").append(fmt(touch.x)).append('/').append(fmt(touch.y))
                    append(if (injected) "  injected" else "  injector off")
                }
            }

            else -> {
                lastTouch = "drag " + fmt(touch.x) + "/" + fmt(touch.y)
            }
        }
        android.util.Log.i(TAG, "touch ${touch.x}/${touch.y} action ${touch.action}")
    }

    private fun fmt(value: Float): String = String.format(java.util.Locale.US, "%.3f", value)

    override fun onClosed(reason: String) {
        android.util.Log.i(TAG, "session closed: $reason")
        pipeline?.stop()
        pipeline = null
    }
}
