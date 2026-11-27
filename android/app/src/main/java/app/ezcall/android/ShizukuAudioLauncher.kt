package app.ezcall.android

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import rikka.shizuku.Shizuku

@Keep
class ShizukuAudioLauncher(private val context: Context) : IShizukuAudioLauncher.Stub() {
    override fun start(port: Int, token: ByteArray) {
        check(Binder.getCallingUid() == context.applicationInfo.uid)
        check(Process.myUid() == Process.SHELL_UID)
        val command = CallAudio.Setup.launchCommand(context.applicationInfo.sourceDir, port, token)
        val launcher = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        try {
            check(launcher.waitFor(3, TimeUnit.SECONDS) && launcher.exitValue() == 0)
        } finally {
            launcher.destroy()
        }
    }

    override fun destroy() {
        check(Binder.getCallingUid() == Process.myUid())
        exitProcess(0)
    }

    internal companion object {
        fun supported() = !Shizuku.isPreV11() && Shizuku.getVersion() >= 13 &&
            Shizuku.getUid() == Process.SHELL_UID

        fun launch(context: Context, port: Int, token: ByteArray) {
            check(Shizuku.pingBinder() && supported() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
            val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuAudioLauncher::class.java))
                .processNameSuffix("audio_launcher").daemon(false)
                .version(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
            val bound = CompletableFuture<IShizukuAudioLauncher>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    bound.complete(IShizukuAudioLauncher.Stub.asInterface(binder))
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    bound.completeExceptionally(IOException("Shizuku disconnected"))
                }
            }
            try {
                Shizuku.bindUserService(args, connection)
                bound.get(7, TimeUnit.SECONDS).start(port, token)
            } finally {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
                runCatching { Shizuku.unbindUserService(args, connection, false) }
            }
        }
    }
}
