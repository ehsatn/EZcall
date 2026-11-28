package app.ezcall.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Process
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@SuppressLint("DiscouragedPrivateApi", "PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
internal object SenderNetworkControl {
    fun allowed(context: Context) = preferences(context).getBoolean("enabled", false)

    fun allow(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("enabled", enabled).apply()
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

    /** This uses the same authenticated local component as call audio. */
    fun request(context: Context, payload: ByteArray, authorized: () -> Boolean): JSONObject? = runCatching {
        val link = CallAudio.Setup.open(context) ?: return null
        link.socket.use {
            if (!link.networkControl || !authorized()) return null
            it.soTimeout = if (payload.isEmpty()) 3_000 else 12_000
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
        val tethering = runCatching { context.getSystemService("tethering") }.getOrNull()
        val canTether = tethering != null &&
            (Process.myUid() == 0 || context.hasPermission("android.permission.TETHER_PRIVILEGED"))
        val telephony = if (!telephonyReady) null else runCatching {
            val sim = SubscriptionManager.getDefaultDataSubscriptionId()
            if (!SubscriptionManager.isValidSubscriptionId(sim)) null
            else TelephonyManager::class.java.getConstructor(Context::class.java).newInstance(context)
                .createForSubscriptionId(sim)
        }.getOrNull()
        val canChangeData = telephony != null &&
            (Process.myUid() == 0 || context.hasPermission("android.permission.MODIFY_PHONE_STATE"))

        fun state(setting: SenderNetwork): Boolean? = runCatching {
            when (setting) {
                SenderNetwork.HOTSPOT -> if (canTether && wifi != null) {
                    when (WifiManager::class.java.getMethod("getWifiApState").invoke(wifi)) {
                        11, 14 -> false // WIFI_AP_STATE_DISABLED or WIFI_AP_STATE_FAILED
                        13 -> true // WIFI_AP_STATE_ENABLED
                        else -> null
                    }
                } else null
                SenderNetwork.MOBILE_DATA -> if (canChangeData) {
                    telephony?.isDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER)
                } else null
            }
        }.getOrNull()

        val reply = JSONObject()
        if (change != null) {
            val (setting, enabled) = change
            val ok = runCatching {
                val before = state(setting)
                if (before == null) false
                else if (before == enabled) true
                else {
                    when (setting) {
                        SenderNetwork.HOTSPOT -> setHotspot(checkNotNull(tethering), enabled)
                        SenderNetwork.MOBILE_DATA -> checkNotNull(telephony)
                            .setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, enabled)
                    }
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    while (state(setting) != enabled && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                    state(setting) == enabled
                }
            }.getOrDefault(false)
            reply.put("ok", ok)
        }
        for (setting in SenderNetwork.entries) reply.put(setting.field, state(setting))
        return reply.toString().toByteArray()
    }

    // Android 12–15 expose these APIs to system components only. Use the saved hotspot configuration.
    private fun setHotspot(manager: Any, enabled: Boolean) {
        val type = Int::class.javaPrimitiveType
        if (!enabled) {
            manager.javaClass.getMethod("stopTethering", type).invoke(manager, 0)
            return
        }
        val request = Class.forName("android.net.TetheringManager\$TetheringRequest")
        val builderType = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderType.getConstructor(type).newInstance(0) // TETHERING_WIFI
        val callbackType = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val started = CompletableFuture<Boolean>()
        val callback = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(callbackType)) { _, method, _ ->
            when (method.name) {
                "onTetheringStarted" -> started.complete(true)
                "onTetheringFailed" -> started.complete(false)
            }
            null
        }
        manager.javaClass.getMethod("startTethering", request, Executor::class.java, callbackType)
            .invoke(manager, builderType.getMethod("build").invoke(builder), Executor { it.run() }, callback)
        check(started.get(5, TimeUnit.SECONDS))
    }
}
