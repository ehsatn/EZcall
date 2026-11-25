package app.ezcall.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.muntashirakon.adb.PairingConnectionCtx

@SuppressLint("MissingPermission")
class WirelessPairing : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val expiresAt = intent.getLongExtra(EXPIRY, 0)
        if (expiresAt <= SystemClock.elapsedRealtime()) return
        val notifications = context.getSystemService(NotificationManager::class.java)
        val input = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)
        val entry = parse(input)
        if (entry == null) {
            showPrompt(context, expiresAt, context.ezcallContext().getString(R.string.s_wireless_six_digits))
            return
        }

        notifications.notify(NOTICE_ID, notification(context, context.ezcallContext().getString(R.string.s_wireless_pairing)).build())
        val result = goAsync()
        Thread {
            try {
                val (code, suppliedPort) = entry
                val port = suppliedPort ?: runCatching { findPort(context) }.getOrNull()
                when {
                    port == null -> showPrompt(context, expiresAt,
                        context.ezcallContext().getString(R.string.s_wireless_window))
                    !tryPair(code, port) -> showPrompt(context, expiresAt,
                        context.ezcallContext().getString(R.string.s_wireless_failed))
                    else -> {
                        preferences(context).edit().putBoolean("paired", true).apply()
                        notifications.notify(NOTICE_ID, notification(context,
                            context.ezcallContext().getString(R.string.s_wireless_success)).setAutoCancel(true).build())
                    }
                }
            } finally {
                result.finish()
            }
        }.start()
    }

    private fun findPort(context: Context): Int? =
        LocalAdb.findPort(context, LocalAdb.TLS_PAIRING, 3_000)

    private fun pair(code: String, port: Int) {
        val key = LocalAdb.key()
        val password = code.toByteArray(Charsets.US_ASCII)
        try {
            PairingConnectionCtx("127.0.0.1", port, password, key.privateKey, key.certificate, "ezcall").use {
                it.start()
            }
        } finally {
            password.fill(0)
        }
    }

    private fun tryPair(code: String, port: Int): Boolean = runCatching { pair(code, port) }
        .onFailure { error ->
            Log.w("XcallPairing", generateSequence(error) { it.cause }.take(4)
                .joinToString(" caused by ") { it.javaClass.simpleName })
        }.isSuccess

    companion object {
        private const val CHANNEL = "wireless_debug_pairing"
        private const val CODE = "pairing_code"
        private const val EXPIRY = "expires_at"
        private const val NOTICE_ID = 42
        private const val WINDOW_MS = 120_000L

        private fun preferences(context: Context) =
            context.getSharedPreferences("wireless_debugging", Context.MODE_PRIVATE)

        fun isPaired(context: Context) = preferences(context).getBoolean("paired", false) && LocalAdb.hasKey()

        fun begin(context: Context): Boolean {
            val notifications = context.getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, context.ezcallContext().getString(R.string.s_wireless_channel), NotificationManager.IMPORTANCE_DEFAULT))
            if (!notifications.areNotificationsEnabled() ||
                notifications.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return false
            showPrompt(context, SystemClock.elapsedRealtime() + WINDOW_MS,
                context.ezcallContext().getString(R.string.s_wireless_leave_open))
            return true
        }

        private fun showPrompt(context: Context, expiresAt: Long, message: String) {
            val notifications = context.getSystemService(NotificationManager::class.java)
            val remaining = expiresAt - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                notifications.cancel(NOTICE_ID)
                return
            }
            val reply = PendingIntent.getBroadcast(context, NOTICE_ID,
                Intent(context, WirelessPairing::class.java).putExtra(EXPIRY, expiresAt),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_ONE_SHOT)
            val code = RemoteInput.Builder(CODE).setLabel(context.ezcallContext().getString(R.string.s_wireless_code)).build()
            val action = Notification.Action.Builder(null, context.ezcallContext().getString(R.string.s_wireless_enter), reply)
                .setAuthenticationRequired(true).setAllowGeneratedReplies(false).addRemoteInput(code).build()
            notifications.notify(NOTICE_ID, notification(context, message).addAction(action).setTimeoutAfter(remaining).build())
        }

        private fun notification(context: Context, message: String) = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_ezcall_notification)
            .setContentTitle(context.ezcallContext().getString(R.string.s_wireless_channel))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)

        internal fun parse(input: CharSequence?): Pair<String, Int?>? {
            val value = input?.trim()?.takeIf { it.length in 6..12 } ?: return null
            val parts = value.split(':', limit = 2)
            val code = parts[0].map { it.digitToIntOrNull() ?: return null }.joinToString("")
            if (code.length != 6) return null
            val port = parts.getOrNull(1)?.let { value ->
                val normalized = value.map { it.digitToIntOrNull() ?: return null }.joinToString("")
                normalized.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
            }
            return code to port
        }
    }
}
