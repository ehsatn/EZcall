package app.ezcall.android

import android.content.Context
import android.os.Build

/** Real-call checks, with positive evidence kept per audio mode and Android build. */
internal object UplinkHealth {
    enum class Result { REACHED, UNKNOWN }

    data class Record(val result: Result, val redirect: Boolean, val at: Long)

    private const val PREFS = "call_audio"

    fun result(wire: Int) = if (wire == AudioBridge.CHECK_REACHED) Result.REACHED else Result.UNKNOWN

    fun prefersNormal(redirectConfirmed: Boolean, normalConfirmed: Boolean) = normalConfirmed && !redirectConfirmed

    fun confirmed(context: Context, redirect: Boolean): Boolean =
        prefs(context).getString(confirmedBuildKey(redirect), null) == Build.FINGERPRINT ||
            load(context)?.let { it.result == Result.REACHED && it.redirect == redirect } == true

    /** Saves the latest check and keeps past confirmations even when this check is undecided. */
    fun save(context: Context, wire: Int, mode: Int): Result {
        val result = result(wire)
        val previous = load(context)
        val redirect = mode == AudioBridge.MODE_REDIRECT
        val edit = prefs(context).edit()
        // Retain a confirmation made before each mode had its own record.
        if (previous?.result == Result.REACHED) edit.putString(confirmedBuildKey(previous.redirect), Build.FINGERPRINT)
        if (result == Result.REACHED) {
            edit.putString(confirmedBuildKey(redirect), Build.FINGERPRINT)
        }
        edit.putString("uplink_result", result.name)
            .putBoolean("uplink_redirect", redirect)
            .putLong("uplink_at", System.currentTimeMillis())
            .putString("uplink_build", Build.FINGERPRINT).apply()
        return result
    }

    fun load(context: Context): Record? {
        val prefs = prefs(context)
        // A system update can change the audio HAL, so an old result says nothing about the current build.
        // A "not reached" result saved by 0.1.25 no longer matches any result and is ignored.
        if (prefs.getString("uplink_build", null) != Build.FINGERPRINT) return null
        val result = Result.entries.firstOrNull { it.name == prefs.getString("uplink_result", null) } ?: return null
        return Record(result, prefs.getBoolean("uplink_redirect", false), prefs.getLong("uplink_at", 0))
    }

    private fun confirmedBuildKey(redirect: Boolean) = if (redirect) "uplink_redirect_build" else "uplink_normal_build"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
