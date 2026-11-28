package app.ezcall.android

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decides whether the receiver's voice injected into the call actually reaches the modem uplink, by matching the
 * loudness envelope of the injected stream against the envelope of the HAL's uplink capture. Speech gives the envelope
 * a shape to match; silence decides nothing. A match must follow the injection in time, so the sender's own
 * microphone hearing the same person in the room (which leads the Bluetooth-delayed injection) doesn't count.
 */
internal class UplinkCheck {
    enum class Verdict { PENDING, REACHED, NOT_REACHED }

    private val injected = FloatArray(BUCKETS) { Float.NaN }
    private val captured = FloatArray(BUCKETS) { Float.NaN }
    private var newest = Long.MIN_VALUE
    private var failures = 0
    private var reached = false
    private var lastStats: String? = null
    private var maxSpeech = 0

    fun injected(atMs: Long, rms: Double) = put(injected, atMs, rms)

    fun captured(atMs: Long, rms: Double) = put(captured, atMs, rms)

    @Synchronized
    private fun put(series: FloatArray, atMs: Long, rms: Double) {
        val bucket = atMs / BUCKET_MS
        if (newest == Long.MIN_VALUE || bucket > newest) {
            val from = if (newest == Long.MIN_VALUE) bucket - BUCKETS + 1 else max(newest + 1, bucket - BUCKETS + 1)
            for (b in from..bucket) {
                injected[slot(b)] = Float.NaN
                captured[slot(b)] = Float.NaN
            }
            newest = bucket
        } else if (bucket <= newest - BUCKETS) return
        val level = level(rms)
        val index = slot(bucket)
        series[index] = if (series[index].isNaN()) level else max(series[index], level)
    }

    @Synchronized
    fun evaluate(nowMs: Long): Verdict {
        if (reached) return Verdict.REACHED
        if (newest == Long.MIN_VALUE) return Verdict.PENDING
        // Leave room after the window for the injected audio to show up in the capture.
        val end = nowMs / BUCKET_MS - MAX_LAG
        val start = end - WINDOW + 1
        var speech = 0
        for (b in start..end) if (at(injected, b) >= SPEECH_LEVEL) speech++
        // A capture that stays flat (absent, digital silence or constant noise) can't confirm or rule out anything.
        var count = 0
        var sum = 0.0
        var squares = 0.0
        var loudest = 0f
        for (b in start..end + MAX_LAG) {
            val value = at(captured, b)
            if (value.isNaN()) continue
            count++
            sum += value
            squares += value * value
            loudest = max(loudest, value)
        }
        val mean = if (count == 0) 0.0 else sum / count
        val spread = if (count == 0) 0.0 else sqrt(max(0.0, squares / count - mean * mean))
        maxSpeech = max(maxSpeech, speech)
        val base = "speech=${speech * BUCKET_MS}ms capture=${count * 100 / (WINDOW + MAX_LAG)}% " +
            "level=${mean.toInt()}dB peak=${loudest.toInt()}dB spread=${fmt(spread, 1)}dB"
        if (speech < MIN_SPEECH || count < WINDOW / 2 || spread < MIN_CAPTURE_SPREAD) {
            lastStats = base
            return Verdict.PENDING
        }

        var following = -1.0
        var leading = -1.0
        for (lag in -MAX_LAG..MAX_LAG) {
            val score = correlation(start, end, lag) ?: continue
            if (lag >= 0) following = max(following, score) else leading = max(leading, score)
        }
        lastStats = "$base match=${fmt(following, 2)} lead=${fmt(leading, 2)} misses=$failures"
        if (following >= MATCH && following >= leading) {
            reached = true
            return Verdict.REACHED
        }
        val acousticOnly = leading > following && following < MATCH
        if ((following < MISMATCH || acousticOnly) && speech >= FAIL_SPEECH && ++failures >= FAIL_WINDOWS) {
            return Verdict.NOT_REACHED
        }
        return Verdict.PENDING
    }

    /** What the latest evaluation saw, for the component log. */
    @Synchronized
    fun summary() = "${lastStats ?: "no evaluation yet"}; most speech in a window=${maxSpeech * BUCKET_MS}ms"

    private fun correlation(start: Long, end: Long, lag: Int): Double? {
        var n = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (b in start..end) {
            val x = at(injected, b)
            val y = at(captured, b + lag)
            if (x.isNaN() || y.isNaN()) continue
            n++
            sx += x
            sy += y
            sxx += x * x
            syy += y * y
            sxy += x * y
        }
        if (n < MIN_SPEECH) return null
        val vx = n * sxx - sx * sx
        val vy = n * syy - sy * sy
        if (vx <= 0 || vy <= 0) return null
        return (n * sxy - sx * sy) / sqrt(vx * vy)
    }

    private fun at(series: FloatArray, bucket: Long): Float =
        if (newest == Long.MIN_VALUE || bucket > newest || bucket <= newest - BUCKETS) Float.NaN else series[slot(bucket)]

    private fun fmt(value: Double, digits: Int) = String.format(java.util.Locale.ROOT, "%.${digits}f", value)

    private fun slot(bucket: Long) = Math.floorMod(bucket, BUCKETS.toLong()).toInt()

    companion object {
        const val BUCKET_MS = 40L
        private const val BUCKETS = 256
        private const val WINDOW = 125 // 5 s
        private const val MAX_LAG = 10 // 400 ms of track and capture buffering
        private const val MIN_SPEECH = 25 // 1 s of speech in the window
        private const val FAIL_SPEECH = 40
        private const val FAIL_WINDOWS = 4
        private const val SPEECH_LEVEL = 45f // about -50 dBFS
        private const val MIN_CAPTURE_SPREAD = 3.0
        private const val MATCH = 0.5
        private const val MISMATCH = 0.2

        fun level(rms: Double) = (20 * log10(max(rms, 1.0))).toFloat()

        /** RMS of little-endian 16-bit PCM. */
        fun rms(pcm: ByteArray, length: Int = pcm.size): Double {
            val samples = length / 2
            if (samples == 0) return 0.0
            var sum = 0.0
            for (i in 0 until samples) {
                val value = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort().toDouble()
                sum += value * value
            }
            return sqrt(sum / samples)
        }
    }
}
