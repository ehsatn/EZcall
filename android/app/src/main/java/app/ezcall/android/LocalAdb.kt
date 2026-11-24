package app.ezcall.android

import android.content.Context
import android.os.Build
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.security.auth.x500.X500Principal

// This phone's own Wireless debugging daemon, reached with the key that WirelessPairing registered.
internal object LocalAdb {
    private const val KEY = "xcall-local-adb-pairing-v2"

    fun hasKey(): Boolean = runCatching { store().containsAlias(KEY) }.getOrDefault(false)

    fun key(): KeyStore.PrivateKeyEntry {
        val store = store()
        if (!store.containsAlias(KEY)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setCertificateSubject(X500Principal("CN=ezcall"))
                    // Conscrypt delegates TLS 1.3 signing for Android Keystore keys as raw RSA.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .build())
            }.generateKeyPair()
        }
        return store.getEntry(KEY, null) as KeyStore.PrivateKeyEntry
    }

    /**
     * Runs one shell command and returns its output. Wireless debugging must be on. The command is sent only after
     * [AdbIdentity] proves the connection reached this phone's adbd; otherwise this throws [AdbIdentity.Unverified].
     */
    fun shell(context: Context, command: String, connectPort: Int? = null): String {
        require(connectPort == null || connectPort in 1024..65535)
        val port = connectPort ?: findPort(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, 5_000)
            ?: throw PortNotFound()
        Connection(key()).use { adb ->
            adb.setApi(Build.VERSION.SDK_INT)
            adb.setTimeout(8, TimeUnit.SECONDS)
            if (!adb.connect("127.0.0.1", port)) throw IOException("Wireless debugging refused ezcall")
            val nonce = AdbIdentity.newNonce()
            // Android user ids are uid / 100000 (UserHandle.PER_USER_RANGE).
            exec(adb, AdbIdentity.command(context.packageName + AdbIdentity.AUTHORITY_SUFFIX, Process.myUid() / 100_000, nonce))
            if (!AdbIdentity.consume(nonce)) throw AdbIdentity.Unverified()
            return exec(adb, command)
        }
    }

    class PortNotFound : IOException("Wireless debugging port not found")

    fun parseConnectPort(input: CharSequence): Int? {
        val digits = input.trim()
        if (digits.length !in 4..5 || !digits.all(Char::isDigit)) return null
        return digits.toString().toIntOrNull()?.takeIf { it in 1024..65535 }
    }

    private fun exec(adb: AbsAdbConnectionManager, command: String): String {
        adb.openStream("shell:$command").use { stream ->
            val input = stream.openInputStream()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1_024)
            try {
                while (output.size() < 16_384) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } catch (error: IOException) {
                if (!stream.isClosed) throw error
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    fun findPort(context: Context, type: String, timeoutMs: Long): Int? {
        val ready = CountDownLatch(1)
        var port = 0
        val discovery = AdbMdns(context, type) { _, found ->
            if (found in 1..65535) {
                port = found
                ready.countDown()
            }
        }
        discovery.start()
        try {
            return if (ready.await(timeoutMs, TimeUnit.MILLISECONDS)) port else null
        } finally {
            discovery.stop()
        }
    }

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private class Connection(private val entry: KeyStore.PrivateKeyEntry) : AbsAdbConnectionManager() {
        override fun getPrivateKey(): PrivateKey = entry.privateKey
        override fun getCertificate(): Certificate = entry.certificate
        override fun getDeviceName() = "ezcall"
    }
}
