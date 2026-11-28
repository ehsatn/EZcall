package app.ezcall.android

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

// Loopback protocol between the relay and the call audio component that runs as the shell user.
// Both sides prove the shared token before any audio flows. Messages are type, 16-bit length, payload.
internal object AudioBridge {
    const val VERSION = 20
    const val SAMPLE_RATE = Opus.SAMPLE_RATE
    const val FRAME_SAMPLES = Opus.FRAME_SAMPLES
    const val FRAME_BYTES = Opus.FRAME_BYTES
    const val LIVE_QUEUE_FRAMES = 5
    const val LIVE_QUEUE_MS = LIVE_QUEUE_FRAMES * FRAME_SAMPLES * 1000L / SAMPLE_RATE

    const val HELLO = 0x01
    const val SERVER_HELLO = 0x02
    const val AUTH = 0x03
    const val START = 0x10
    // Optional START payload: keep Telecom's normal call mode instead of redirecting the call off the handset.
    const val START_NORMAL_MODE = 1
    const val STARTED = 0x11
    const val STOP = 0x12
    const val RECEIVER_READY = 0x13
    const val RECEIVER_RECOVERING = 2
    const val STOP_FOR_RECOVERY = 1
    const val START_TRIAL = 0x14
    const val TRIAL_MODE = 0x15
    const val PCM = 0x20
    const val DTMF = 0x21
    const val DTMF_DIGITS = "123456789*0#"
    const val ERROR = 0x30
    const val UPLINK_CHECK = 0x31
    const val SHUTDOWN = 0x40
    const val DIAGNOSE = 0x50
    const val DIAGNOSTICS = 0x51
    const val NETWORK = 0x52

    // SERVER_HELLO capability bits.
    const val CAN_INJECT = 1
    const val CAN_CONTROL_NETWORK = 2
    // STARTED bits.
    const val DOWNLINK = 1
    const val UPLINK = 2
    // UPLINK_CHECK payload: result, then the audio mode the check ran in.
    const val CHECK_REACHED = 1
    const val CHECK_UNKNOWN = 3 // 2 meant "not reached" before 0.1.26; uplink capture can't prove that.
    const val MODE_REDIRECT = 1
    const val MODE_IN_CALL = 2
    const val MAX_TEXT = 8_000
    const val UPLINK_GAIN_DB = 6
    const val DOWNLINK_GAIN_DB = 3
    val uplinkGain = 10.0.pow(UPLINK_GAIN_DB / 20.0)
    val downlinkGain = 10.0.pow(DOWNLINK_GAIN_DB / 20.0)

    private const val SERVER_LABEL = "xcall-audio/v1/server"
    private const val CLIENT_LABEL = "xcall-audio/v1/client"
    private const val MAX_PAYLOAD = 8_192
    private const val DTMF_TONE_SAMPLES = FRAME_SAMPLES * 8
    private const val DTMF_COMPONENT_PEAK = 1_802
    private val random = SecureRandom()

    fun trialPayload(id: Int, mode: Int): ByteArray = ByteBuffer.allocate(5).putInt(id).put(mode.toByte()).array()

    fun readTrial(payload: ByteArray): Pair<Int, Int>? {
        if (payload.size != 5) return null
        val buffer = ByteBuffer.wrap(payload)
        val id = buffer.int
        val mode = buffer.get().toInt()
        return if (mode == MODE_REDIRECT || mode == MODE_IN_CALL) id to mode else null
    }

    fun boostVoice(pcm: ByteArray, gain: Double) {
        val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var peak = 0
        for (i in 0 until samples.limit()) peak = maxOf(peak, abs(samples[i].toInt()))
        if (peak == 0) return
        // Back off the boost for loud frames, preserving headroom and any already-loud signal.
        val applied = minOf(gain, maxOf(1.0, 30_000.0 / peak))
        if (applied == 1.0) return
        for (i in 0 until samples.limit()) samples.put(i, (samples[i] * applied).toInt().toShort())
    }

    private fun dtmfEnvelope(sample: Int) =
        minOf(1.0, minOf(sample, DTMF_TONE_SAMPLES - 1 - sample) / (SAMPLE_RATE * 0.004))

    fun dtmfPcm(digit: Char): ByteArray {
        val index = DTMF_DIGITS.indexOf(digit)
        require(index >= 0)
        val low = intArrayOf(697, 770, 852, 941)[index / 3]
        val high = intArrayOf(1209, 1336, 1477)[index % 3]
        val pcm = ByteArray(FRAME_BYTES * 12)
        for (sample in 0 until DTMF_TONE_SAMPLES) {
            val phase = 2 * PI * sample / SAMPLE_RATE
            val value = (DTMF_COMPONENT_PEAK * dtmfEnvelope(sample) * (sin(low * phase) + sin(high * phase))).toInt()
            pcm[2 * sample] = value.toByte()
            pcm[2 * sample + 1] = (value shr 8).toByte()
        }
        return pcm
    }

    fun mixDtmf(pcm: ByteArray, tone: ByteArray, offset: Int) {
        val voice = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val signal = ByteBuffer.wrap(tone).order(ByteOrder.LITTLE_ENDIAN)
        val end = minOf(offset + pcm.size, DTMF_TONE_SAMPLES * 2)
        for (position in offset until end step 2) {
            // Fade in just enough microphone headroom to keep the tone from clipping.
            val gain = 1 - dtmfEnvelope(position / 2) * (2.0 * DTMF_COMPONENT_PEAK / Short.MAX_VALUE)
            val index = position - offset
            val mixed = (voice.getShort(index) * gain).toInt() + signal.getShort(position)
            voice.putShort(index, mixed.toShort())
        }
    }

    class UplinkPcm {
        private val gap = ByteArray(FRAME_BYTES)
        private val fade = SAMPLE_RATE / 250
        private var lastSample = 0
        private var fadeIn = fade

        fun frame(pcm: ByteArray?): ByteArray {
            if (pcm == null) {
                // Keep the track fed and taper the last microphone sample into silence.
                gap.fill(0)
                if (lastSample != 0) {
                    val samples = ByteBuffer.wrap(gap).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    for (i in 0 until fade) samples.put(i, (lastSample * (fade - i - 1) / fade).toShort())
                }
                lastSample = 0
                fadeIn = 0
                return gap
            }
            val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until minOf(samples.limit(), fade - fadeIn)) {
                fadeIn++
                samples.put(i, (samples[i] * fadeIn / fade).toShort())
            }
            lastSample = samples[samples.limit() - 1].toInt()
            return pcm
        }
    }

    fun nonce(): ByteArray = ByteArray(16).also(random::nextBytes)

    fun serverProof(token: ByteArray, client: ByteArray, server: ByteArray) =
        Wire.hmac(token, SERVER_LABEL.toByteArray() + client + server)

    fun clientProof(token: ByteArray, client: ByteArray, server: ByteArray) =
        Wire.hmac(token, CLIENT_LABEL.toByteArray() + client + server)

    fun matches(expected: ByteArray, actual: ByteArray) = MessageDigest.isEqual(expected, actual)

    fun write(output: DataOutputStream, type: Int, payload: ByteArray = ByteArray(0)) {
        require(payload.size <= MAX_PAYLOAD)
        synchronized(output) {
            output.writeByte(type)
            output.writeShort(payload.size)
            output.write(payload)
            output.flush()
        }
    }

    fun read(input: DataInputStream): Pair<Int, ByteArray> {
        val type = input.readUnsignedByte()
        val size = input.readUnsignedShort()
        require(size <= MAX_PAYLOAD)
        return type to ByteArray(size).also(input::readFully)
    }
}
