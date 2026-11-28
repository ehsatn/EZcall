package app.ezcall.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

internal const val TEST_ECHO_SEQUENCE = 4_096L

internal data class PcmLevel(val peak: Int, val rms: Int) {
    companion object {
        fun read(pcm: ByteArray): PcmLevel {
            var peak = 0
            var energy = 0L
            for (offset in 0 until pcm.lastIndex step 2) {
                val sample = (pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)
                peak = maxOf(peak, abs(sample))
                energy += sample.toLong() * sample
            }
            return PcmLevel(peak, sqrt(energy.toDouble() / (pcm.size / 2).coerceAtLeast(1)).toInt())
        }
    }
}

internal class TestSignal {
    private var frames = 0
    private var observed = false

    /** Emits once, after enough non-silent PCM to reject a single click or digital silence. */
    fun observe(level: PcmLevel): Boolean {
        if (observed) return false
        frames = if (level.rms >= 160) frames + 1 else 0
        observed = frames >= 2
        return observed
    }
}

internal class AudioWaveform {
    private val samples = IntArray(76)
    private var index = 0

    @Synchronized fun add(level: PcmLevel) {
        samples[index] = level.peak
        index = (index + 1) % samples.size
    }

    @Synchronized fun snapshot() = IntArray(samples.size) { samples[(index + it) % samples.size] }
}

internal class AudioWaveformView(context: Context, private val waveform: AudioWaveform, color: Int, private val live: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeCap = Paint.Cap.ROUND }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val samples = waveform.snapshot()
        val bars = samples.size / 2
        val stride = width.toFloat() / bars
        val center = height * 0.5f
        paint.strokeWidth = (stride * 0.58f).coerceAtLeast(1f)
        for (index in 0 until bars) {
            val peak = maxOf(samples[index * 2], samples[index * 2 + 1])
            val x = (index + 0.5f) * stride
            val level = sqrt(peak / 32_768f)
            val amplitude = maxOf(paint.strokeWidth / 2, center * 0.85f * level)
            canvas.drawLine(x, center - amplitude, x, center + amplitude, paint)
        }
        if (live && isShown) postInvalidateDelayed(66)
    }
}
