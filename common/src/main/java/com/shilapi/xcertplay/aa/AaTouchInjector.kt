package com.shilapi.xcertplay.aa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.shilapi.xcertplay.mirroring.aa.AaWire
import java.util.concurrent.atomic.AtomicLong

/**
 * Injects the head unit's touches into the phone.
 *
 * A normal app cannot post `MotionEvent`s into another window: `INJECT_EVENTS` is a signature
 * permission, and the shell-only `input` command is not reachable from here. An accessibility
 * service may dispatch gestures, which is the one route available to a sideloaded app, so the driver
 * has to enable it once in Settings → Accessibility. Without it the mirror is view-only, and the
 * sender says so instead of silently swallowing touches.
 *
 * Coordinates arrive normalised to the mirrored video. They are scaled to the real display, so the
 * gesture lands where the driver aimed even when the two screens differ in size.
 */
class AaTouchInjector : AccessibilityService() {

    companion object {
        private const val TAG = "DiPlayAa"

        @Volatile
        private var instance: AaTouchInjector? = null

        /** Counts dispatched gestures so the sender can prove the round trip end to end. */
        private val dispatched = AtomicLong()

        fun isReady(): Boolean = instance != null

        fun gestureCount(): Long = dispatched.get()

        /**
         * Plays a tap or a swipe on the real screen. [fromX]/[toX] are normalised 0..1; a press with no
         * travel becomes a tap, which is what most head-unit interactions are.
         */
        fun inject(
            displayWidth: Int,
            displayHeight: Int,
            fromX: Float,
            fromY: Float,
            toX: Float,
            toY: Float,
            durationMs: Long,
        ): Boolean {
            val service = instance ?: return false
            if (displayWidth <= 0 || displayHeight <= 0) return false
            val startX = (fromX.coerceIn(0f, 1f) * (displayWidth - 1)).toInt().toFloat()
            val startY = (fromY.coerceIn(0f, 1f) * (displayHeight - 1)).toInt().toFloat()
            val endX = (toX.coerceIn(0f, 1f) * (displayWidth - 1)).toInt().toFloat()
            val endY = (toY.coerceIn(0f, 1f) * (displayHeight - 1)).toInt().toFloat()
            return service.play(startX, startY, endX, endY, durationMs.coerceIn(1L, 1_000L))
        }

        /** Maps the wire action onto a gesture; null for events that cannot start a gesture. */
        fun actionOf(action: Int): String? = when (action) {
            AaWire.TouchAction.DOWN -> "down"
            AaWire.TouchAction.MOVE -> "move"
            AaWire.TouchAction.UP -> "up"
            else -> null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        android.util.Log.i(TAG, "touch injector ready")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        android.util.Log.i(TAG, "touch injector gone")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun play(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(startX, startY)
            if (startX != endX || startY != endY) lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(gesture, null, null)
        if (accepted) dispatched.incrementAndGet()
        return accepted
    }
}
