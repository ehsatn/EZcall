package app.ezcall.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbIdentityTest {
    private val nonce = "00112233445566778899aabbccddeeff"

    @Test fun buildsOnlySafeCallbackCommands() {
        assertEquals("content call --user 0 --uri content://app.ezcall.android.adbcheck --method verify --arg $nonce",
            AdbIdentity.command("app.ezcall.android.adbcheck", 0, nonce))
        assertThrows(IllegalArgumentException::class.java) { AdbIdentity.command("a; rm -rf /", 0, nonce) }
        assertThrows(IllegalArgumentException::class.java) { AdbIdentity.command("app.adbcheck", -1, nonce) }
        assertThrows(IllegalArgumentException::class.java) { AdbIdentity.command("app.adbcheck", 0, "$nonce;id") }
    }

    @Test fun newNoncesAreAcceptedAndDistinct() {
        val first = AdbIdentity.newNonce()
        AdbIdentity.command("app.adbcheck", 0, first)
        assertFalse(first == AdbIdentity.newNonce())
    }

    @Test fun acceptsCallbacksOnlyFromShellOrRoot() {
        assertFalse(AdbIdentity.record("verify", nonce, 10_123))
        assertFalse(AdbIdentity.record("verify", nonce, 1_000))
        assertFalse(AdbIdentity.consume(nonce))

        assertTrue(AdbIdentity.record("verify", nonce, 2_000))
        assertTrue(AdbIdentity.consume(nonce))
        assertFalse(AdbIdentity.consume(nonce))

        assertTrue(AdbIdentity.record("verify", nonce, 0))
        assertTrue(AdbIdentity.consume(nonce))
    }

    @Test fun ignoresMalformedCallbacks() {
        assertFalse(AdbIdentity.record("other", nonce, 2_000))
        assertFalse(AdbIdentity.record("verify", null, 2_000))
        assertFalse(AdbIdentity.record("verify", "short", 2_000))
        assertFalse(AdbIdentity.consume("short"))
    }
}
