package app.ezcall.receiver

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import app.ezcall.android.Opus
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@SuppressLint("MissingPermission") // The service enables microphone capture only after a visible, granted request.
internal class ReceiverAudio(
    private val mic: Boolean,
    private val send: (ByteArray) -> Unit,
    private val onPlaying: () -> Unit,
    private val onError: () -> Unit
) {
    private val downlink = ArrayBlockingQueue<ByteArray>(5)
    @Volatile private var running = true
    private val format = AudioFormat.Builder().setSampleRate(Opus.SAMPLE_RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()

    init {
        thread(name = "ezcall-playback") { runCatching { play() }.onFailure { if (running) onError() } }
        if (mic) thread(name = "ezcall-microphone") {
            runCatching { record() }.onFailure { if (running) onError() }
        }
    }

    fun offer(packet: ByteArray) {
        if (!downlink.offer(packet)) {
            downlink.poll()
            downlink.offer(packet)
        }
    }

    fun close() {
        running = false
        downlink.clear()
    }

    private fun play() {
        val minimum = AudioTrack.getMinBufferSize(Opus.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val sink = AudioTrack.Builder().setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        ).setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum, Opus.FRAME_BYTES * 3)).build()
        try {
            val decoder = Opus.Decoder()
            try {
                sink.play()
                onPlaying()
                while (running) {
                    val packet = downlink.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val pcm = decoder.decode(packet) ?: continue
                    check(sink.write(pcm, 0, pcm.size) >= 0)
                }
            } finally {
                decoder.release()
            }
        } finally {
            sink.release()
        }
    }

    private fun record() {
        val buffer = ByteArray(Opus.FRAME_BYTES)
        val minimum = AudioRecord.getMinBufferSize(Opus.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, Opus.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, buffer.size * 3))
        try {
            val encoder = Opus.Encoder()
            try {
                source.startRecording()
                check(source.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                while (running) {
                    val length = source.read(buffer, 0, buffer.size)
                    check(length >= 0)
                    if (running && length == buffer.size) encoder.encode(buffer).forEach(send)
                }
            } finally {
                encoder.release()
            }
        } finally {
            source.release()
        }
    }
}
