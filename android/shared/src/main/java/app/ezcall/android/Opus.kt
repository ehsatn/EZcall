package app.ezcall.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

// 48 kHz mono Opus in 20 ms packets, matching the Bluetooth audio protocol.
internal object Opus {
    const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
    const val SAMPLE_RATE = 48_000
    const val FRAME_SAMPLES = 960
    const val FRAME_BYTES = FRAME_SAMPLES * 2
    const val BIT_RATE = 20_000
    const val PRE_SKIP = 312
    private const val WAIT_US = 10_000L
    private const val OUTPUT_WAIT_US = 2_000L

    fun head(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray()).put(1).put(1).putShort(PRE_SKIP.toShort())
        .putInt(SAMPLE_RATE).putShort(0).put(0).array()

    fun nanos(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    class Encoder {
        private val codec = MediaCodec.createEncoderByType(MIME)
        private val info = MediaCodec.BufferInfo()
        private var timeUs = 0L

        init {
            try {
                val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES)
                }
                val modes = codec.codecInfo.getCapabilitiesForType(MIME).encoderCapabilities
                if (modes?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            } catch (error: Exception) {
                codec.release()
                throw error
            }
        }

        fun encode(pcm: ByteArray): List<ByteArray> {
            val index = codec.dequeueInputBuffer(WAIT_US)
            if (index >= 0) {
                val buffer = checkNotNull(codec.getInputBuffer(index))
                buffer.clear()
                val size = minOf(pcm.size, buffer.remaining())
                buffer.put(pcm, 0, size)
                codec.queueInputBuffer(index, 0, size, timeUs, 0)
                timeUs += size / 2 * 1_000_000L / SAMPLE_RATE
            }
            return drain(codec, info, if (index >= 0) OUTPUT_WAIT_US else 0)
        }

        fun release() {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    class Decoder {
        private val codec = MediaCodec.createDecoderByType(MIME)
        private val info = MediaCodec.BufferInfo()
        private var timeUs = 0L

        init {
            try {
                val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(head()))
                    setByteBuffer("csd-1", ByteBuffer.wrap(nanos(PRE_SKIP * 1_000_000_000L / SAMPLE_RATE)))
                    setByteBuffer("csd-2", ByteBuffer.wrap(nanos(80_000_000L)))
                }
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (error: Exception) {
                codec.release()
                throw error
            }
        }

        fun decode(packet: ByteArray): ByteArray? {
            val index = codec.dequeueInputBuffer(WAIT_US)
            if (index >= 0) {
                val buffer = checkNotNull(codec.getInputBuffer(index))
                buffer.clear()
                if (packet.size > buffer.remaining()) {
                    codec.queueInputBuffer(index, 0, 0, timeUs, 0)
                } else {
                    buffer.put(packet)
                    codec.queueInputBuffer(index, 0, packet.size, timeUs, 0)
                    timeUs += 20_000
                }
            }
            val output = drain(codec, info, if (index >= 0) OUTPUT_WAIT_US else 0)
            return when (output.size) {
                0 -> null
                1 -> output[0]
                else -> output.reduce(ByteArray::plus)
            }
        }

        fun release() {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, firstWaitUs: Long): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        // A brief first wait lets the last frame through even when no later packet arrives to drain it.
        var waitUs = firstWaitUs
        while (true) {
            val index = codec.dequeueOutputBuffer(info, waitUs)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return result
            if (index < 0) continue
            waitUs = 0
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                result += ByteArray(info.size).also { buffer.get(it) }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }
}
