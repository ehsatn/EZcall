package app.ezcall.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioAuthorizationTest {
    @Test fun shellCanClaimAnActiveRequestOnlyOnce() {
        var now = 100L
        val usb = UsbAudioAuthorization { now }
        assertFalse(usb.claim(2000))
        val request = usb.begin(120)
        assertFalse(usb.claim(10_123))
        assertTrue(usb.claim(2000))
        assertFalse(usb.claim(2000))
        now = 107
        assertFalse(usb.isPending(request, 7))
    }

    @Test fun expiryAndCancellationDoNotAuthorizeLaterShellRequests() {
        var now = 100L
        val usb = UsbAudioAuthorization { now }
        val expired = usb.begin(120)
        now = 220
        assertFalse(usb.claim(2000))
        val next = usb.begin(120)
        usb.cancel(expired)
        assertTrue(usb.isPending(next, 7))
        usb.cancel(next)
        assertFalse(usb.claim(2000))
    }
}
