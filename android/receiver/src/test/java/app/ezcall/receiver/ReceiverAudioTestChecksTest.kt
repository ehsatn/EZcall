package app.ezcall.receiver

import app.ezcall.android.AudioTestLog
import app.ezcall.android.ReceiverTestStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReceiverAudioTestChecksTest {
    @Test
    fun resultUsesPlaybackAndMicrophoneSignalsFromTheSameTest() {
        val current = AudioTestLog("Receiver") { 0 }
        val previous = AudioTestLog("Receiver") { 0 }
        previous.record(ReceiverTestStep.ECHO_PLAYED)
        current.record(ReceiverTestStep.BEEPS_HEARD)
        current.record(ReceiverTestStep.ECHO_HEARD)
        assertEquals(listOf(false, false, false), current.audioChecks())

        current.record(ReceiverTestStep.CUE_PLAYED)
        current.record(ReceiverTestStep.MIC_SIGNAL)
        assertFalse(current.audioChecks().all { it })

        current.record(ReceiverTestStep.ECHO_PLAYED)
        assertEquals(listOf(true, true, true), current.audioChecks())
    }
}
