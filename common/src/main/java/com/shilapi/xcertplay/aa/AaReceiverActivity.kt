package com.shilapi.xcertplay.aa

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var lastStats: AaMessages.Stats? = null
    private var phoneName = ""
    private var streamSize = ""

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
        lastStats = null
        phoneName = ""
        streamSize = ""
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
        if (root.width == 0 || root.height == 0) return
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
                x = AaMessages.Touch.clamp(event.x / root.width),
                y = AaMessages.Touch.clamp(event.y / root.height),
            ),
        )
    }

    private val statusTick = object : Runnable {
        override fun run() {
            val current = session
            val state = current?.state?.name ?: "IDLE"
            val fps = rawFrames
            statusView.text = buildString {
                append(host).append(':').append(port).append("  ").append(state).append('\n')
                if (phoneName.isNotEmpty()) append(phoneName).append('\n')
                append("h264 ").append(decoder.renderedFrames).append(" frames")
                if (decoder.streamWidth > 0) append("  ").append(decoder.streamWidth).append('x').append(decoder.streamHeight)
                append('\n')
                if (streamSize.isNotEmpty()) append(streamSize).append('\n')
                append("raw ").append(fps).append(" frames")
                if (rawView.streamWidth > 0) append("  ").append(rawView.streamWidth).append('x').append(rawView.streamHeight)
                if (roundTripUs >= 0) append("  rtt ").append(roundTripUs / 1000).append("ms")
                lastStats?.let { stats ->
                    append('\n').append("phone ").append(stats.encodedFrames).append(" sent, ")
                        .append(stats.droppedFrames).append(" dropped, last ").append(stats.lastFrameBytes).append('B')
                }
            }
            if (state == AaSessionState.STREAMING.name) hintView.visibility = View.INVISIBLE
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
        streamSize = "phone ${hello.width}x${hello.height} @${hello.densityDpi}dpi"
        runOnUiThread {
            hintView.visibility = View.INVISIBLE
            if (hello.codec == AaWire.Codec.H264) surfaceView.visibility = View.VISIBLE
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

    override fun onStats(stats: AaMessages.Stats) {
        lastStats = stats
    }

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
