package app.ezcall.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

internal object SimulationTone {
    const val PERIOD_FRAMES = 50
    const val INTRO_FRAMES = 250

    fun frame(index: Int): ByteArray {
        val cycle = index % PERIOD_FRAMES
        val samples = ByteBuffer.allocate(Opus.FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        if (cycle < 24) {
            val frequency = if ((index / PERIOD_FRAMES) % 2 == 0) 660 else 880
            val duration = 24 * Opus.FRAME_SAMPLES
            for (i in 0 until Opus.FRAME_SAMPLES) {
                val sample = cycle * Opus.FRAME_SAMPLES + i
                val gain = minOf(sample, duration - 1 - sample, 240) / 240.0
                samples.putShort((5_000 * gain * sin(2 * PI * frequency * sample / Opus.SAMPLE_RATE)).toInt().toShort())
            }
        }
        return samples.array()
    }
}
