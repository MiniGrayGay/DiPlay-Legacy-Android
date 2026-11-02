package com.shilapi.xcertplay.aa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.mirroring.aa.AaMessages
import com.shilapi.xcertplay.mirroring.aa.AaSession
import com.shilapi.xcertplay.mirroring.aa.AaSessionState
import com.shilapi.xcertplay.mirroring.aa.AaWire
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The head-unit half of the Android mode: connects to the phone, decodes what it sends, and returns
 * touches. Both a [SurfaceView] (H.264) and a [AaRawFrameView] (raw) are mounted; whichever codec the
 * phone uses is the one brought to the front, so a fallback needs no restart.
 */
class AaReceiverActivity : Activity(), AaSession.Listener {

    companion object {
        private const val EXTRA_HOST = "aa_host"
        private const val EXTRA_PORT = "aa_port"
        private const val TAG = "DiPlayAa"

        /** How long the diagnostics strip stays up after the picture appears. */
        private const val STATS_VISIBLE_MS = 6_000L

        fun intent(context: Context, host: String = AaPreferences.receiverHost(context), port: Int = AaPreferences.receiverPort(context)): Intent =
            Intent(context, AaReceiverActivity::class.java)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayBlockingQueue<VideoJob>(3)

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var rawView: AaRawFrameView
    private lateinit var statusView: TextView
    private lateinit var hintView: TextView

    private val decoder = AaVideoDecoder { android.util.Log.i(TAG, it) }

    private var host: String = AaPreferences.DEFAULT_HOST
    private var port: Int = AaPreferences.DEFAULT_PORT

    @Volatile
    private var session: AaSession? = null

    private var connectionThread: Thread? = null
    private var decodeThread: Thread? = null
    private val running = AtomicBoolean(false)
    private var surfaceReady = false

    private var roundTripUs = -1L
    private var rawFrames = 0L
    private var phoneName = ""

    /** What the phone said it is sending; drives the aspect-fit and the touch mapping. */
    private var videoWidth = 0
    private var videoHeight = 0

    /** The stats strip is useful while connecting and noise afterwards, so it fades on streaming. */
    private var statsHiddenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent?.getStringExtra(EXTRA_HOST) ?: AaPreferences.receiverHost(this)
        port = intent?.getIntExtra(EXTRA_PORT, AaPreferences.receiverPort(this)) ?: AaPreferences.receiverPort(this)
        buildUi()
        startDecodeThread()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // The surface stays mounted and visible for the whole session: an invisible SurfaceView has
        // no consumer, so the decoder blocks on its first frame and the input queue stays full. The
        // raw view is drawn above it instead, which costs nothing and keeps both paths warm.
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                decoder.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                decoder.setSurface(null)
            }
        })
        root.addView(surfaceView, fullScreen())

        rawView = AaRawFrameView(this).apply { visibility = View.INVISIBLE }
        root.addView(rawView, fullScreen())

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.TOP or Gravity.START },
        )

        hintView = TextView(this).apply {
            setTextColor(Color.parseColor("#B0BEC5"))
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            text = getString(com.shilapi.xcertplay.host.R.string.aa_receiver_waiting, "$host:$port")
        }
        root.addView(
            hintView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER },
        )

        root.setOnTouchListener { _, event ->
            forwardTouch(event)
            true
        }
        // Rotation or a window resize changes the available box but not the phone's aspect ratio.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVideoAspect() }
        setContentView(root)
    }

    private fun fullScreen() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun startDecodeThread() {
        running.set(true)
        decodeThread = Thread({
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val job = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return@Thread
                } ?: continue
                when (job.kind) {
                    VideoJob.KIND_CONFIG -> decoder.setConfig(job.payload)
                    VideoJob.KIND_H264 -> decoder.submit(job.payload, job.ptsUs)
                    VideoJob.KIND_RAW -> {
                        rawFrames++
                        runOnUiThread {
                            if (rawView.visibility != View.VISIBLE) {
                                rawView.visibility = View.VISIBLE
                                hintView.visibility = View.INVISIBLE
                            }
                            rawView.setFrame(job.payload)
                        }
                    }
                }
            }
        }, "aa-decode").apply {
            isDaemon = true
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        connect()
        handler.post(statusTick)
        handler.postDelayed(pingTick, 2_000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusTick)
        handler.removeCallbacks(pingTick)
        disconnect("paused")
    }

    override fun onDestroy() {
        super.onDestroy()
        running.set(false)
        decodeThread?.interrupt()
        decoder.stop()
        rawView.release()
    }

    private fun connect() {
        if (connectionThread != null) return
        // Counters belong to one session; carrying them across a reconnect makes the overlay claim
        // frames that were never rendered on this link.
        rawFrames = 0L
        roundTripUs = -1L
        phoneName = ""
        hintView.visibility = View.VISIBLE
        hintView.text = getString(com.shilapi.xcertplay.host.R.string.aa_receiver_waiting, "$host:$port")
        connectionThread = Thread({
            try {
                val duplex = SocketAaDuplex.connect(host, port, 5_000)
                val created = AaSession(
                    duplex = duplex,
                    role = AaSession.Role.RECEIVER,
                    listener = this,
                    receiverName = "DiPlay",
                )
                session = created
                created.begin()
                created.run()
            } catch (error: Throwable) {
                android.util.Log.w(TAG, "connect to $host:$port failed", error)
                runOnUiThread { onTransportLost(error.message ?: "connect failed") }
            }
        }, "aa-connect").apply {
            isDaemon = true
            start()
        }
    }

    private fun disconnect(reason: String) {
        session?.close(reason)
        session = null
        connectionThread?.interrupt()
        connectionThread = null
        queue.clear()
        decoder.setSurface(if (surfaceReady) surfaceView.holder.surface else null)
    }

    private fun onTransportLost(reason: String) {
        connectionThread = null
        rawView.visibility = View.INVISIBLE
        hintView.visibility = View.VISIBLE
        hintView.text = getString(com.shilapi.xcertplay.host.R.string.aa_receiver_retrying, reason)
        // The sender may still be starting up; retry instead of making the driver tap again.
        handler.postDelayed({
            if (!isFinishing && !isDestroyed) connect()
        }, 2_000)
    }

    private fun forwardTouch(event: MotionEvent) {
        val active = session ?: return
        // Map against the mirrored picture, not the window: the letterbox around a portrait phone on
        // a landscape head unit is not part of the phone, and a tap there must not become (0.02, 0.5).
        val view = surfaceView
        if (view.width == 0 || view.height == 0) return
        val x = (event.x - view.left) / view.width
        val y = (event.y - view.top) / view.height
        if (x < 0f || x > 1f || y < 0f || y > 1f) return
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> AaWire.TouchAction.DOWN
            MotionEvent.ACTION_MOVE -> AaWire.TouchAction.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> AaWire.TouchAction.UP
            else -> return
        }
        active.sendTouch(
            AaMessages.Touch(
                action = action,
                pointerId = 0,
                x = AaMessages.Touch.clamp(x),
                y = AaMessages.Touch.clamp(y),
            ),
        )
    }

    /**
     * Sizes the surfaces to the phone's aspect ratio and centres them.
     *
     * A SurfaceView filled to the window makes the decoder stretch the buffer to the window too, so a
     * 9:16 phone arrives squashed on a 16:9 head unit. Matching the view to the stream keeps pixels
     * square, and it is what makes [forwardTouch] able to map a tap onto the right place.
     */
    private fun applyVideoAspect() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val width = root.width
        val height = root.height
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val targetWidth = (videoWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (videoHeight * scale).toInt().coerceAtLeast(1)
        for (view in arrayOf<android.view.View>(surfaceView, rawView)) {
            val params = (view.layoutParams as? FrameLayout.LayoutParams) ?: continue
            if (params.width == targetWidth && params.height == targetHeight) continue
            params.width = targetWidth
            params.height = targetHeight
            params.gravity = Gravity.CENTER
            view.layoutParams = params
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            val current = session
            val state = current?.state?.name ?: "IDLE"
            val fps = rawFrames
            // One line, top-left: enough to tell live from frozen without covering the mirror.
            statusView.text = buildString {
                append(host).append(':').append(port).append("  ").append(state)
                if (phoneName.isNotEmpty()) append("  ").append(phoneName)
                append("  h264 ").append(decoder.renderedFrames).append('f')
                if (decoder.streamWidth > 0) {
                    append(' ').append(decoder.streamWidth).append('x').append(decoder.streamHeight)
                }
                if (fps > 0) append("  raw ").append(fps).append('f')
                if (roundTripUs >= 0) append("  ").append(roundTripUs / 1000).append("ms")
            }
            if (state == AaSessionState.STREAMING.name) {
                hintView.visibility = View.INVISIBLE
                // The driver wants the phone's screen, not diagnostics: drop the strip once the
                // picture has been up long enough to look settled.
                val now = SystemClock.elapsedRealtime()
                if (statsHiddenAt == 0L) {
                    statsHiddenAt = now
                } else if (statusView.visibility == View.VISIBLE && now - statsHiddenAt > STATS_VISIBLE_MS) {
                    statusView.visibility = View.GONE
                }
            } else {
                statsHiddenAt = 0L
                statusView.visibility = View.VISIBLE
            }
            handler.postDelayed(this, 500)
        }
    }

    private val pingTick = object : Runnable {
        override fun run() {
            session?.ping()
            handler.postDelayed(this, 2_000)
        }
    }

    // --- AaSession.Listener: called on the connection thread ---

    override fun onStateChanged(state: AaSessionState) {
        android.util.Log.i(TAG, "session state $state")
    }

    override fun onEstablished(hello: AaMessages.Hello, ack: AaMessages.HelloAck?) {
        phoneName = "${hello.deviceName} ${hello.width}x${hello.height}"
        videoWidth = hello.width
        videoHeight = hello.height
        runOnUiThread {
            hintView.visibility = View.INVISIBLE
            statsHiddenAt = 0L
            statusView.visibility = View.VISIBLE
            if (hello.codec == AaWire.Codec.H264) surfaceView.visibility = View.VISIBLE
            applyVideoAspect()
        }
    }

    override fun onConfig(codec: Int, payload: ByteArray) {
        offer(VideoJob(VideoJob.KIND_CONFIG, payload, 0))
    }

    override fun onVideo(codec: Int, ptsUs: Long, payload: ByteArray) {
        if (codec == AaWire.Codec.H264) {
            runOnUiThread {
                if (rawView.visibility == View.VISIBLE) rawView.visibility = View.INVISIBLE
                if (hintView.visibility == View.VISIBLE) hintView.visibility = View.INVISIBLE
            }
            offer(VideoJob(VideoJob.KIND_H264, payload, ptsUs))
        } else {
            offer(VideoJob(VideoJob.KIND_RAW, payload, ptsUs))
        }
    }

    override fun onStats(stats: AaMessages.Stats) = Unit

    override fun onRoundTripUs(micros: Long) {
        roundTripUs = micros
    }

    override fun onClosed(reason: String) {
        android.util.Log.i(TAG, "session closed: $reason")
        session = null
        runOnUiThread {
            connectionThread = null
            if (!isFinishing && !isDestroyed) onTransportLost(reason)
        }
    }

    /** Keeps latency low: a mirror that shows three-second-old frames is worse than a dropped one. */
    private fun offer(job: VideoJob) {
        if (!queue.offer(job)) {
            queue.poll()
            queue.offer(job)
        }
    }

    private class VideoJob(val kind: Int, val payload: ByteArray, val ptsUs: Long) {
        companion object {
            const val KIND_CONFIG = 0
            const val KIND_H264 = 1
            const val KIND_RAW = 2
        }
    }
}
