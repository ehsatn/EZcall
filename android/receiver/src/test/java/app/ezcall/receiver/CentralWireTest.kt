package app.ezcall.receiver

import app.ezcall.android.PairKey
import app.ezcall.android.Wire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CentralWireTest {
    @Test fun receivesTheExistingSenderProtocolInBothDirections() {
        val key = ByteArray(32) { it.toByte() }
        val central = Wire.CentralHello()
        val (reply, server) = Wire.makeHello(key, central.request)
        val client = central.accept(key, reply)
        val ready = """{"type":"ready"}""".toByteArray()
        val frame = Wire.frame(client.seal(ready))
        val reader = Wire.Reader()
        val delivered = mutableListOf<ByteArray>()
        frame.asList().chunked(20).forEach { reader.feed(it.toByteArray(), delivered::add) }
        assertArrayEquals(ready, server.open(delivered.single()))
        val ack = server.seal(ready)
        assertArrayEquals(ready, client.open(ack))
        assertThrows(IllegalArgumentException::class.java) { client.open(ack) }
    }

    @Test fun rejectsAWrongQrSecretAndAChangedProof() {
        val key = ByteArray(32) { 8 }
        val central = Wire.CentralHello()
        val (reply, _) = Wire.makeHello(key, central.request)
        assertThrows(IllegalArgumentException::class.java) { central.accept(ByteArray(32) { 9 }, reply) }
        reply[reply.lastIndex] = (reply.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { central.accept(key, reply) }
    }

    @Test fun rotatedKeyReplacesTheQrSecret() {
        val qr = ByteArray(32) { 7 }
        val first = Wire.CentralHello()
        val (reply, server) = Wire.makeHello(qr, first.request)
        val client = first.accept(qr, reply)
        assertArrayEquals(server.next, client.next)

        // Next connection: Android uses the rotated key, the receiver still has the QR secret as current.
        val second = Wire.CentralHello()
        val (rotated, _) = Wire.makeHello(client.next, second.request)
        assertThrows(IllegalArgumentException::class.java) { second.accept(qr, rotated) }
        second.accept(client.next, rotated)
        // Someone holding only the QR can no longer pass as Android.
        val third = Wire.CentralHello()
        val (copied, _) = Wire.makeHello(qr, third.request)
        assertThrows(IllegalArgumentException::class.java) { third.accept(client.next, copied) }
    }

    @Test fun acceptsExistingQrAndRejectsMalformedCredentials() {
        val code = "xcall:v1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        assertArrayEquals(ByteArray(32) { it.toByte() }, PairKey.parseQr(code))
        assertNull(PairKey.parseQr(code + "="))
        assertNull(PairKey.parseQr(code.dropLast(1) + "9"))
        assertNull(PairKey.parseQr("other:" + code))
    }
}
