package app.ezcall.android

import android.content.Context
import android.os.Build

/** The last real-call check of whether the receiver's voice reached the SIM call, kept per Android build. */
internal object UplinkHealth {
    enum class Result { REACHED, UNKNOWN }

    data class Record(val result: Result, val redirect: Boolean, val at: Long)

    private const val PREFS = "call_audio"

    fun result(wire: Int) = if (wire == AudioBridge.CHECK_REACHED) Result.REACHED else Result.UNKNOWN

    /** Saves a check. An undecided check never replaces a confirmed one. Returns the result. */
    fun save(context: Context, wire: Int, mode: Int): Result {
        val result = result(wire)
        if (result == Result.UNKNOWN && load(context)?.result == Result.REACHED) return result
        prefs(context).edit().putString("uplink_result", result.name)
            .putBoolean("uplink_redirect", mode == AudioBridge.MODE_REDIRECT)
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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
