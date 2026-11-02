package com.shilapi.xcertplay.adb

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.security.KeyPair

/**
 * Android mode as the driver experiences it: the phone's screen fills the head unit, touches go back,
 * and nothing was installed on the phone.
 *
 * The phone is driven entirely through its own `adbd` — see [AdbMirrorSession]. Two ways in: the cable
 * (the phone's ADB interface, claimed by [UsbAdbTransport]) and an address (a phone whose `adbd` is on
 * the network, which is also how this is verified on emulators).
 */
class AdbMirrorActivity : Activity(), AdbMirrorSession.Listener {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var hintView: TextView

    private var session: AdbMirrorSession? = null
    private var transport: AdbTransport? = null
    private var receiverRegistered = false

    private var videoWidth = 0
    private var videoHeight = 0
    private var title = ""

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val usbDeviceId = intent.getIntExtra(EXTRA_USB_DEVICE_ID, -1)
        if (usbDeviceId >= 0) {
            connectOverUsb(usbDeviceId)
        } else {
            connectOverNetwork(
                intent.getStringExtra(EXTRA_HOST) ?: AdbPreferences.phoneHost(this),
                intent.getIntExtra(EXTRA_PORT, AdbPreferences.phonePort(this)),
            )
        }
        handler.post(statusTick)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.setSurface(null)
        session?.stop()
        session = null
        transport = null
        if (receiverRegistered) {
            runCatching { unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    // region entry points

    private fun connectOverNetwork(host: String, port: Int) {
        title = "$host:$port"
        hintView.text = getString(com.shilapi.xcertplay.host.R.string.aa_receiver_waiting, title)
        Thread {
            val opened = runCatching { TcpAdbTransport.connect(host, port) }.getOrElse { error ->
                handler.post { showHint("adb connect failed: ${error.message}") }
                return@Thread
            }
            startSession(opened)
        }.apply { isDaemon = true }.start()
    }

    private fun connectOverUsb(deviceId: Int) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId }
        if (device == null) {
            showHint("usb device gone")
            return
        }
        title = device.productName ?: device.deviceName
        hintView.text = getString(com.shilapi.xcertplay.host.R.string.aa_receiver_waiting, title)
        if (UsbAdbTransport.adbInterfaceIndex(device) < 0) {
            // The one thing the driver has to do once: turn on USB debugging, so the phone exposes the
            // interface adbd lives behind. Nothing is installed.
            showHint(getString(com.shilapi.xcertplay.host.R.string.settings_usb_debugging_required_message))
            return
        }
        if (!manager.hasPermission(device)) {
            registerUsbReceiver(deviceId)
            manager.requestPermission(
                device,
                PendingIntent.getBroadcast(
                    this,
                    USB_PERMISSION_REQUEST,
                    Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            return
        }
        openUsb(deviceId)
    }

    private fun registerUsbReceiver(deviceId: Int) {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        // Android 14 refuses a receiver that is not explicitly exported or not exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
        pendingUsbDeviceId = deviceId
    }

    private var pendingUsbDeviceId = -1

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val deviceId = pendingUsbDeviceId
            pendingUsbDeviceId = -1
            if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                showHint("usb permission denied")
                return
            }
            if (deviceId >= 0) openUsb(deviceId)
        }
    }

    private fun openUsb(deviceId: Int) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return
        Thread {
            val connection = manager.openDevice(device)
            if (connection == null) {
                handler.post { showHint("cannot open the phone") }
                return@Thread
            }
            val usbTransport = UsbAdbTransport.open(device, connection)
            if (usbTransport == null) {
                handler.post {
                    showHint(getString(com.shilapi.xcertplay.host.R.string.settings_usb_debugging_required_message))
                }
                return@Thread
            }
            startSession(usbTransport)
        }.apply { isDaemon = true }.start()
    }

    private fun startSession(opened: AdbTransport) {
        val key: KeyPair = AdbKeys.load(this)
        val created = AdbMirrorSession(opened, key, this)
        transport = opened
        session = created
        handler.post {
            created.setSurface((surfaceView.holder).surface)
            created.start()
        }
    }

    // endregion

    // region AdbMirrorSession.Listener

    override fun onStatus(status: String) {
        handler.post { Log.i(TAG, status) }
    }

    override fun onVideoSize(width: Int, height: Int) {
        handler.post {
            videoWidth = width
            videoHeight = height
            applyVideoAspect()
            hintView.visibility = View.INVISIBLE
        }
    }

    override fun onEnded(reason: String) {
        handler.post { showHint(reason) }
    }

    // endregion

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // The surface stays visible for the whole session: a hidden Surface has no consumer and the
        // decoder then blocks on a full input queue instead of failing loudly.
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                session?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                session?.setSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                session?.setSurface(null)
            }
        })
        surfaceView.setOnTouchListener { _, event -> handleTouch(event) }

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 11f
            setPadding(dp(12), dp(8), dp(12), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            )
        }
        hintView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        root.addView(surfaceView)
        root.addView(statusView)
        root.addView(hintView)
        setContentView(root)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val width = surfaceView.width
        val height = surfaceView.height
        if (width == 0 || height == 0) return false
        // Map against the picture, not the window: the letterbox around a portrait phone is not part of
        // the phone, and a tap there must not land on it.
        val x = (event.x / width).coerceIn(0f, 1f)
        val y = (event.y / height).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastX = x
                lastY = y
                downAt = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                val duration = (event.eventTime - downAt).toInt()
                session?.tapOrSwipe(downX, downY, lastX, lastY, duration)
            }
        }
        return true
    }

    /**
     * Sizes the surface to the phone's aspect ratio.
     *
     * A SurfaceView filled to the window makes the decoder stretch the buffer to the window too, so a
     * tall phone arrives squashed on a wide head unit — and every touch lands in the wrong place.
     */
    private fun applyVideoAspect() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val width = root.width.takeIf { it > 0 } ?: return
        val height = root.height.takeIf { it > 0 } ?: return
        val scale = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val params = surfaceView.layoutParams as FrameLayout.LayoutParams
        val targetWidth = (videoWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (videoHeight * scale).toInt().coerceAtLeast(1)
        if (params.width == targetWidth && params.height == targetHeight) return
        params.width = targetWidth
        params.height = targetHeight
        params.gravity = Gravity.CENTER
        surfaceView.layoutParams = params
    }

    private val statusTick = object : Runnable {
        override fun run() {
            val current = session
            statusView.text = buildString {
                append(title)
                if (current != null) {
                    append("  ").append(current.renderedFrames).append('f')
                    if (current.videoWidth > 0) append(' ').append(current.videoWidth).append('x').append(current.videoHeight)
                }
            }
            handler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    private fun showHint(message: String) {
        hintView.text = message
        hintView.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val TAG = "DiPlayAdb"
        private const val EXTRA_HOST = "adb_host"
        private const val EXTRA_PORT = "adb_port"
        private const val EXTRA_USB_DEVICE_ID = "adb_usb_device_id"
        private const val ACTION_USB_PERMISSION = "com.shilapi.xcertplay.adb.USB_PERMISSION"
        private const val USB_PERMISSION_REQUEST = 41
        private const val STATUS_INTERVAL_MS = 500L

        /** Address of a phone whose `adbd` is reachable on the network. */
        fun intent(
            context: Context,
            host: String = AdbPreferences.phoneHost(context),
            port: Int = AdbPreferences.phonePort(context),
        ): Intent = Intent(context, AdbMirrorActivity::class.java)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_PORT, port)

        /** The cable: the phone is already attached and [deviceId] identifies it. */
        fun usbIntent(context: Context, deviceId: Int): Intent =
            Intent(context, AdbMirrorActivity::class.java)
                .putExtra(EXTRA_USB_DEVICE_ID, deviceId)
    }
}
