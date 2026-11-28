package app.ezcall.android

import app.ezcall.android.CallAudio.Setup.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuAudioLauncherTest {
    @Test
    fun acceptsShizukuRootAndAdbIdentities() {
        assertTrue(ShizukuAudioLauncher.supportsUid(0))
        assertTrue(ShizukuAudioLauncher.supportsUid(2000))
        assertFalse(ShizukuAudioLauncher.supportsUid(1000))
    }

    @Test
    fun confinesRootLauncherToInstalledSenderAndAvoidsSharedLog() {
        val apk = "/data/app/~~app=/app.ezcall.android-abc==/base.apk"
        val token = ByteArray(32)
        assertThrows(IllegalArgumentException::class.java) {
            CallAudio.Setup.rootLaunchCommand("/data/local/tmp/base.apk", 25000, token)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CallAudio.Setup.rootLaunchCommand(apk.replace("ezcall", "another"), 25000, token)
        }
        val command = CallAudio.Setup.rootLaunchCommand(apk, 25000, token)
        assertTrue(command.contains("id -u"))
        assertTrue(command.contains("|| exit 1; echo ezcall-root-granted;"))
        assertTrue(command.indexOf("ezcall-root-granted") < command.indexOf("CLASSPATH"))
        assertTrue(command.contains("CLASSPATH='$apk'"))
        assertFalse(command.contains("/data/local/tmp"))
        assertFalse(command.contains("pkill"))
    }

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
