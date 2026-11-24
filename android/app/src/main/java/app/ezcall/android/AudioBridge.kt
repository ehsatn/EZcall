package app.ezcall.android

import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

// Loopback protocol between the relay and the call audio component that runs as the shell user.
// Both sides prove the shared token before any audio flows. Messages are type, 16-bit length, payload.
internal object AudioBridge {
    const val VERSION = 3
    const val SAMPLE_RATE = Opus.SAMPLE_RATE
    const val FRAME_SAMPLES = Opus.FRAME_SAMPLES
    const val FRAME_BYTES = Opus.FRAME_BYTES
    const val LIVE_QUEUE_FRAMES = 3
    const val LIVE_QUEUE_MS = LIVE_QUEUE_FRAMES * FRAME_SAMPLES * 1000L / SAMPLE_RATE

    const val HELLO = 0x01
    const val SERVER_HELLO = 0x02
    const val AUTH = 0x03
    const val START = 0x10
    const val STARTED = 0x11
    const val STOP = 0x12
    const val ROUTE = 0x13
    const val PCM = 0x20
    const val ERROR = 0x30
    const val SHUTDOWN = 0x40

    // SERVER_HELLO capability bits.
    const val CAN_INJECT = 1
    // STARTED bits.
    const val DOWNLINK = 1
    const val UPLINK = 2

    private const val SERVER_LABEL = "xcall-audio/v1/server"
    private const val CLIENT_LABEL = "xcall-audio/v1/client"
    private const val MAX_PAYLOAD = 8_192
    private val random = SecureRandom()

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
