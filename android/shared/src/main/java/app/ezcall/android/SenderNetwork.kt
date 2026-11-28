package app.ezcall.android

internal enum class SenderNetwork(val field: String) {
    HOTSPOT("hotspot"), MOBILE_DATA("mobile_data");

    fun command(enabled: Boolean): ByteArray =
        byteArrayOf((ordinal * 2 + if (enabled) 2 else 1).toByte())

    companion object {
        fun fromField(field: String?) = entries.firstOrNull { it.field == field }

        fun decode(payload: ByteArray): Pair<SenderNetwork, Boolean>? {
            val value = payload.singleOrNull()?.toInt() ?: return null
            if (value !in 1..4) return null
            return entries[(value - 1) / 2] to (value % 2 == 0)
        }

        fun batteryPercent(level: Int) = level.takeIf { it in 0..100 }
    }
}
