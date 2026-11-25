package app.ezcall.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.util.BitSet

internal enum class ReceiverTestStep(val detail: String) {
    RINGING("Test call received"),
    ANSWER_QUEUED("Answer request queued"),
    ANSWER_FAILED("Answer request could not be queued"),
    ANSWER_TIMEOUT("Sender did not confirm the call action"),
    ACTIVE("Sender confirmed the call is active"),
    CALL_ERROR("Sender reported a test call error"),
    AUDIO_START("Bluetooth audio setup received"),
    INVALID_AUDIO_KEY("Audio setup contained an invalid key"),
    PLAYBACK_READY("AudioTrack started"),
    PACKET_AUTHENTICATED("First downlink packet authenticated"),
    PACKET_REJECTED("A downlink packet failed authentication or replay checks"),
    UPLINK_REJECTED("An uplink packet failed authentication or replay checks"),
    DECODE_REJECTED("Opus decoder rejected a downlink packet"),
    PCM_WRITTEN("Decoded PCM accepted by AudioTrack"),
    PLAYBACK_FAILED("Playback failed in the receiver"),
    BEEPS_HEARD("User confirmed hearing the beeps"),
    MIC_PERMISSION_MISSING("Microphone permission not granted"),
    MIC_NOT_REQUESTED("The call was answered without starting the microphone"),
    MIC_NOT_OFFERED("Sender did not offer a microphone return path"),
    MIC_FOREGROUND_FAILED("Android blocked the microphone foreground service"),
    MIC_NO_BLUETOOTH_PAYLOAD("Bluetooth cannot carry a microphone packet"),
    MIC_CAPTURE_STARTED("Android returned the first recorded microphone samples"),
    MIC_CAPTURE_FAILED("Microphone capture or encoding failed"),
    MIC_QUEUED("First microphone packet queued for Bluetooth"),
    MIC_QUEUE_REJECTED("A microphone packet could not enter the Bluetooth queue"),
    MIC_MUTED("Microphone muted"),
    MIC_UNMUTED("Microphone unmuted"),
    ECHO_HEARD("User confirmed hearing their voice back"),
    CUE_PLAYED("Android playback consumed the test tone signal"),
    MIC_SIGNAL("Receiver microphone captured a non-silent signal"),
    ECHO_PLAYED("Android playback consumed the returned microphone signal"),
    STATUS_NOT_QUEUED("A diagnostic update could not be queued for the sender"),
    LINK_LOST("Bluetooth disconnected during the test"),
    LINK_RESUMED("Bluetooth audio resumed"),
    AUDIO_NOT_RESUMED("Bluetooth reconnected without audio"),
    RECOVERY_TIMEOUT("Bluetooth recovery timed out"),
    AUDIO_STOPPED("Sender stopped test audio"),
    END_QUEUED("End request queued"),
    END_FAILED("End request could not be queued"),
    END_TIMEOUT("Sender did not confirm the end request"),
    ENDED("Test call ended")
}

internal enum class AudioTestMetric {
    UPLINK_ENCODED, UPLINK_QUEUED, UPLINK_QUEUE_DROPPED, UPLINK_QUEUE_REJECTED, UPLINK_STALE,
    UPLINK_WRITE_SUBMITTED, UPLINK_WRITE_COMPLETED, UPLINK_WRITE_FAILED, UPLINK_RECEIVED,
    UPLINK_BEFORE_ECHO_WINDOW, UPLINK_AUTHENTICATED, UPLINK_REJECTED,
    CUE_ENCODED, CUE_QUEUED, CUE_QUEUE_REJECTED, CUE_STALE, ECHO_QUEUED, ECHO_QUEUE_REJECTED,
    DOWNLINK_QUEUE_DROPPED, DOWNLINK_NOTIFY_SUBMITTED, DOWNLINK_NOTIFY_COMPLETED, DOWNLINK_NOTIFY_FAILED,
    DOWNLINK_RECEIVED, DOWNLINK_BEFORE_SETUP, DOWNLINK_AUTHENTICATED, DOWNLINK_REJECTED,
    PLAYBACK_QUEUE_DROPPED, PCM_WRITTEN
}

internal class AudioTestLog(private val role: String, val testID: String? = null, private val now: () -> Long) {
    val localWaveform = AudioWaveform()
    val remoteWaveform = AudioWaveform()
    private val startedAt = now()
    private val entries = ArrayDeque<String>()
    private val seen = mutableSetOf<ReceiverTestStep>()
    private val counts = IntArray(AudioTestMetric.entries.size)
    private val sequences = mutableMapOf<AudioTestMetric, BitSet>()
    private var device = ""

    fun elapsedMs() = now() - startedAt

    fun add(detail: String) {
        if (entries.size == 160) entries.removeFirst()
        val line = "+${elapsedMs()} ms  $detail"
        entries.addLast(line)
        if (testID != null) Log.i("EzcallTestCall", "$role $testID $line")
    }

    @Suppress("DEPRECATION")
    fun describeDevice(context: Context) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), app ${version ?: "unknown"}"
        Log.i("EzcallTestCall", "$role $testID $device")
    }

    fun count(metric: AudioTestMetric): Int = ++counts[metric.ordinal]

    fun count(metric: AudioTestMetric, amount: Int) {
        counts[metric.ordinal] += amount
    }

    fun track(metric: AudioTestMetric, sequence: Long) {
        // shortcut: ranges cover the 45-second test; raise the cap if longer tests need more sequences.
        if (sequence !in 0L..16_383L) return
        synchronized(sequences) { sequences.getOrPut(metric, ::BitSet).set(sequence.toInt()) }
    }

    fun controlLabel(message: JSONObject): String? {
        if (testID == null || message.optString("id") != testID) return null
        return when (val type = message.optString("type")) {
            "call" -> when (val state = message.optString("state")) {
                "ringing", "active", "ended" -> "call/$state"
                else -> null
            }
            "call_answer", "call_end", "call_resume", "call_resumed", "call_error", "audio_start", "audio_stop" -> type
            else -> null
        }
    }

    fun record(step: ReceiverTestStep, prefix: String = ""): Boolean {
        if (!seen.add(step)) return false
        add(prefix + step.detail)
        return true
    }

    operator fun contains(step: ReceiverTestStep) = step in seen

    fun text() = buildString {
        appendLine("EasyCall audio test · $role")
        testID?.let { appendLine("Test ID: $it") }
        if (device.isNotEmpty()) appendLine(device)
        append(entries.joinToString("\n"))
        val metrics = AudioTestMetric.entries.filter { counts[it.ordinal] != 0 }
        if (metrics.isNotEmpty()) {
            append("\n\nPacket and frame totals (nonzero)")
            metrics.forEach { append("\n${it.name}=${counts[it.ordinal]}") }
        }
        synchronized(sequences) {
            if (sequences.isNotEmpty()) append("\n\nAudio packet sequences by stage")
            AudioTestMetric.entries.forEach { metric ->
                val packets = sequences[metric] ?: return@forEach
                var start = packets.nextSetBit(0)
                val ranges = mutableListOf<String>()
                while (start >= 0 && ranges.size < 24) {
                    val end = packets.nextClearBit(start) - 1
                    ranges.add(if (start == end) "$start" else "$start-$end")
                    start = packets.nextSetBit(end + 1)
                }
                append("\n${metric.name} (${packets.cardinality()}): ${ranges.joinToString(", ")}")
                if (start >= 0) append(", … (${packets.length() - 1} last)")
            }
        }
    }

    fun show(context: Context, title: Int, copy: Int) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(text())
            .setPositiveButton(copy) { _, _ ->
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(context.getString(title), text()))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
