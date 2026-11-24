package app.ezcall.android

internal class CallRecovery(val id: String, disconnectedAt: Long) {
    private val deadline = disconnectedAt + WINDOW_MS

    fun accepts(requestedId: String, now: Long): Boolean = requestedId == id && now < deadline

    companion object {
        const val WINDOW_MS = 20_000L
    }
}
