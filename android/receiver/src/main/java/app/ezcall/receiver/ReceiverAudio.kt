package app.ezcall.receiver

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import app.ezcall.android.AudioTestLog
import app.ezcall.android.Opus
import app.ezcall.android.PcmLevel
import app.ezcall.android.ReceiverTestStep
import app.ezcall.android.TestSignal
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@SuppressLint("MissingPermission") // The service enables microphone capture only after a visible, granted request.
internal class ReceiverAudio(
    private val routing: Future<*>,
    private val onPlaying: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onTestStep: ((ReceiverTestStep) -> Unit)? = null,
    private val onTestDetail: ((String) -> Unit)? = null,
    private val onTestPlayedPacket: ((Long) -> Unit)? = null,
    private val testLog: AudioTestLog? = null,
    private val echoSequence: Long? = null
) {
    private data class Packet(val bytes: ByteArray, val sequence: Long?)

    // Give the finite test cue room to arrive while Android opens the audio route.
    private val downlink = ArrayBlockingQueue<Packet>(if (echoSequence != null) 16 else 5)
    @Volatile private var running = true

    init {
        thread(name = "ezcall-playback") { runCatching { play() }.onFailure { if (running) onError(it) } }
    }

    fun startMicrophone(payloadLimit: Int, send: (ByteArray) -> Unit, onRecording: () -> Unit, onError: (Throwable) -> Unit) {
        thread(name = "ezcall-microphone") {
            runCatching { record(payloadLimit, send, onRecording) }.onFailure { if (running) onError(it) }
        }
    }

    fun offer(bytes: ByteArray, sequence: Long?): Boolean {
        val packet = Packet(bytes, sequence)
        if (downlink.offer(packet)) return false
        val dropped = downlink.poll() != null
        downlink.offer(packet)
        return dropped
    }

    fun close() {
        running = false
        downlink.clear()
    }

    private fun play() {
        routing.get()
        if (!running) return
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val format = AudioFormat.Builder().setSampleRate(Opus.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val minimum = AudioTrack.getMinBufferSize(Opus.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val sink = AudioTrack.Builder().setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        ).setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum, Opus.FRAME_BYTES * 3)).build()
        var decodedFrames = 0
        var emptyDecodes = 0
        var writtenFrames = 0
        var playbackPeak = 0
        var silentPlaybackFrames = 0
        val toneSignal = TestSignal()
        val echoSignal = TestSignal()
        var cueEnd = Long.MAX_VALUE
        var echoEnd = Long.MAX_VALUE
        var samplesWritten = 0L
        var usingEchoDecoder = false
        try {
            var decoder: Opus.Decoder? = Opus.Decoder()
            try {
                sink.setStartThresholdInFrames(minOf(sink.bufferSizeInFrames, Opus.FRAME_SAMPLES * 3))
                sink.play()
                check(sink.playState == AudioTrack.PLAYSTATE_PLAYING)
                onTestDetail?.invoke("AudioTrack: rate=${sink.sampleRate} Hz buffer=${sink.bufferSizeInFrames} frames threshold=${sink.startThresholdInFrames} frames")
                onPlaying()
                var pcmWritten = false
                while (running) {
                    if (testLog != null) {
                        val played = sink.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                        if (played >= cueEnd) {
                            onTestStep?.invoke(ReceiverTestStep.CUE_PLAYED)
                            cueEnd = Long.MAX_VALUE
                        }
                        if (played >= echoEnd) {
                            onTestStep?.invoke(ReceiverTestStep.ECHO_PLAYED)
                            echoEnd = Long.MAX_VALUE
                        }
                    }
                    val packet = downlink.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val isEcho = echoSequence != null && packet.sequence != null && packet.sequence >= echoSequence
                    if (isEcho && !usingEchoDecoder) {
                        // The tones and returned microphone packets come from different Opus encoders.
                        val oldDecoder = decoder
                        decoder = null
                        oldDecoder?.release()
                        decoder = Opus.Decoder()
                        usingEchoDecoder = true
                    }
                    val pcm = try {
                        checkNotNull(decoder).decode(packet.bytes)
                    } catch (error: Exception) {
                        onTestStep?.invoke(ReceiverTestStep.DECODE_REJECTED)
                        throw error
                    }
                    if (pcm == null) {
                        emptyDecodes++
                        continue
                    }
                    decodedFrames++
                    var offset = 0
                    while (running && offset < pcm.size) {
                        val written = sink.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) onTestDetail?.invoke("AudioTrack write failed: code=$written")
                        check(written > 0) { "Call playback write failed ($written)" }
                        offset += written
                    }
                    if (offset == pcm.size) {
                        writtenFrames++
                        if (testLog != null) {
                            val level = PcmLevel.read(pcm)
                            testLog.remoteWaveform.add(level)
                            playbackPeak = maxOf(playbackPeak, level.peak)
                            if (level.peak == 0) silentPlaybackFrames++
                            samplesWritten += pcm.size / 2
                            if (isEcho) {
                                if (echoSignal.observe(level)) echoEnd = samplesWritten
                            } else if (echoSequence != null && toneSignal.observe(level)) cueEnd = samplesWritten
                        }
                        packet.sequence?.let { onTestPlayedPacket?.invoke(it) }
                        if (!pcmWritten && onTestStep != null) {
                            pcmWritten = true
                            onTestDetail?.invoke("AudioTrack first write: routed device type=${sink.routedDevice?.type ?: "unknown"}")
                            onTestStep.invoke(ReceiverTestStep.PCM_WRITTEN)
                        }
                    }
                }
            } finally {
                decoder?.release()
            }
        } finally {
            if (onTestDetail != null) {
                val underruns = runCatching { sink.underrunCount }.getOrDefault(-1)
                onTestDetail.invoke("AudioTrack totals: decoded=$decodedFrames emptyDecodes=$emptyDecodes PCM writes=$writtenFrames underruns=$underruns silent=$silentPlaybackFrames peak=$playbackPeak/32768")
            }
            sink.release()
        }
    }

    private fun record(payloadLimit: Int, send: (ByteArray) -> Unit, onRecording: () -> Unit) {
        routing.get()
        if (!running) return
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ByteArray(Opus.FRAME_BYTES)
        val minimum = AudioRecord.getMinBufferSize(Opus.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, Opus.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, buffer.size * 3))
        var recordedFrames = 0
        var encodedPackets = 0
        var microphonePeak = 0
        var silentMicrophoneFrames = 0
        val micSignal = TestSignal()
        try {
            val encoder = Opus.Encoder(payloadLimit)
            try {
                source.startRecording()
                check(source.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                onTestDetail?.invoke("AudioRecord: rate=${source.sampleRate} Hz buffer=${source.bufferSizeInFrames} frames")
                var recording = false
                var filled = 0
                var framesUntilCheck = 0
                while (running) {
                    val length = source.read(buffer, filled, buffer.size - filled, AudioRecord.READ_BLOCKING)
                    if (length <= 0) onTestDetail?.invoke("AudioRecord read failed: code=$length")
                    check(length > 0) { "Microphone read failed ($length)" }
                    filled += length
                    if (!running || filled != buffer.size) continue
                    if (framesUntilCheck == 0) {
                        val silenced = source.activeRecordingConfiguration?.isClientSilenced == true
                        if (silenced) onTestDetail?.invoke("AudioRecord was silenced by Android")
                        check(!silenced) { "Microphone silenced by Android" }
                        framesUntilCheck = 15
                    }
                    framesUntilCheck--
                    if (!recording) {
                        recording = true
                        onTestDetail?.invoke("AudioRecord first read: routed device type=${source.routedDevice?.type ?: "unknown"}")
                        onRecording()
                    }
                    recordedFrames++
                    if (testLog != null) {
                        val level = PcmLevel.read(buffer)
                        testLog.localWaveform.add(level)
                        microphonePeak = maxOf(microphonePeak, level.peak)
                        if (level.peak == 0) silentMicrophoneFrames++
                        if (micSignal.observe(level)) onTestStep?.invoke(ReceiverTestStep.MIC_SIGNAL)
                    }
                    val packets = encoder.encode(buffer)
                    encodedPackets += packets.size
                    packets.forEach(send)
                    filled = 0
                }
            } finally {
                encoder.release()
            }
        } finally {
            onTestDetail?.invoke("AudioRecord totals: recordedFrames=$recordedFrames encodedPackets=$encodedPackets silent=$silentMicrophoneFrames peak=$microphonePeak/32768")
            source.release()
        }
    }
}
