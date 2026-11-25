package app.ezcall.android

internal data class TestCallReport(
    val stage: Stage = Stage.STARTING,
    val audioStarted: Boolean = false,
    val cue: Cue = Cue.WAITING,
    val playbackConfirmed: Boolean = false,
    val microphoneSignal: Boolean = false,
    val microphoneReceived: Boolean = false,
    val echoQueued: Boolean = false,
    val echoConfirmed: Boolean = false
) {
    val verified get() = playbackConfirmed && microphoneSignal && echoConfirmed
    enum class Stage(val label: Int) {
        STARTING(R.string.s_test_starting),
        RINGING(R.string.s_test_ringing),
        AUDIO(R.string.s_test_audio_started),
        RECONNECTING(R.string.s_test_reconnecting),
        FINISHED(R.string.s_test_finished),
        NO_ANSWER(R.string.s_test_no_answer),
        PHONE_BUSY(R.string.s_test_phone_busy),
        SIGNAL_FAILED(R.string.s_test_signal_failed),
        NO_AUDIO(R.string.s_relay_audio_test_failed),
        LINK_LOST(R.string.s_test_link_lost)
    }

    enum class Cue(val label: Int) {
        WAITING(R.string.s_test_cue_missing),
        QUEUED(R.string.s_test_cue_queued),
        FAILED(R.string.s_test_cue_failed)
    }

    fun receivedMicrophone(queued: Boolean): TestCallReport {
        if (microphoneReceived && (echoQueued || !queued)) return this
        return copy(microphoneReceived = true, echoQueued = queued)
    }
}
