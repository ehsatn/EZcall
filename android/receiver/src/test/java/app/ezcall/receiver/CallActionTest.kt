package app.ezcall.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallActionTest {
    @Test fun activeUpdatesConfirmAnswerButDoNotConfirmHangupForTheSameCall() {
        val answer = CallAction("test", "call_answer", 0)
        val end = CallAction("test", "call_end", 10_000)

        assertTrue(answer.accepts("test", "active"))
        assertFalse(end.accepts("test", "active"))
        assertFalse(end.accepts("other", "ended"))
        assertTrue(end.accepts("test", "ended"))
    }
}
