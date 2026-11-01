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

/**
 * The phone half of the loopback pair: listens for the head unit, then streams a test pattern.
 *
 * On a real phone this Activity stands in for Android Auto's projection service; on an emulator it
 * is the only way to produce a stream at all, because an emulator has no USB host port. Both ends
 * speak the same [AaSession], so what is verified here is the protocol, the codec path and the input
 * round trip — not the USB accessory handshake, which needs real hardware.
 */
class AaSenderActivity : Activity(), AaSession.Listener {

    companion object {
        private const val EXTRA_PORT = "aa_sender_port"
        private const val EXTRA_CODEC = "aa_sender_codec"
        private const val TAG = "DiPlayAa"

        /** Small enough for an emulator's software encoder, large enough to judge picture quality. */
        const val WIDTH = 640
        const val HEIGHT = 360
        const val FPS = 15

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

    @Volatile
    private var session: AaSession? = null

    @Volatile
    private var pipeline: AaSenderPipeline? = null

    private var server: ServerSocket? = null
    private var serverThread: Thread? = null
    private var lastTouch = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        port = intent?.getIntExtra(EXTRA_PORT, AaPreferences.senderPort(this)) ?: AaPreferences.senderPort(this)
        codecKind = intent?.getIntExtra(EXTRA_CODEC, AaPreferences.codec(this)) ?: AaPreferences.codec(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        running.set(true)
        startServer()
        handler.post(statusTick)
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
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
                            width = WIDTH,
                            height = HEIGHT,
                            densityDpi = resources.displayMetrics.densityDpi,
                            codec = codecKind,
                            deviceName = android.os.Build.MODEL ?: "android",
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
            if (active?.state == AaSessionState.STREAMING) {
                lines.append(WIDTH).append('x').append(HEIGHT).append(" @").append(FPS).append("fps\n")
                lines.append("frames ").append(producer?.frameCount ?: 0L)
                lines.append("   sent ").append((producer?.bytesSent ?: 0L) / 1024).append(" KiB")
            }
            statusView.text = lines
            if (active?.state == AaSessionState.STREAMING) {
                hintView.text = getString(com.shilapi.xcertplay.host.R.string.aa_sender_streaming)
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
            width = WIDTH,
            height = HEIGHT,
            fps = FPS,
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
                            width = WIDTH,
                            height = HEIGHT,
                        ),
                    )
                }
            },
            log = { android.util.Log.i(TAG, it) },
        )
        pipeline = producer
        producer.start()
        runOnUiThread {
            hintView.text = getString(com.shilapi.xcertplay.host.R.string.aa_sender_streaming)
        }
    }

    override fun onTouch(touch: AaMessages.Touch) {
        pipeline?.setTouch(touch.x, touch.y)
        lastTouch = "touch ${touch.x} / ${touch.y}  action ${touch.action}"
    }

    override fun onClosed(reason: String) {
        android.util.Log.i(TAG, "session closed: $reason")
        pipeline?.stop()
        pipeline = null
    }
}
