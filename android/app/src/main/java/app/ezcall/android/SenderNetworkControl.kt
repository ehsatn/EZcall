package app.ezcall.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

@SuppressLint("DiscouragedPrivateApi", "PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
internal object SenderNetworkControl {
    // The previous opt-in covered hotspot and mobile data, without permission to turn off Wi-Fi.
    private const val NETWORK_PERMISSION = "internet_control_enabled"

    fun allowed(context: Context) = preferences(context).getBoolean(NETWORK_PERMISSION, false)

    fun allow(context: Context, enabled: Boolean) {
        preferences(context).edit().remove("enabled").putBoolean(NETWORK_PERMISSION, enabled).apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences("sender_network", Context.MODE_PRIVATE)

    private val telephonyReady by lazy {
        // Unlike an app spawned by Android, the app_process component must initialize this framework explicitly.
        runCatching {
            val initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer")
            if (initializer.getMethod("getTelephonyServiceManager").invoke(null) == null) {
                val type = Class.forName("android.os.TelephonyServiceManager")
                initializer.getMethod("setTelephonyServiceManager", type)
                    .invoke(null, type.getConstructor().newInstance())
            }
        }.isSuccess
    }

    private var cachedTetheringManager: Any? = null

    @Synchronized
    private fun tetheringManager(context: Context): Any? {
        cachedTetheringManager?.let { return it }
        return runCatching {
            val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            val connector = Supplier { service.invoke(null, "tethering") as? IBinder }
            checkNotNull(connector.get())
            // The default manager retains the base system context and sends "android" as the shell caller.
            Class.forName("android.net.TetheringManager")
                .getConstructor(Context::class.java, Supplier::class.java).newInstance(context, connector)
        }.getOrNull()?.also { cachedTetheringManager = it }
    }

    /** This uses the same authenticated local component as call audio. */
    fun request(context: Context, payload: ByteArray, authorized: () -> Boolean): JSONObject? = runCatching {
        val link = CallAudio.Setup.open(context) ?: return null
        link.socket.use {
            if (!link.networkControl || !authorized()) return null
            it.soTimeout = when {
                payload.isEmpty() -> 3_000
                SenderNetwork.decode(payload)?.first == SenderNetwork.INTERNET -> 25_000
                else -> 12_000
            }
            AudioBridge.write(link.output, AudioBridge.NETWORK, payload)
            val (type, response) = AudioBridge.read(link.input)
            check(type == AudioBridge.NETWORK)
            JSONObject(Wire.decode(response))
        }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    fun respond(context: Context, payload: ByteArray): ByteArray {
        val change = if (payload.isEmpty()) null else requireNotNull(SenderNetwork.decode(payload))
        val wifi = runCatching { context.getSystemService(WifiManager::class.java) }.getOrNull()
        val tethering = if (Process.myUid() == 0 || context.hasPermission("android.permission.TETHER_PRIVILEGED")) {
            tetheringManager(context)
        } else null
        val telephony = if (!telephonyReady) null else runCatching {
            val sim = SubscriptionManager.getDefaultDataSubscriptionId()
            if (!SubscriptionManager.isValidSubscriptionId(sim)) null
            else TelephonyManager::class.java.getConstructor(Context::class.java).newInstance(context)
                .createForSubscriptionId(sim)
        }.getOrNull()
        val canChangeData = telephony != null &&
            (Process.myUid() == 0 || context.hasPermission("android.permission.MODIFY_PHONE_STATE"))
        val canDisableWifi = Process.myUid() == 0 || context.hasPermission("android.permission.CHANGE_WIFI_STATE")

        fun wifiEnabled(): Boolean? = when (wifi?.wifiState) {
            WifiManager.WIFI_STATE_DISABLED -> false
            WifiManager.WIFI_STATE_ENABLED -> true
            else -> null
        }

        fun state(setting: SenderNetwork): Boolean? = runCatching {
            when (setting) {
                SenderNetwork.HOTSPOT -> if (tethering != null && wifi != null) {
                    when (WifiManager::class.java.getMethod("getWifiApState").invoke(wifi)) {
                        11, 14 -> false // WIFI_AP_STATE_DISABLED or WIFI_AP_STATE_FAILED
                        13 -> true // WIFI_AP_STATE_ENABLED
                        else -> null
                    }
                } else null
                SenderNetwork.MOBILE_DATA -> if (canChangeData) {
                    telephony?.isDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER)
                } else null
                SenderNetwork.INTERNET -> if (canDisableWifi) {
                    val wifiOn = wifiEnabled() ?: return@runCatching null
                    val dataOn = state(SenderNetwork.MOBILE_DATA) ?: return@runCatching null
                    val hotspotOn = state(SenderNetwork.HOTSPOT) ?: return@runCatching null
                    dataOn && hotspotOn && !wifiOn
                } else null
            }
        }.getOrNull()

        fun set(setting: SenderNetwork, enabled: Boolean): Boolean {
            val before = state(setting) ?: return false
            if (before == enabled) return true
            when (setting) {
                SenderNetwork.HOTSPOT -> setHotspot(checkNotNull(tethering), checkNotNull(wifi), enabled)
                SenderNetwork.MOBILE_DATA -> checkNotNull(telephony)
                    .setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, enabled)
                SenderNetwork.INTERNET -> return set(SenderNetwork.MOBILE_DATA, true) &&
                    (wifiEnabled() == false || disableWifi() && waitForState { wifiEnabled() == false }) &&
                    set(SenderNetwork.HOTSPOT, true) && state(setting) == true
            }
            return waitForState { state(setting) == enabled }
        }

        val reply = JSONObject()
        if (change != null) {
            val (setting, enabled) = change
            val ok = runCatching { set(setting, enabled) }.onFailure { error ->
                val cause = error.cause ?: error
                Log.w("EzcallNetwork", "${setting.field} change failed: ${cause.javaClass.simpleName}: ${cause.message.orEmpty()}")
            }.getOrDefault(false)
            reply.put("ok", ok)
        }
        for (setting in SenderNetwork.entries) reply.put(setting.field, state(setting))
        return reply.toString().toByteArray()
    }

    private fun waitForState(matches: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!matches() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        return matches()
    }

    private fun disableWifi(): Boolean {
        // The Wi-Fi shell command uses Android's caller attribution for the privileged component.
        val process = ProcessBuilder("/system/bin/cmd", "wifi", "set-wifi-enabled", "disabled")
            .redirectOutput(java.io.File("/dev/null")).redirectErrorStream(true).start()
        return try {
            process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun setHotspot(manager: Any, wifi: WifiManager, enabled: Boolean) {
        val type = Int::class.javaPrimitiveType
        if (!enabled) {
            manager.javaClass.getMethod("stopTethering", type).invoke(manager, 0)
            return
        }
        val request = Class.forName("android.net.TetheringManager\$TetheringRequest")
        val builderType = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderType.getConstructor(type).newInstance(0) // TETHERING_WIFI
        val configType = Class.forName("android.net.wifi.SoftApConfiguration")
        val setConfig = runCatching { builderType.getMethod("setSoftApConfiguration", configType) }.getOrNull()
        if (setConfig != null) {
            val saved = WifiManager::class.java.getMethod("getSoftApConfiguration").invoke(wifi)
            setConfig.invoke(builder, checkNotNull(saved))
        }
        val callbackType = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val started = CompletableFuture<Int>()
        val callback = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(callbackType)) { _, method, arguments ->
            when (method.name) {
                "onTetheringStarted" -> started.complete(0)
                "onTetheringFailed" -> started.complete(arguments?.firstOrNull() as? Int ?: -1)
            }
            null
        }
        manager.javaClass.getMethod("startTethering", request, Executor::class.java, callbackType)
            .invoke(manager, builderType.getMethod("build").invoke(builder), Executor { it.run() }, callback)
        val result = started.get(5, TimeUnit.SECONDS)
        check(result == 0) { "Tethering was rejected (code $result)" }
    }
}
