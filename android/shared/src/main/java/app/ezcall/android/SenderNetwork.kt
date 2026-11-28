package app.ezcall.android

internal enum class SenderNetwork(val field: String) {
    HOTSPOT("hotspot"), MOBILE_DATA("mobile_data"), INTERNET("internet");

    fun supports(enabled: Boolean) = enabled || this != INTERNET

    fun command(enabled: Boolean): ByteArray =
        byteArrayOf((ordinal * 2 + if (enabled) 2 else 1).toByte())

    companion object {
        fun fromField(field: String?) = entries.firstOrNull { it.field == field }

        fun decode(payload: ByteArray): Pair<SenderNetwork, Boolean>? {
            val value = payload.singleOrNull()?.toInt() ?: return null
            if (value !in 1..entries.size * 2) return null
            val setting = entries[(value - 1) / 2]
            val enabled = value % 2 == 0
            return if (setting.supports(enabled)) setting to enabled else null
        }

        fun batteryPercent(level: Int) = level.takeIf { it in 0..100 }
    }
}
