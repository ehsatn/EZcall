package app.ezcall.receiver

import app.ezcall.android.AudioTestLog
import app.ezcall.android.ReceiverTestStep
import app.ezcall.android.TEST_ECHO_SEQUENCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class ReceiverAudioTestChecksTest {
    @Test
    fun testCueCanBufferTheStartupBurstWhileRoutingIsPending() {
        val routing = CompletableFuture<Unit>()
        val test = ReceiverAudio(routing, {}, {}, echoSequence = TEST_ECHO_SEQUENCE)
        val live = ReceiverAudio(routing, {}, {})
        val frame = byteArrayOf(1)
        try {
            repeat(16) { assertFalse(test.offer(frame, it.toLong())) }
            assertTrue(test.offer(frame, 16))
            repeat(5) { assertFalse(live.offer(frame, null)) }
            assertTrue(live.offer(frame, null))
        } finally {
            test.close()
            live.close()
            routing.complete(Unit)
        }
    }

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
