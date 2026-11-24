package app.ezcall.android

import android.content.Context

internal fun Context.senderNotice(notice: String): String = when (notice) {
    "Bluetooth LE advertising is unavailable." -> getString(R.string.s_relay_advertising)
    "Bluetooth advertising is unavailable." -> getString(R.string.s_relay_advertiser)
    "Bluetooth permission is unavailable." -> getString(R.string.s_relay_permissions)
    "Bluetooth relay" -> getString(R.string.s_relay_channel)
    "Bluetooth relay active · Waiting for the receiving phone" -> getString(R.string.s_relay_notice_wait)
    "Could not save the secure pairing." -> getString(R.string.s_relay_save_failed)
    "Could not start relay. Check Bluetooth and Nearby devices permissions." -> getString(R.string.s_relay_failed)
    "Couldn’t remove the saved pairing. Try again." -> getString(R.string.s_relay_remove_failed)
    "If the receiving phone still shows the old link, forget this phone there before scanning." -> getString(R.string.s_old_link)
    "Nearby devices permission is needed to pair." -> getString(R.string.s_nearby_pair)
    "Nearby devices permission is needed to resume the relay." -> getString(R.string.s_relay_nearby)
    "Open ezcall to start the relay." -> getString(R.string.s_relay_open_app)
    "Pairing code expired. Start pairing again." -> getString(R.string.s_relay_expired)
    "Receiving phone connected · Bluetooth relay active" -> getString(R.string.s_relay_notice_connected)
    "Reconnecting Bluetooth." -> getString(R.string.s_relay_reconnecting)
    "Relay notifications" -> getString(R.string.s_relay_notifications)
    "Removing the link from the receiving phone…" -> getString(R.string.s_relay_removing)
    "Retrying Bluetooth discovery." -> getString(R.string.s_relay_retry_bt)
    "Starting Bluetooth" -> getString(R.string.s_relay_start_bt)
    "Test audio needs the Bluetooth audio channel. Reconnect and try again." -> getString(R.string.s_relay_audio_test_failed)
    "The Bluetooth relay is active." -> getString(R.string.s_relay_active)
    "The Bluetooth service did not open." -> getString(R.string.s_relay_service_open)
    "Waiting for Bluetooth to turn on." -> getString(R.string.s_relay_wait_bt)
    "Waiting for the receiving phone" -> getString(R.string.s_waiting_receiver)
    else -> Regex("Bluetooth discovery failed \\((\\d+)\\)\\.").matchEntire(notice)?.groupValues?.get(1)
        ?.toIntOrNull()?.let { getString(R.string.s_relay_discovery_failed, it) } ?: notice
}
