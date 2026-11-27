package app.ezcall.android

import app.ezcall.android.CallAudio.Setup.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuAudioLauncherTest {
    @Test
    fun restoresExplicitMethodsAndOnlyTheKnownLegacyMethod() {
        for (method in Method.entries) {
            assertEquals(method, Method.restore(method.name, legacyShizuku = true))
        }
        assertEquals(Method.SHIZUKU, Method.restore(null, legacyShizuku = true))
        assertNull(Method.restore(null, legacyShizuku = false))
        assertNull(Method.restore("unknown", legacyShizuku = true))
    }

    @Test
    fun validatesArgumentsBeforeBuildingPrivilegedCommand() {
        val apk = "/data/app/~~app=/app.ezcall.android-abc==/base.apk"
        val token = ByteArray(32)
        assertThrows(IllegalArgumentException::class.java) {
            CallAudio.Setup.launchCommand(apk, 1234, token)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CallAudio.Setup.launchCommand(apk, 25000, ByteArray(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CallAudio.Setup.launchCommand("$apk'; id #", 25000, token)
        }
        val command = CallAudio.Setup.launchCommand(apk, 25000, token)
        assertTrue(command.contains("CLASSPATH='$apk'"))
        assertTrue(command.contains("app.ezcall.android.CallAudioServer 25000"))
    }
}
