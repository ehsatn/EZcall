package app.ezcall.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

internal class PairingArtwork(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val link = Path().apply {
        moveTo(142f, 81f)
        lineTo(158f, 97f)
        lineTo(150f, 104f)
        lineTo(150f, 74f)
        lineTo(158f, 81f)
        lineTo(142f, 97f)
    }
    private val check = Path().apply {
        moveTo(218f, 88f)
        lineTo(224f, 94f)
        lineTo(235f, 82f)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 316f, height / 178f)
        canvas.save()
        canvas.translate((width - 300f * scale) / 2f, (height - 178f * scale) / 2f)
        canvas.scale(scale, scale)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(40, 235, 240, 255)
        for (ring in 0..4) {
            canvas.drawCircle(150f, 89f, 45f + ring * 26f, paint)
        }
        paint.strokeWidth = 1.5f
        paint.color = Color.argb(150, 235, 240, 255)
        canvas.drawLine(93f, 89f, 208f, 89f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(114f, 89f, 2.8f, paint)
        canvas.drawCircle(186f, 89f, 2.8f, paint)

        canvas.save()
        canvas.rotate(-11f, 73f, 89f)
        phone(canvas, 43f, 30f)
        box(canvas, 56f, 71f, 90f, 105f, 8f, 0xFFE4EDFF.toInt())
        finder(canvas, 63f, 78f)
        finder(canvas, 75f, 78f)
        finder(canvas, 63f, 90f)
        box(canvas, 75f, 90f, 79f, 94f, 1f, 0xFF284DB8.toInt())
        box(canvas, 79f, 94f, 83f, 98f, 1f, 0xFF284DB8.toInt())
        canvas.restore()

        canvas.save()
        canvas.rotate(11f, 226f, 89f)
        phone(canvas, 196f, 30f)
        paint.color = 0xFFDAE7FF.toInt()
        canvas.drawCircle(226f, 88f, 16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = 0xFF2045B2.toInt()
        canvas.drawPath(check, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()

        paint.color = 0xFFF1F5FF.toInt()
        canvas.drawCircle(150f, 89f, 25.5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.6f
        paint.color = 0xFF2346C5.toInt()
        canvas.drawPath(link, paint)
        canvas.restore()
    }

    private fun phone(canvas: Canvas, x: Float, y: Float) {
        box(canvas, x + 2, y + 4, x + 62, y + 122, 15f, 0x44101E5A)
        box(canvas, x, y, x + 60, y + 118, 14f, 0xFFE2EBFF.toInt())
        box(canvas, x + 3, y + 3, x + 57, y + 115, 11f, 0xFFB4CAFF.toInt())
        box(canvas, x + 6, y + 6, x + 54, y + 112, 9f, 0xFFF8FAFF.toInt())
        box(canvas, x + 24, y + 10, x + 36, y + 13, 2f, 0xFF9DB6F5.toInt())
        box(canvas, x + 20, y + 105, x + 40, y + 107, 1f, 0xFFC2D3F8.toInt())
    }

    private fun finder(canvas: Canvas, x: Float, y: Float) {
        box(canvas, x, y, x + 8, y + 8, 1.4f, 0xFF284DB8.toInt())
        box(canvas, x + 2, y + 2, x + 6, y + 6, 0.7f, 0xFFE4EDFF.toInt())
    }

    private fun box(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, corner: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint)
    }
}
