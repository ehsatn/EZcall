package app.ezcall.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayRecoveryTest {
    @Test fun expiredDialCannotHangUpAnotherCall() {
        val dial = RelayService.Dial("dial", "1234567", 5_000)
        val incoming = RelayService.Call("incoming", "7654321", 1_000, "ringing")
        assertTrue(dial.canCancel("dial", null, 4_999))
        assertFalse(dial.canCancel("dial", null, 5_000))
        assertFalse(dial.canCancel("dial", incoming, 4_999))
        assertFalse(dial.canCancel("other", null, 4_999))
    }

    @Test fun revokingCallerSharingHidesIncomingNumbersAndKeepsReceiverDialedNumbers() {
        val incoming = RelayService.Call("call", "1234567", 1_000, "active")
        assertEquals("", incoming.withCallerSharing(false).address)
        assertEquals(incoming, incoming.withCallerSharing(true))
        assertEquals(incoming.address, incoming.copy(fromReceiver = true).withCallerSharing(false).address)
    }

    @Test fun onlyTheSameActiveCallCanResumeWithinTheOriginalWindow() {
        val recovery = CallRecovery("active-call", disconnectedAt = 1_000L)
        assertTrue(recovery.accepts("active-call", now = 20_999L))
        assertFalse(recovery.accepts("different-call", now = 1_001L))
        assertFalse(recovery.accepts("active-call", now = 21_000L))
    }

    @Test fun smsOnlyAcceptsRegularPhoneNumbers() {
        assertEquals("+989121234567", RelayService.smsAddress("+98 (912) ۱۲۳-۴۵۶۷"))
        assertNull(RelayService.smsAddress("*123#"))
        assertNull(RelayService.smsAddress("12345"))
        assertNull(RelayService.smsAddress("+989121234567;1"))
    }

    @Test fun retriesBackOffAndKeepTryingAtTheLimit() {
        val delays = generateSequence(1_000L, RelayService::nextRetryDelay).take(8).toList()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
    }
}
