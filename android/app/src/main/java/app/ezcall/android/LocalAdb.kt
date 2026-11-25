package app.ezcall.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.security.auth.x500.X500Principal

// This phone's own Wireless debugging daemon, reached with the key that WirelessPairing registered.
internal object LocalAdb {
    private const val KEY = "xcall-local-adb-pairing-v2"
    private const val LAST_PORT = "verified_connect_port"
    private const val TAG = "EzcallAudioSetup"
    const val TLS_PAIRING = "_adb-tls-pairing._tcp."
    private const val TLS_CONNECT = "_adb-tls-connect._tcp."

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
        val preferences = context.getSharedPreferences("wireless_debugging", Context.MODE_PRIVATE)
        val remembered = preferences.getInt(LAST_PORT, 0).takeIf { it in 1024..65535 }
        if (connectPort == null && remembered != null) {
            val previous = runCatching { verifiedConnection(context, remembered) }.getOrNull()
            if (previous != null) return previous.use {
                Log.i(TAG, "Verified Wireless debugging using the saved port")
                exec(it, command)
            }
            preferences.edit().remove(LAST_PORT).apply()
        }
        val port = connectPort ?: findPort(context, TLS_CONNECT, 5_000) ?: throw PortNotFound()
        return verifiedConnection(context, port).use {
            preferences.edit().putInt(LAST_PORT, port).apply()
            Log.i(TAG, "Verified Wireless debugging using ${if (connectPort == null) "service discovery" else "a manual port"}")
            exec(it, command)
        }
    }

    private fun verifiedConnection(context: Context, port: Int): Connection {
        val adb = Connection(key())
        try {
            adb.setApi(Build.VERSION.SDK_INT)
            adb.setTimeout(8, TimeUnit.SECONDS)
            if (!adb.connect("127.0.0.1", port)) throw IOException("Wireless debugging refused ezcall")
            val nonce = AdbIdentity.newNonce()
            // Android user ids are uid / 100000 (UserHandle.PER_USER_RANGE).
            exec(adb, AdbIdentity.command(context.packageName + AdbIdentity.AUTHORITY_SUFFIX, Process.myUid() / 100_000, nonce))
            if (!AdbIdentity.consume(nonce)) throw AdbIdentity.Unverified()
            return adb
        } catch (error: Exception) {
            runCatching { adb.close() }
            throw error
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

    @Suppress("DEPRECATION") // The legacy NSD callbacks are also available on Android 12.
    fun findPort(context: Context, type: String, timeoutMs: Long): Int? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val localAddresses = connectivity.allNetworks.flatMap { network ->
            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
        }.toSet()
        val nsd = context.getSystemService(NsdManager::class.java)
        val result = CompletableFuture<Int?>()
        val discovery = object : NsdManager.DiscoveryListener {
            private val waiting = ArrayDeque<NsdServiceInfo>()
            private var resolving = false

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.i(TAG, "Wireless debugging discovery failed: code=$errorCode")
                result.complete(null)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                waiting.removeAll { it.serviceName == serviceInfo.serviceName }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (result.isDone) return
                if (waiting.any { it.serviceName == serviceInfo.serviceName }) return
                waiting.add(serviceInfo)
                resolveNext()
            }

            private fun resolveNext() {
                if (resolving || result.isDone) return
                val service = waiting.removeFirstOrNull() ?: return
                resolving = true
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving = false
                        val addresses = if (Build.VERSION.SDK_INT >= 34) serviceInfo.hostAddresses else listOfNotNull(serviceInfo.host)
                        if (addresses.any { it.isLoopbackAddress || it in localAddresses } && serviceInfo.port in 1024..65535) {
                            result.complete(serviceInfo.port)
                        } else resolveNext()
                    }
                }
                try {
                    nsd.resolveService(service, listener)
                } catch (error: RuntimeException) {
                    Log.i(TAG, "Wireless debugging service resolution failed: ${error.javaClass.simpleName}")
                    result.complete(null)
                }
            }
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
        try {
            return runCatching { result.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
        } finally {
            result.cancel(false)
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private class Connection(private val entry: KeyStore.PrivateKeyEntry) : AbsAdbConnectionManager() {
        override fun getPrivateKey(): PrivateKey = entry.privateKey
        override fun getCertificate(): Certificate = entry.certificate
        override fun getDeviceName() = "ezcall"
    }
}
