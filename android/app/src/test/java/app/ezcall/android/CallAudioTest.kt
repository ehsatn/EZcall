package app.ezcall.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test fun audioAndSmsFragmentsAlternateWithoutDelayingCallControl() {
        assertTrue(audioBeforeControl(controlIsSms = true, afterAudio = false, queuedAudio = 1))
        assertFalse(audioBeforeControl(controlIsSms = true, afterAudio = true, queuedAudio = 1))
        assertTrue(audioBeforeControl(controlIsSms = true, afterAudio = true, queuedAudio = 2))
        assertFalse(audioBeforeControl(controlIsSms = false, afterAudio = false, queuedAudio = 2))
        assertTrue(audioBeforeControl(controlIsSms = null, afterAudio = true, queuedAudio = 1))
    }

    @Test fun simulatedCallEchoUsesTheOppositeAuthenticatedDirection() {
        val phone = iphone()
        val relay = android()
        val uplink = phone.seal(payload)
        val echo = relay.seal(checkNotNull(relay.open(uplink)))
        assertArrayEquals(payload, phone.open(echo))
        assertNull(relay.open(uplink))
    }

    @Test fun simulatedToneAndEchoUseDistinctAuthenticatedSequenceRanges() {
        val relay = android()
        val receiver = iphone()
        val tone = relay.seal(payload)
        assertEquals(0L, Wire.AudioSeal.sequence(tone))
        assertArrayEquals(payload, receiver.open(tone))

        relay.advanceOutgoingSequence(TEST_ECHO_SEQUENCE)
        val echo = relay.seal(payload)
        assertEquals(TEST_ECHO_SEQUENCE, Wire.AudioSeal.sequence(echo))
        assertArrayEquals(payload, receiver.open(echo))
        assertNull(receiver.open(tone))
        assertThrows(IllegalArgumentException::class.java) { relay.advanceOutgoingSequence(TEST_ECHO_SEQUENCE) }
    }

    @Test fun automaticSignalRequiresSustainedNonSilentPcmAndEmitsOnce() {
        val quiet = PcmLevel.read(ByteArray(Opus.FRAME_BYTES))
        val tone = PcmLevel.read(SimulationTone.frame(5))
        assertEquals(PcmLevel(0, 0), quiet)
        assertTrue(tone.peak >= tone.rms)
        assertTrue(tone.rms >= 160)

        val signal = TestSignal()
        assertFalse(signal.observe(tone))
        assertFalse(signal.observe(quiet))
        assertFalse(signal.observe(tone))
        assertTrue(signal.observe(tone))
        assertFalse(signal.observe(tone))
    }

    @Test fun downlinkAudioPairsCarryBothFramesAndRejectInvalidSizes() {
        val pair = Wire.AudioSeal.pair(hex("aabb"), hex("cc"))
        assertArrayEquals(hex("0002aabbcc"), iphone().open(android().seal(pair)))
        val (first, second) = checkNotNull(Wire.AudioSeal.splitPair(pair))
        assertArrayEquals(hex("aabb"), first)
        assertArrayEquals(hex("cc"), second)
        assertNull(Wire.AudioSeal.splitPair(hex("0002aabb")))
        assertThrows(IllegalArgumentException::class.java) { Wire.AudioSeal.pair(byteArrayOf(), payload) }
        assertThrows(IllegalArgumentException::class.java) { Wire.AudioSeal.pair(ByteArray(1_024), payload) }
    }

    @Test fun testReportKeepsMicrophoneAndEchoResultsIndependent() {
        val microphoneOnly = TestCallReport().receivedMicrophone(queued = false)
        assertTrue(microphoneOnly.microphoneReceived)
        assertFalse(microphoneOnly.echoQueued)
        assertFalse(microphoneOnly.playbackConfirmed)
        val echoed = microphoneOnly.receivedMicrophone(queued = true)
        assertTrue(echoed.echoQueued)
        assertEquals(echoed, echoed.receivedMicrophone(queued = false))
    }

    @Test fun audioTestLogKeepsTimedCheckpointsWithoutRepeatingPerPacket() {
        var now = 500L
        val log = AudioTestLog("Receiver") { now }
        now += 25
        assertTrue(log.record(ReceiverTestStep.PACKET_AUTHENTICATED))
        now += 20
        assertEquals(45L, log.elapsedMs())
        assertFalse(log.record(ReceiverTestStep.PACKET_AUTHENTICATED))
        assertTrue(log.record(ReceiverTestStep.PCM_WRITTEN))
        assertEquals(
            "EasyCall audio test · Receiver\n+25 ms  First downlink packet authenticated\n+45 ms  Decoded PCM accepted by AudioTrack",
            log.text()
        )
    }

    @Test fun audioTestLogShowsGapsBetweenQueuedAndPlayedPacketSequences() {
        val log = AudioTestLog("Receiver") { 0L }
        log.count(AudioTestMetric.DOWNLINK_AUTHENTICATED, 3)
        for (sequence in 0L..2L) log.track(AudioTestMetric.DOWNLINK_AUTHENTICATED, sequence)
        log.track(AudioTestMetric.PCM_WRITTEN, 2)
        log.track(AudioTestMetric.PCM_WRITTEN, 0)
        log.track(AudioTestMetric.PCM_WRITTEN, 0)
        log.track(AudioTestMetric.PCM_WRITTEN, Long.MAX_VALUE)

        assertTrue(log.text().contains("DOWNLINK_AUTHENTICATED=3"))
        assertTrue(log.text().contains("DOWNLINK_AUTHENTICATED (3): 0-2"))
        assertTrue(log.text().contains("PCM_WRITTEN (2): 0, 2"))
    }

    @Test fun testAudioAlternatesAudibleTonesWithSilence() {
        val first = SimulationTone.frame(0)
        val second = SimulationTone.frame(SimulationTone.PERIOD_FRAMES)
        assertEquals(Opus.FRAME_BYTES, first.size)
        assertFalse(first.contentEquals(second))
        assertFalse(first.contentEquals(ByteArray(Opus.FRAME_BYTES)))
        assertArrayEquals(ByteArray(Opus.FRAME_BYTES), SimulationTone.frame(24))
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

    @Test fun opusUsesMoreBandwidthWhenBluetoothCanCarryIt() {
        assertEquals(21_066, Opus.bitRate(100 - Wire.AudioSeal.OVERHEAD))
        assertEquals(42_400, Opus.bitRate(180 - Wire.AudioSeal.OVERHEAD))
        assertEquals(56_000, Opus.bitRate(512 - Wire.AudioSeal.OVERHEAD))
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
