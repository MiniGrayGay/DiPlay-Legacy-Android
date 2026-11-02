package com.diplay.aapphone

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.shilapi.xcertplay.aap.AapIdentity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The phone, as an app you can run on an emulator next to the head unit.
 *
 * It is the development peer for the head unit: a real AAP client, with a generated test signal
 * instead of a screen capture, so the whole path can be exercised on two emulators with no phone
 * and no head unit in the room.
 *
 * ```
 * car:    am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.AapProjectionActivity
 * phone:  am start -n com.diplay.aapphone/.AapPhoneActivity --es host 10.0.2.2
 * ```
 *
 * `10.0.2.2` is the host loopback as seen from inside an emulator, so the head unit's listening port
 * has to be forwarded to the host first: `adb -s <car> forward tcp:5277 tcp:5277`.
 */
class AapPhoneActivity : AppCompatActivity(), AapPhoneLink.Listener {

    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var connectButton: Button
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var logView: TextView

    private val previewRenderer = TestPatternRenderer(PREVIEW_WIDTH, PREVIEW_HEIGHT)
    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private lateinit var link: AapPhoneLink
    private var previewBitmap: Bitmap? = null
    private var connected = false

    private val previewTick = object : Runnable {
        override fun run() {
            drawPreview()
            main.postDelayed(this, PREVIEW_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        link = AapPhoneLink(identity()).apply { listener = this@AapPhoneActivity }
        main.post(previewTick)

        // Scripted runs pass the address in, so a two-emulator demo needs no typing.
        intent?.getStringExtra("host")?.takeIf { it.isNotEmpty() }?.let {
            hostField.setText(it)
            main.postDelayed({ connect() }, 600)
        }
        intent?.getIntExtra("port", 0)?.takeIf { it > 0 }?.let { portField.setText(it.toString()) }
    }

    override fun onDestroy() {
        main.removeCallbacks(previewTick)
        link.disconnect()
        previewBitmap?.recycle()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ ui

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        column.addView(
            TextView(this).apply {
                text = "DiPlay AAP 手机端"
                textSize = 22f
            },
        )
        column.addView(
            TextView(this).apply {
                text = "Android Auto 投影的发送端，用来配合车机端做端到端验证。"
                textSize = 12f
                setPadding(0, 6, 0, 18)
            },
        )

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hostField = EditText(this).apply {
            hint = "车机地址"
            setText(DEFAULT_HOST)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        portField = EditText(this).apply {
            hint = "端口"
            setText(DEFAULT_PORT.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        connectButton = Button(this).apply {
            text = "连接"
            setOnClickListener { if (connected) disconnect() else connect() }
        }
        row.addView(hostField)
        row.addView(portField)
        row.addView(connectButton)
        column.addView(row)

        preview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply { topMargin = 18 }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        column.addView(preview)

        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, 14, 0, 4)
            text = "未连接"
        }
        stats = TextView(this).apply {
            textSize = 12f
            text = ""
        }
        logView = TextView(this).apply {
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 8, 0, 0)
        }
        column.addView(status)
        column.addView(stats)
        column.addView(
            ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
                addView(logView)
            },
        )
        return ScrollView(this).apply { addView(column) }
    }

    private fun drawPreview() {
        if (!this::preview.isInitialized) return
        val bitmap = previewBitmap ?: Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888)
            .also { previewBitmap = it }
        previewRenderer.render(bitmap)
        preview.setImageBitmap(bitmap)
    }

    private fun connect() {
        val host = hostField.text.toString().trim()
        val port = portField.text.toString().trim().toIntOrNull() ?: DEFAULT_PORT
        if (host.isEmpty()) {
            status.text = "先填车机地址"
            return
        }
        connected = true
        connectButton.text = "断开"
        AapDiagnosticsLog.add("连接 $host:$port")
        link.connect(host, port)
    }

    private fun disconnect() {
        connected = false
        connectButton.text = "连接"
        link.disconnect()
        status.text = "已断开"
    }

    // ------------------------------------------------------------------ link callbacks

    override fun onStatus(text: String) {
        AapDiagnosticsLog.add(text)
        main.post {
            status.text = text
            logView.text = AapDiagnosticsLog.tail(8)
        }
    }

    override fun onDiscovery(summary: String) {
        main.post { stats.text = summary }
    }

    override fun onTouch(x: Int, y: Int) {
        // Mirror the car's touch onto the preview so both screens show the same point.
        previewRenderer.touchX = x * PREVIEW_WIDTH / 800
        previewRenderer.touchY = y * PREVIEW_HEIGHT / 480
    }

    override fun onNightMode(night: Boolean) {
        main.post { stats.text = if (night) "车机：夜间模式" else "车机：白天模式" }
    }

    override fun onStats(fps: Int, dropped: Int, inFlight: Int) {
        main.post {
            stats.text = "$fps fps · 在途 $inFlight · 丢弃 $dropped · ${clock.format(Date())}"
        }
    }

    override fun onClosed(reason: String) {
        AapDiagnosticsLog.add("断开：$reason")
        main.post {
            connected = false
            connectButton.text = "连接"
            status.text = "已断开：$reason"
            logView.text = AapDiagnosticsLog.tail(8)
        }
    }

    private fun identity(): AapIdentity = sharedIdentity ?: AapIdentity.generate("DiPlay AAP 模拟手机").also {
        sharedIdentity = it
        AapDiagnosticsLog.add("本机证书 ${it.fingerprint.take(23)}…")
    }

    private companion object {
        const val DEFAULT_HOST = "10.0.2.2"
        const val DEFAULT_PORT = 5277
        const val PREVIEW_WIDTH = 400
        const val PREVIEW_HEIGHT = 240
        const val PREVIEW_INTERVAL_MS = 250L

        /**
         * One identity per process. A real phone persists its certificate; for a test peer a fresh
         * one per launch is honest — the head unit's trust is by fingerprint, and a new fingerprint
         * is exactly what "a different phone" looks like.
         */
        var sharedIdentity: AapIdentity? = null
    }
}

/** A short in-memory log, mirrored to logcat, so the on-screen line count stays readable. */
object AapDiagnosticsLog {
    private const val CAPACITY = 60
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(message: String) {
        android.util.Log.i("DiPlayAapPhone", message)
        synchronized(lines) {
            lines.addLast("${clock.format(Date())} $message")
            while (lines.size > CAPACITY) lines.removeFirst()
        }
    }

    fun tail(count: Int): String = synchronized(lines) { lines.toList().takeLast(count).joinToString("\n") }
}
