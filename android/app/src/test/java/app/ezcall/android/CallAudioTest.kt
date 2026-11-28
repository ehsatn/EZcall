package app.ezcall.android

import android.bluetooth.BluetoothDevice
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class CallAudioTest {
    @Test fun automaticModeKeepsNormalOnlyWhenItAloneHasPositiveEvidence() {
        assertFalse(UplinkHealth.prefersNormal(redirectConfirmed = false, normalConfirmed = false))
        assertTrue(UplinkHealth.prefersNormal(redirectConfirmed = false, normalConfirmed = true))
        assertFalse(UplinkHealth.prefersNormal(redirectConfirmed = true, normalConfirmed = false))
        assertFalse(UplinkHealth.prefersNormal(redirectConfirmed = true, normalConfirmed = true))
    }

    @Test fun onlyCarriedLinksCanReuseSavedMtu() {
        assertEquals(23, initialLinkMtu(negotiated = null, saved = 517, carried = false))
        assertEquals(517, initialLinkMtu(negotiated = null, saved = 517, carried = true))
        assertEquals(185, initialLinkMtu(negotiated = 185, saved = 517, carried = true))
        assertEquals(185, initialLinkMtu(negotiated = 185, saved = 517, carried = false))
    }

    @Test fun onlyAnEndedBleLinkInvalidatesTheMtu() {
        assertTrue(leLinkDisconnected(BluetoothDevice.TRANSPORT_LE) { true })
        assertFalse(leLinkDisconnected(BluetoothDevice.TRANSPORT_BREDR) { false })
        assertFalse(leLinkDisconnected(BluetoothDevice.TRANSPORT_AUTO) { true })
        assertTrue(leLinkDisconnected(BluetoothDevice.TRANSPORT_AUTO) { false })
    }

    @Test fun failedSetupCanRetryOnceOnlyForTheActiveRelayedCall() {
        val call = RelayService.Call("first", "", 0, "active")
        assertTrue(call.canRetryAudio("first", "first", null, null))
        assertFalse(call.canRetryAudio("first", "first", null, "first"))
        assertFalse(call.canRetryAudio("first", "first", "first", null))
        assertFalse(call.canRetryAudio("first", null, null, null))
        assertFalse(call.canRetryAudio("previous", "first", null, null))
        assertFalse(call.copy(state = "ended").canRetryAudio("first", "first", null, null))
    }

    private val key = hex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")
    private val payload = hex("7863616c6c206f707573206672616d65")
    private val first = hex("05000000001eefac86eb7c9242798ae4954850fa8b7c654ca9f52c95227d93f866322808df")
    private val second = hex("0500000001a39088d16674cc65c144b33e7e4518ac09ac646485aad0fad543b9cd3b689a86")
    private val fromIphone = hex("05000000005e8e261bfd00994bd48e607b615f62fc17c9442abaf25a3752bf6a363eafc783")

    private fun android() = Wire.AudioSeal(key, Wire.AudioSeal.ANDROID_TO_IPHONE, Wire.AudioSeal.IPHONE_TO_ANDROID)
    private fun iphone() = Wire.AudioSeal(key, Wire.AudioSeal.IPHONE_TO_ANDROID, Wire.AudioSeal.ANDROID_TO_IPHONE)

    @Test fun handsetMicrophoneMuteIsLimitedToTheTestedSamsungAndAndroidVersion() {
        assertTrue(CallAudioServer.supportsHandsetMicMute("SM-A175F", 37))
        assertFalse(CallAudioServer.supportsHandsetMicMute("SM-A175F", 36))
        assertFalse(CallAudioServer.supportsHandsetMicMute("SM-A176B", 37))
    }

    @Test fun audioPacketsMatchTheSharedFixture() {
        val sender = android()
        assertArrayEquals(first, sender.seal(payload))
        assertArrayEquals(second, sender.seal(payload))
        assertArrayEquals(payload, android().open(fromIphone))
    }

    @Test fun dtmfSixHasTheExpectedFrequenciesLevelAndInterDigitPause() {
        val pcm = AudioBridge.dtmfPcm('6')
        assertEquals(AudioBridge.FRAME_BYTES * 12, pcm.size)
        val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val toneSamples = AudioBridge.FRAME_SAMPLES * 8
        val peak = (0 until toneSamples).maxOf { abs(samples[it].toInt()) }
        assertTrue("DTMF peak was $peak", peak in 3_500..3_605)
        val bins = listOf(697, 770, 852, 941, 1209, 1336, 1477)
        val peaks = bins.sortedByDescending { frequency ->
            var real = 0.0
            var imaginary = 0.0
            for (index in 0 until toneSamples) {
                val phase = 2 * PI * frequency * index / AudioBridge.SAMPLE_RATE
                real += samples[index] * cos(phase)
                imaginary += samples[index] * sin(phase)
            }
            real * real + imaginary * imaginary
        }
        assertEquals(setOf(770, 1477), peaks.take(2).toSet())
        assertTrue((toneSamples until samples.limit()).all { samples[it] == 0.toShort() })
        assertThrows(IllegalArgumentException::class.java) { AudioBridge.dtmfPcm('A') }
    }

    @Test fun dtmfMixPreservesMicrophoneFramesAndKeepsHeadroom() {
        val tone = AudioBridge.dtmfPcm('6')
        val mixed = ByteArray(tone.size)
        val voice = ByteBuffer.wrap(mixed).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (index in 0 until voice.limit()) {
            voice.put(index, if (index % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE)
        }
        val original = mixed.copyOf()
        for (offset in mixed.indices step AudioBridge.FRAME_BYTES) {
            val end = offset + AudioBridge.FRAME_BYTES
            val silentFrame = ByteArray(AudioBridge.FRAME_BYTES)
            AudioBridge.mixDtmf(silentFrame, tone, offset)
            assertArrayEquals(tone.copyOfRange(offset, end), silentFrame)
            val voiceFrame = mixed.copyOfRange(offset, end)
            AudioBridge.mixDtmf(voiceFrame, tone, offset)
            voiceFrame.copyInto(mixed, offset)
        }
        assertEquals(Short.MAX_VALUE, voice[0])
        assertEquals(Short.MIN_VALUE, voice[AudioBridge.FRAME_SAMPLES * 8 - 1])
        assertTrue((0 until voice.limit()).all { (voice[it] >= 0) == (it % 2 == 0) })
        assertFalse(original.contentEquals(mixed))
        val pause = AudioBridge.FRAME_BYTES * 8
        assertArrayEquals(original.copyOfRange(pause, original.size), mixed.copyOfRange(pause, mixed.size))
    }

    @Test fun uplinkFillsMissingFramesAndFadesVoiceBackIn() {
        fun voicedFrame() = ByteArray(AudioBridge.FRAME_BYTES).also { pcm ->
            val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until samples.limit()) samples.put(i, -9_600)
        }
        fun sample(pcm: ByteArray, index: Int) =
            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).getShort(index * 2).toInt()

        val uplink = AudioBridge.UplinkPcm()
        assertArrayEquals(voicedFrame(), uplink.frame(voicedFrame()))
        val gap = uplink.frame(null)
        assertEquals(AudioBridge.FRAME_BYTES, gap.size)
        assertTrue(sample(gap, 0) in -9_599..-1)
        assertEquals(0, sample(gap, AudioBridge.FRAME_SAMPLES - 1))
        assertTrue(uplink.frame(null).all { it == 0.toByte() })
        val resumed = uplink.frame(voicedFrame())
        assertTrue(sample(resumed, 0) in -9_599..-1)
        assertEquals(-9_600, sample(resumed, AudioBridge.FRAME_SAMPLES - 1))
        assertArrayEquals(voicedFrame(), uplink.frame(voicedFrame()))
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
