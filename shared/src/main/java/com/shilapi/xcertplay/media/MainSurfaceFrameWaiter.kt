package com.shilapi.xcertplay.media

import android.view.Surface

/** One-shot submission evidence for one visible main output, independent of session first-frame history. */
internal class MainSurfaceFrameWaiter {
    private data class Pending(val surface: Surface, val callback: (Long) -> Unit)
    private val lock = Any()
    private var pending: Pending? = null
    private var closed = false

    fun await(surface: Surface, callback: (Long) -> Unit) = synchronized(lock) {
        if (!closed) pending = Pending(surface, callback)
    }

    fun cancel(surface: Surface? = null) = synchronized(lock) {
        if (surface == null || pending?.surface === surface) pending = null
    }

    fun onFrameSubmitted(surface: Surface, delayMillis: Long) {
        val callback = synchronized(lock) {
            pending?.takeIf { it.surface === surface }?.also { pending = null }?.callback
        }
        // No ownership locks across UI callbacks. A callback already claimed here can finish after
        // cancellation; the host must also invalidate any UI work it posts using its output generation.
        callback?.invoke(delayMillis.coerceAtLeast(0L))
    }

    fun close() = synchronized(lock) {
        closed = true
        pending = null
    }
}
