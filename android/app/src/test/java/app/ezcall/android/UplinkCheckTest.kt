package app.ezcall.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class UplinkCheckTest {
    private val frameMs = 20L

    /** RMS per 20 ms frame of speech bursts and pauses, like the receiver's microphone stream. */
    private fun speech(seed: Int, frames: Int): DoubleArray {
        val random = Random(seed)
        val levels = DoubleArray(frames)
        var index = 0
        var talking = random.nextBoolean()
        while (index < frames) {
            val length = if (talking) random.nextInt(10, 40) else random.nextInt(10, 30)
            repeat(length) {
                if (index < frames) levels[index++] = if (talking) 800.0 + random.nextDouble() * 3_000 else 0.0
            }
            talking = !talking
        }
        return levels
    }

    private fun run(injected: DoubleArray, captured: (Int) -> Double): UplinkCheck.Verdict {
        val check = UplinkCheck()
        val start = 1_000_000L
        for (i in injected.indices) {
            val now = start + i * frameMs
            check.injected(now, injected[i])
            check.captured(now, captured(i))
            if (i % 50 == 49) {
                val verdict = check.evaluate(now)
                if (verdict != UplinkCheck.Verdict.PENDING) return verdict
            }
        }
        return UplinkCheck.Verdict.PENDING
    }

    @Test fun injectedVoiceFoundInTheUplinkIsReached() {
        val voice = speech(1, 1_500)
        val noise = Random(2)
        val verdict = run(voice) { i -> (if (i >= 6) voice[i - 6] * 0.4 else 0.0) + 40 + noise.nextDouble() * 40 }
        assertEquals(UplinkCheck.Verdict.REACHED, verdict)
    }

    @Test fun uplinkCarryingOnlyUnrelatedSoundIsNotReached() {
        val voice = speech(3, 1_500)
        val room = speech(4, 1_500)
        assertEquals(UplinkCheck.Verdict.NOT_REACHED, run(voice) { i -> room[i] + 30 })
    }

    @Test fun roomPickupThatLeadsTheInjectionDoesNotCountAsReached() {
        val voice = speech(5, 1_500)
        // The sender's microphone hears the person before the Bluetooth-delayed copy is injected.
        assertNotEquals(UplinkCheck.Verdict.REACHED, run(voice) { i -> if (i + 10 < voice.size) voice[i + 10] + 30 else 30.0 })
    }

    @Test fun flatCaptureOrSilentReceiverDecidesNothing() {
        val voice = speech(6, 1_500)
        assertEquals(UplinkCheck.Verdict.PENDING, run(voice) { 0.0 })
        assertEquals(UplinkCheck.Verdict.PENDING, run(DoubleArray(1_500)) { i -> voice[i] })
    }

    @Test fun summaryExplainsAnUndecidedCheck() {
        val check = UplinkCheck()
        assertTrue(check.summary().startsWith("no evaluation yet"))
        val voice = speech(7, 400)
        for (i in voice.indices) {
            check.injected(1_000_000L + i * frameMs, voice[i])
            check.captured(1_000_000L + i * frameMs, 0.0)
        }
        check.evaluate(1_000_000L + voice.size * frameMs)
        val summary = check.summary()
        assertTrue(summary, summary.contains("capture=") && summary.contains("spread=0.0dB"))
        assertTrue(summary, !summary.contains("match="))
    }

    @Test fun rmsReadsLittleEndianSamples() {
        val pcm = byteArrayOf(0x00, 0x10, 0x00, 0xF0.toByte()) // 4096, -4096
        assertEquals(4_096.0, UplinkCheck.rms(pcm), 0.001)
    }

    @Test fun diagnosticsDropOldestLogLinesToFit() {
        val lines = List(500) { "line $it with some component log text" }
        val text = String(CallAudioServer.fitText("header\n", lines, limit = 2_000), Charsets.UTF_8)
        assertTrue(text.toByteArray().size <= 2_000)
        assertTrue(text.startsWith("header\n"))
        assertTrue(text.endsWith("line 499 with some component log text"))
    }
}
