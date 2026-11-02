package com.shilapi.xcertplay.aa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import java.nio.ByteBuffer
import java.util.zip.Inflater

/**
 * Renders the raw sender for devices whose H.264 decoder refuses the stream, and for the emulator
 * pair where a software codec is the only thing available. Frames arrive as
 * `width, height, deflated ARGB_8888`, which the sender produces on the CPU.
 */
internal class AaRawFrameView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val destination = Rect()
    private var inflater = Inflater()
    private var reportedFailure = false

    val streamWidth: Int get() = bitmap?.width ?: 0
    val streamHeight: Int get() = bitmap?.height ?: 0

    fun setFrame(payload: ByteArray) {
        val width = com.shilapi.xcertplay.mirroring.aa.AaWire.getInt(payload, 0)
        val height = com.shilapi.xcertplay.mirroring.aa.AaWire.getInt(payload, 4)
        if (width <= 0 || height <= 0) return
        val pixels = IntArray(width * height)
        val bytes = ByteArray(width * height * 4)
        try {
            if (inflater.finished()) inflater.reset()
            inflater.setInput(payload, 8, payload.size - 8)
            var written = 0
            while (written < bytes.size) {
                val read = inflater.inflate(bytes, written, bytes.size - written)
                if (read <= 0) break
                written += read
            }
            if (written < bytes.size) {
                // Dropping this frame is better than showing a half-decoded one.
                return
            }
            ByteBuffer.wrap(bytes).asIntBuffer().get(pixels)
        } catch (error: Throwable) {
            if (!reportedFailure) {
                reportedFailure = true
                android.util.Log.w("DiPlayAa", "raw frame rejected", error)
            }
            return
        }
        val next = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val previous = bitmap
        bitmap = next
        previous?.recycle()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val frame = bitmap ?: return
        val scale = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val drawWidth = (frame.width * scale).toInt()
        val drawHeight = (frame.height * scale).toInt()
        val left = (width - drawWidth) / 2
        val top = (height - drawHeight) / 2
        destination.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(frame, null, destination, paint)
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
        inflater.end()
    }
}
