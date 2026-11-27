package app.ezcall.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.appcompat.content.res.AppCompatResources

internal class AudioGuideArtwork(context: Context, private val accent: Int, private val outline: Int, ink: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headset = AppCompatResources.getDrawable(context, R.drawable.ic_headset)?.mutate()?.apply {
        setTint(ink)
        setBounds(139, 54, 205, 120)
    }
    private val levels = intArrayOf(9, 19, 30, 22, 12, 25, 13)
    private val opacities = intArrayOf(92, 158, 219, 255, 217, 158, 97)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 344f, height / 174f)
        canvas.save()
        canvas.translate((width - 344 * scale) / 2, (height - 174 * scale) / 2)
        canvas.scale(scale, scale)
        paint.color = outline
        paint.strokeWidth = 1f
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(172f, 87f, 51.5f, paint)
        paint.alpha = 150
        canvas.drawCircle(172f, 87f, 61.5f, paint)
        headset?.draw(canvas)
        paint.color = accent
        paint.style = Paint.Style.FILL
        for (index in levels.indices) {
            paint.alpha = opacities[index]
            drawBar(canvas, 36f + index * 9, levels[index])
            drawBar(canvas, 304f - index * 9, levels[index])
        }
        canvas.restore()
    }

    private fun drawBar(canvas: Canvas, x: Float, level: Int) {
        val half = level / 2f
        canvas.drawRoundRect(x, 87f - half, x + 4, 87f + half, 2f, 2f, paint)
    }
}
