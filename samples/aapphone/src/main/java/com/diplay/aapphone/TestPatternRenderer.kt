package com.diplay.aapphone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws what the "phone" is projecting.
 *
 * A real phone encodes its Android Auto UI here. This one draws a stand-in that makes a head unit
 * session easy to judge from across a room: the clock proves frames are arriving, the cards prove
 * layout and scaling are right, and the touch ring proves the input path works — a touch on the car
 * screen moves the ring in the projected image, so a reviewer can see both halves of the loop in one
 * glance without a second device.
 */
class TestPatternRenderer(val width: Int, val height: Int) {

    /** Set by the link once the session is up, so the image says what it is. */
    @Volatile
    var subtitle: String = "等待连接"

    /** Last touch from the car, in video coordinates; -1 when the finger is up. */
    @Volatile
    var touchX: Int = -1

    @Volatile
    var touchY: Int = -1

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas) {
        val scale = height / 480f
        val background = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.parseColor("#0B1B2B"), Color.parseColor("#123A4D"), Color.parseColor("#0B1B2B")),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

        drawClock(canvas, scale)
        drawMapCard(canvas, scale)
        drawMediaCard(canvas, scale)
        drawTouch(canvas, scale)
    }

    private fun drawClock(canvas: Canvas, scale: Float) {
        paint.shader = null
        paint.color = Color.WHITE
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 64f * scale
        val text = clock.format(Date())
        canvas.drawText(text, 40f * scale, 96f * scale, paint)

        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = 18f * scale
        paint.color = Color.parseColor("#8FD0E8")
        canvas.drawText("DiPlay · Android Auto 投影", 42f * scale, 128f * scale, paint)

        paint.textSize = 15f * scale
        paint.color = Color.parseColor("#B9D6E3")
        canvas.drawText(subtitle, 42f * scale, 152f * scale, paint)
    }

    private fun drawMapCard(canvas: Canvas, scale: Float) {
        val left = 40f * scale
        val top = 180f * scale
        val right = width * 0.62f
        val bottom = height - 40f * scale
        paint.shader = null
        paint.color = Color.parseColor("#1B3B4E")
        canvas.drawRoundRect(RectF(left, top, right, bottom), 18f * scale, 18f * scale, paint)

        // A road grid, then a route: enough shape to tell scaling and tearing at a glance.
        paint.color = Color.parseColor("#2C5A70")
        paint.strokeWidth = 2f * scale
        var x = left + 20f * scale
        while (x < right) {
            canvas.drawLine(x, top + 10f * scale, x, bottom - 10f * scale, paint)
            x += 42f * scale
        }
        var y = top + 20f * scale
        while (y < bottom) {
            canvas.drawLine(left + 10f * scale, y, right - 10f * scale, y, paint)
            y += 42f * scale
        }

        paint.color = Color.parseColor("#39D98A")
        paint.strokeWidth = 8f * scale
        val route = android.graphics.Path().apply {
            moveTo(left + 40f * scale, bottom - 40f * scale)
            lineTo(left + 90f * scale, bottom - 120f * scale)
            lineTo(left + 170f * scale, bottom - 130f * scale)
            lineTo(left + 210f * scale, top + 60f * scale)
        }
        canvas.drawPath(route, paint)

        paint.color = Color.WHITE
        paint.textSize = 20f * scale
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        canvas.drawText("导航", left + 18f * scale, top + 34f * scale, paint)
    }

    private fun drawMediaCard(canvas: Canvas, scale: Float) {
        val left = width * 0.65f
        val right = width - 40f * scale
        val top = 180f * scale
        val bottom = height - 40f * scale
        paint.color = Color.parseColor("#1B3B4E")
        canvas.drawRoundRect(RectF(left, top, right, bottom), 18f * scale, 18f * scale, paint)

        paint.color = Color.parseColor("#7FD1FF")
        paint.textSize = 20f * scale
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        canvas.drawText("音乐", left + 18f * scale, top + 34f * scale, paint)

        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = 16f * scale
        paint.color = Color.WHITE
        canvas.drawText("正在播放 · 测试音轨", left + 18f * scale, top + 74f * scale, paint)

        val barLeft = left + 18f * scale
        val barRight = right - 18f * scale
        val barTop = top + 100f * scale
        paint.color = Color.parseColor("#33566A")
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barTop + 8f * scale), 4f * scale, 4f * scale, paint)
        paint.color = Color.parseColor("#39D98A")
        canvas.drawRoundRect(
            RectF(barLeft, barTop, barLeft + (barRight - barLeft) * 0.42f, barTop + 8f * scale),
            4f * scale,
            4f * scale,
            paint,
        )

        paint.color = Color.parseColor("#9FBDCB")
        paint.textSize = 13f * scale
        canvas.drawText("触控回传：车机点按 → 手机响应", left + 18f * scale, bottom - 24f * scale, paint)
    }

    private fun drawTouch(canvas: Canvas, scale: Float) {
        val x = touchX
        val y = touchY
        if (x < 0 || y < 0) return
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(x.toFloat(), y.toFloat(), 34f * scale, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * scale
        canvas.drawCircle(x.toFloat(), y.toFloat(), 22f * scale, paint)
        paint.style = Paint.Style.FILL
    }

    /** Renders into [bitmap], which must be [width] by [height]. */
    fun render(bitmap: Bitmap) {
        draw(Canvas(bitmap))
    }
}
