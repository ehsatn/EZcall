package app.ezcall.android

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object Wire {
    val service: UUID = UUID.fromString("1c79ea10-64d7-4f93-94ee-881acb0d5e74")
    val receive: UUID = UUID.fromString("1c79ea11-64d7-4f93-94ee-881acb0d5e74")
    val transmit: UUID = UUID.fromString("1c79ea12-64d7-4f93-94ee-881acb0d5e74")
    val audioIn: UUID = UUID.fromString("1c79ea13-64d7-4f93-94ee-881acb0d5e74")
    val audioOut: UUID = UUID.fromString("1c79ea14-64d7-4f93-94ee-881acb0d5e74")
    val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val MAX_FRAME = 65_536

    fun validSmsBody(body: String) = body.isNotBlank() && body.toByteArray().size <= 1_024

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun derive(psk: ByteArray, shared: ByteArray, client: ByteArray, server: ByteArray, central: Boolean = false): Session {
        val salt = hmac(psk, "xcall/v1/kdf".toByteArray() + client + server)
        val prk = hmac(salt, shared)
        val c2a = hmac(prk, "xcall/v1/c2a".toByteArray() + 1.toByte())
        val a2c = hmac(prk, "xcall/v1/a2c".toByteArray() + 1.toByte())
        val next = hmac(prk, "xcall/v1/next".toByteArray() + 1.toByte())
        return if (central) Session(a2c, c2a, next) else Session(c2a, a2c, next)
    }

    fun makeHello(psk: ByteArray, incoming: ByteArray): Pair<ByteArray, Session> {
        require(incoming.size == 66 && incoming[0] == 1.toByte())
        val client = incoming.copyOfRange(1, 66)
        val local = keyPair()
        val server = publicBytes(local)
        val shared = exchange(local, client)
        val session = try {
            derive(psk, shared, client, server)
        } finally {
            shared.fill(0)
        }
        return (byteArrayOf(2) + server + proof(psk, client, server)) to session
    }

    class CentralHello {
        private val local = keyPair()
        private val client = publicBytes(local)
        val request: ByteArray get() = byteArrayOf(1) + client

        fun accept(psk: ByteArray, reply: ByteArray): Session {
            require(reply.size == 98 && reply[0] == 2.toByte())
            val server = reply.copyOfRange(1, 66)
            require(MessageDigest.isEqual(proof(psk, client, server), reply.copyOfRange(66, 98)))
            val shared = exchange(local, server)
            return try {
                derive(psk, shared, client, server, central = true)
            } finally {
                shared.fill(0)
            }
        }
    }

    private fun proof(psk: ByteArray, client: ByteArray, server: ByteArray) =
        hmac(psk, "xcall/v1/android".toByteArray() + client + server)

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun publicBytes(pair: KeyPair): ByteArray {
        val key = pair.public as ECPublicKey
        return byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
    }

    private fun exchange(local: KeyPair, remoteBytes: ByteArray): ByteArray {
        val params = (local.public as ECPublicKey).params
        val remote = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(point(remoteBytes, params), params)
        )
        return KeyAgreement.getInstance("ECDH").run {
            init(local.private)
            doPhase(remote, true)
            generateSecret().also { require(it.size == 32) }
        }
    }

    private fun coordinate(value: BigInteger): ByteArray {
        val signed = value.toByteArray()
        val length = minOf(signed.size, 32)
        return ByteArray(32).also {
            signed.copyInto(it, 32 - length, signed.size - length)
        }
    }

    private fun point(bytes: ByteArray, params: ECParameterSpec): ECPoint {
        require(bytes.size == 65 && bytes[0] == 4.toByte())
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val curve = params.curve
        val p = (curve.field as ECFieldFp).p
        require(x < p && y < p)
        require(y.pow(2).mod(p) == (x.pow(3) + curve.a * x + curve.b).mod(p))
        return ECPoint(x, y)
    }

    fun frame(body: ByteArray): ByteArray {
        require(body.size in 1..MAX_FRAME)
        return ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
    }

    fun decode(message: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(message)).toString()

    class Reader {
        private val header = ByteBuffer.allocate(4)
        private var payload: ByteBuffer? = null

        fun feed(bytes: ByteArray, deliver: (ByteArray) -> Unit) {
            var position = 0
            while (position < bytes.size) {
                val target = payload ?: header
                val count = minOf(target.remaining(), bytes.size - position)
                target.put(bytes, position, count)
                position += count
                if (target.hasRemaining()) continue
                if (payload == null) {
                    val size = header.getInt(0)
                    header.clear()
                    require(size in 1..MAX_FRAME)
                    payload = ByteBuffer.allocate(size)
                } else {
                    payload = null
                    deliver(target.array())
                }
            }
        }
    }

    // BLE indications and acknowledged writes keep each direction strictly ordered.
    // `next` replaces the pairing secret when the receiver opts in, so a copied QR stops working.
    class Session(receiveKey: ByteArray, sendKey: ByteArray, val next: ByteArray) {
        private val receive = SecretKeySpec(receiveKey, "AES")
        private val send = SecretKeySpec(sendKey, "AES")
        private var nextReceive = 0L
        private var nextSend = 0L

        fun open(body: ByteArray): ByteArray {
            require(body.size in 25..MAX_FRAME && body[0] == 3.toByte())
            require(nextReceive < Long.MAX_VALUE && ByteBuffer.wrap(body, 1, 8).long == nextReceive)
            val plaintext = cipher(Cipher.DECRYPT_MODE, receive, nextReceive, body.copyOfRange(0, 9))
                .doFinal(body, 9, body.size - 9)
            nextReceive++
            return plaintext
        }

        fun seal(plaintext: ByteArray): ByteArray {
            require(plaintext.size <= MAX_FRAME - 25 && nextSend < Long.MAX_VALUE)
            val aad = ByteBuffer.allocate(9).put(3.toByte()).putLong(nextSend).array()
            val ciphertext = cipher(Cipher.ENCRYPT_MODE, send, nextSend, aad).doFinal(plaintext)
            nextSend++
            return aad + ciphertext
        }

        private fun cipher(mode: Int, key: SecretKeySpec, counter: Long, aad: ByteArray): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                val nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
                init(mode, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
            }
    }

    // Live call audio travels as one sealed Opus packet per BLE write or notification. Packets can be skipped
    // under congestion, so the receiver accepts any newer sequence number instead of the next one only.
    class AudioSeal(key: ByteArray, private val outgoing: Int, private val incoming: Int) {
        private val key = SecretKeySpec(key.copyOf(), "AES")
        private var nextSend = 0L
        private var lastReceived = -1L

        init {
            require(key.size == 32 && outgoing != incoming)
        }

        fun seal(payload: ByteArray): ByteArray {
            require(payload.size in 1..MAX_AUDIO && nextSend <= 0xFFFF_FFFFL)
            val header = ByteBuffer.allocate(5).put(AUDIO_PACKET).putInt(nextSend.toInt()).array()
            val sealed = cipher(Cipher.ENCRYPT_MODE, outgoing, nextSend, header).doFinal(payload)
            nextSend++
            return header + sealed
        }

        fun open(packet: ByteArray): ByteArray? {
            if (packet.size !in 22..MAX_AUDIO + 21 || packet[0] != AUDIO_PACKET) return null
            val sequence = ByteBuffer.wrap(packet, 1, 4).int.toLong() and 0xFFFF_FFFFL
            if (sequence <= lastReceived) return null
            val payload = runCatching {
                cipher(Cipher.DECRYPT_MODE, incoming, sequence, packet.copyOfRange(0, 5)).doFinal(packet, 5, packet.size - 5)
            }.getOrNull() ?: return null
            lastReceived = sequence
            return payload
        }

        private fun cipher(mode: Int, direction: Int, sequence: Long, aad: ByteArray): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                val nonce = ByteBuffer.allocate(12).putInt(direction).putInt(0).putInt(sequence.toInt()).array()
                init(mode, key, GCMParameterSpec(128, nonce))
                updateAAD(aad)
            }

        companion object {
            const val ANDROID_TO_IPHONE = 1
            const val IPHONE_TO_ANDROID = 2
            const val MAX_AUDIO = 1_024
            private const val AUDIO_PACKET: Byte = 5
        }
    }
}
