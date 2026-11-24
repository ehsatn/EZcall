package app.ezcall.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

class WireTest {
    private val psk = ByteArray(32) { it.toByte() }
    private val client = hex("046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
    private val server = hex("047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc4766997807775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1")
    private val ready = """{"type":"ready"}""".toByteArray()

    @Test fun fixtureAndReplayProtection() {
        // The fixture's private keys are one and two, so the ECDH secret is the x coordinate of 2G.
        val session = Wire.derive(psk, server.copyOfRange(1, 33), client, server)
        val encrypted = hex("0300000000000000008a1245c8a8e7912ad2ccaa36dca4af117cff601fec7b37602d6e56b33b8e5a4d")
        val changed = encrypted.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }

        assertThrows(AEADBadTagException::class.java) { session.open(changed) }
        assertArrayEquals(ready, session.open(encrypted))
        assertThrows(IllegalArgumentException::class.java) { session.open(encrypted) }

        assertArrayEquals(
            hex("0300000000000000009d5d3baeb1ced15982fc23d3a9aea7862392c2082463e9660b4f9a8e67af5ef3"),
            session.seal(ready)
        )
    }

    @Test fun validatesPeerPointAndProvesAndroidIdentity() {
        val (response, session) = Wire.makeHello(psk, byteArrayOf(1) + client)
        assertEquals(98, response.size)
        assertEquals(2.toByte(), response[0])
        assertEquals(4.toByte(), response[1])
        assertArrayEquals(
            Wire.hmac(psk, "xcall/v1/android".toByteArray() + client + response.copyOfRange(1, 66)),
            response.copyOfRange(66, 98)
        )
        val androidPoint = response.copyOfRange(1, 66)
        // The fixture's client private scalar is one, so the generated x coordinate is the reciprocal ECDH result.
        val expected = Wire.derive(psk, androidPoint.copyOfRange(1, 33), client, androidPoint)
        assertArrayEquals(expected.seal(ready), session.seal(ready))
        assertThrows(IllegalArgumentException::class.java) {
            Wire.makeHello(psk, byteArrayOf(1, 4) + ByteArray(64))
        }
    }

    @Test fun framesSplitAtEveryByteAndRejectsOversize() {
        val reader = Wire.Reader()
        val bodies = mutableListOf<ByteArray>()
        val bytes = Wire.frame(ready) + Wire.frame(byteArrayOf(42))
        bytes.forEach { reader.feed(byteArrayOf(it), bodies::add) }
        assertEquals(2, bodies.size)
        assertArrayEquals(ready, bodies[0])
        assertArrayEquals(byteArrayOf(42), bodies[1])
        assertThrows(IllegalArgumentException::class.java) {
            reader.feed(byteArrayOf(0, 1, 0, 1), bodies::add)
        }
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
