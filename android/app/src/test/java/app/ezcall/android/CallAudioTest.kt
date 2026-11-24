package app.ezcall.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class CallAudioTest {
    private val key = hex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")
    private val payload = hex("7863616c6c206f707573206672616d65")
    private val first = hex("05000000001eefac86eb7c9242798ae4954850fa8b7c654ca9f52c95227d93f866322808df")
    private val second = hex("0500000001a39088d16674cc65c144b33e7e4518ac09ac646485aad0fad543b9cd3b689a86")
    private val fromIphone = hex("05000000005e8e261bfd00994bd48e607b615f62fc17c9442abaf25a3752bf6a363eafc783")

    private fun android() = Wire.AudioSeal(key, Wire.AudioSeal.ANDROID_TO_IPHONE, Wire.AudioSeal.IPHONE_TO_ANDROID)
    private fun iphone() = Wire.AudioSeal(key, Wire.AudioSeal.IPHONE_TO_ANDROID, Wire.AudioSeal.ANDROID_TO_IPHONE)

    @Test fun audioPacketsMatchTheSharedFixture() {
        val sender = android()
        assertArrayEquals(first, sender.seal(payload))
        assertArrayEquals(second, sender.seal(payload))
        assertArrayEquals(payload, android().open(fromIphone))
    }

    @Test fun simulatedCallEchoUsesTheOppositeAuthenticatedDirection() {
        val phone = iphone()
        val relay = android()
        val uplink = phone.seal(payload)
        val echo = relay.seal(checkNotNull(relay.open(uplink)))
        assertArrayEquals(payload, phone.open(echo))
        assertNull(relay.open(uplink))
    }

    @Test fun audioAcceptsGapsButRejectsReplayTamperingAndDirection() {
        val receiver = iphone()
        assertArrayEquals(payload, receiver.open(second))
        assertNull(receiver.open(first))
        assertNull(receiver.open(second))
        val changed = second.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        assertNull(iphone().open(changed))
        assertNull(android().open(first))
        assertThrows(IllegalArgumentException::class.java) { Wire.AudioSeal(key, 1, 1) }
    }

    @Test fun bridgeFramesRoundTripAndProofsAreDirectional() {
        val bytes = ByteArrayOutputStream()
        AudioBridge.write(DataOutputStream(bytes), AudioBridge.PCM, byteArrayOf(1, 2, 3))
        val (type, body) = AudioBridge.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(AudioBridge.PCM, type)
        assertArrayEquals(byteArrayOf(1, 2, 3), body)
        val client = ByteArray(16) { 1 }
        val server = ByteArray(16) { 2 }
        assertFalse(AudioBridge.matches(AudioBridge.serverProof(key, client, server), AudioBridge.clientProof(key, client, server)))
        assertThrows(IllegalArgumentException::class.java) {
            AudioBridge.read(DataInputStream(ByteArrayInputStream(byteArrayOf(0x20, 0x7f, 0x7f))))
        }
    }

    @Test fun opusHeaderDescribesMono48k() {
        val head = Opus.head()
        assertEquals(19, head.size)
        assertEquals("OpusHead", String(head, 0, 8))
        assertEquals(1, head[9].toInt())
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0xbb.toByte(), 0, 0), head.copyOfRange(12, 16))
    }

    @Test fun dialNumbersAreNormalized() {
        assertEquals("+989121234567", RelayService.dialable(" +98 (912) 123-4567 "))
        assertEquals("09121234567", RelayService.dialable("۰۹۱۲۱۲۳۴۵۶۷"))
        assertEquals("*123#", RelayService.dialable("*123#"))
        assertNull(RelayService.dialable("12+3"))
        assertNull(RelayService.dialable("call me"))
        assertNull(RelayService.dialable(""))
    }

    @Test fun componentTokenParsesHexOnly() {
        assertArrayEquals(byteArrayOf(0, 15, -1), CallAudioServer.decodeHex("000fff"))
        assertNull(CallAudioServer.decodeHex("0g"))
        assertNull(CallAudioServer.decodeHex("123"))
    }

    @Test fun manualConnectionPortAcceptsLocalizedDigitsAndRejectsInvalidInput() {
        assertEquals(37811, LocalAdb.parseConnectPort(" ۳۷۸۱۱ "))
        assertEquals(1024, LocalAdb.parseConnectPort("1024"))
        assertEquals(65535, LocalAdb.parseConnectPort("65535"))
        for (value in listOf("", "1023", "65536", "-2000", "123456", "host:37811")) {
            assertNull(LocalAdb.parseConnectPort(value))
        }
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
