package app.ezcall.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessPairingTest {
    @Test fun parsesOnlyPairingCodesAndOptionalPorts() {
        assertEquals("012345" to null, WirelessPairing.parse(" ۰۱۲۳۴۵ "))
        assertEquals("012345" to 37811, WirelessPairing.parse("012345:37811"))
        assertNull(WirelessPairing.parse("12345"))
        assertNull(WirelessPairing.parse("123456:65536"))
        assertNull(WirelessPairing.parse("123456:192.168.1.2:37811"))
    }
}
