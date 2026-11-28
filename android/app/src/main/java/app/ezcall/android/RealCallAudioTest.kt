package app.ezcall.android

/** One explicitly armed mode test, bound to the next real call taken by the receiver. */
internal class RealCallAudioTest {
    enum class Mode(val wire: Int) {
        REDIRECT(AudioBridge.MODE_REDIRECT), NORMAL(AudioBridge.MODE_IN_CALL)
    }

    enum class Result { BOTH, RECEIVER_ONLY, THIRD_PHONE_ONLY, NEITHER, UNAVAILABLE }

    data class Trial(
        val id: Int, val mode: Mode, val callId: String? = null, val observed: Set<Mode> = emptySet(),
        val ended: Boolean = false
    ) {
        val matched get() = observed == setOf(mode)
    }

    private var nextId = 0
    var trial: Trial? = null
        private set

    fun arm(mode: Mode) {
        if (trial == null) trial = Trial(++nextId, mode)
    }

    fun bind(callId: String): Trial? {
        val current = trial ?: return null
        if (current.ended || (current.callId != null && current.callId != callId)) return null
        return current.copy(callId = callId).also { trial = it }
    }

    fun observe(id: Int, wire: Int): Boolean {
        val current = trial?.takeIf { it.id == id && it.callId != null && !it.ended } ?: return false
        val mode = Mode.entries.find { it.wire == wire } ?: return false
        if (mode in current.observed) return false
        trial = current.copy(observed = current.observed + mode)
        return true
    }

    fun end(callId: String): Boolean {
        val current = trial?.takeIf { it.callId == callId && !it.ended } ?: return false
        trial = current.copy(ended = true)
        return true
    }

    fun record(id: Int, result: Result): Mode? {
        val current = trial?.takeIf { it.id == id && it.ended } ?: return null
        val canSave = if (current.matched) result != Result.UNAVAILABLE else result == Result.UNAVAILABLE
        if (!canSave) return null
        cancel()
        return current.mode
    }

    fun cancel() { trial = null }

    companion object {
        fun recommendation(redirect: Result?, normal: Result?): Mode? {
            if (redirect == null || normal == null) return null
            return when {
                redirect == Result.BOTH -> Mode.REDIRECT
                normal == Result.BOTH -> Mode.NORMAL
                else -> null
            }
        }
    }
}
