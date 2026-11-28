package app.ezcall.android

import android.annotation.SuppressLint
import android.app.Application
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Call audio component started as shell or root. It exchanges call audio with an authenticated ezcall relay on loopback.
 */
@SuppressLint("MissingPermission")
class CallAudioServer private constructor(private val token: ByteArray) {
    private val audio: AudioManager? by lazy(::audioManager)
    private val clients = AtomicInteger()
    private val handset = HandsetAudio()
    private var media: Media? = null
    @Volatile private var lastCheck = "none"

    private fun serve(socket: Socket) {
        var owner: Media? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 3_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val (type, hello) = AudioBridge.read(input)
            if (type != AudioBridge.HELLO || hello.size != 17) return
            val client = hello.copyOfRange(1, 17)
            val server = AudioBridge.nonce()
            val capabilities = if (canIntercept()) AudioBridge.CAN_INJECT else 0
            AudioBridge.write(output, AudioBridge.SERVER_HELLO, byteArrayOf(AudioBridge.VERSION.toByte(),
                capabilities.toByte()) + server + AudioBridge.serverProof(token, client, server))
            val (kind, proof) = AudioBridge.read(input)
            if (kind != AudioBridge.AUTH || !AudioBridge.matches(AudioBridge.clientProof(token, client, server), proof)) return
            socket.soTimeout = 0
            while (true) {
                val (message, payload) = AudioBridge.read(input)
                when (message) {
                    AudioBridge.START -> owner = start(output, payload.singleOrNull()?.toInt() == AudioBridge.START_NORMAL_MODE)
                    AudioBridge.STOP -> {
                        val preserveGrace = payload.singleOrNull()?.toInt() == AudioBridge.STOP_FOR_RECOVERY
                        require(payload.isEmpty() || preserveGrace)
                        owner?.let { stop(it, preserveGrace) }
                        owner = null
                    }
                    AudioBridge.RECEIVER_READY -> {
                        val ready = payload.singleOrNull()?.toInt()
                        require(ready == 0 || ready == 1 || ready == AudioBridge.RECEIVER_RECOVERING)
                        synchronized(this) {
                            if (owner === media) owner?.setReceiverReady(ready == 1, ready == AudioBridge.RECEIVER_RECOVERING)
                        }
                    }
                    AudioBridge.PCM -> owner?.play(payload)
                    AudioBridge.DTMF -> {
                        val digit = payload.singleOrNull()?.toInt()?.toChar()
                        if (digit != null && digit in AudioBridge.DTMF_DIGITS) owner?.queueDtmf(digit)
                    }
                    AudioBridge.DIAGNOSE -> AudioBridge.write(output, AudioBridge.DIAGNOSTICS, diagnostics())
                    AudioBridge.SHUTDOWN -> {
                        log("Shutdown requested")
                        synchronized(this) { media?.close() }
                        handset.restore(wait = true)
                        exitProcess(0)
                    }
                }
            }
        } catch (_: IOException) {
        } catch (_: IllegalArgumentException) {
        } finally {
            owner?.let(::stop)
            runCatching { socket.close() }
            clients.decrementAndGet()
        }
    }

    private fun start(output: DataOutputStream, normalMode: Boolean): Media? {
        val next = Media(output, allowRedirect = !normalMode)
        synchronized(this) {
            media?.close()
            media = null
            next.open()
            if (next.record == null || next.track == null) {
                next.close()
                AudioBridge.write(output, AudioBridge.ERROR, "Call audio is on the sending phone.".toByteArray())
                log("Two-way call audio route unavailable")
                return null
            }
            try {
                AudioBridge.write(output, AudioBridge.STARTED, byteArrayOf((AudioBridge.DOWNLINK or AudioBridge.UPLINK).toByte()))
            } catch (error: IOException) {
                next.close()
                throw error
            }
            media = next
            next.begin()
        }
        log("Two-way call audio opened (${if (next.allowRedirect) "automatic" else "normal call"} mode)")
        return next
    }

    private fun stop(owner: Media, preserveGrace: Boolean = false) {
        synchronized(this) {
            owner.close(preserveGrace)
            if (media === owner) media = null
        }
    }

    @SuppressLint("InlinedApi") // Redirection uses telephony devices opened only on Android 13 and later.
    private inner class Media(private val output: DataOutputStream, val allowRedirect: Boolean) {
        @Volatile private var running = true
        private val uplink = ArrayBlockingQueue<ByteArray>(AudioBridge.LIVE_QUEUE_FRAMES)
        private val dtmf = ArrayBlockingQueue<Char>(12)
        private val workers = mutableListOf<Thread>()
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        @Volatile private var receiverReady = false
        @Volatile private var uplinkGeneration = 0
        @Volatile private var failed = false
        @Volatile private var lastDownlinkAt = 0L
        @Volatile private var check: UplinkCheck? = null
        @Volatile private var monitor: Thread? = null
        @Volatile private var checkDone = false

        fun open() {
            if (!canIntercept()) return
            // OFFHOOK can precede Telecom entering cellular call mode.
            for (attempt in 0..10) {
                val mode = audio?.mode
                if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_CALL_REDIRECT) {
                    if (record == null) openTelephonyDownlink()
                    if (record != null && track == null) openUplink()
                    if (record != null && track != null) return
                }
                if (attempt < 10) Thread.sleep(150)
            }
        }

        fun begin() {
            record?.let { input -> workers += thread(name = "xcall-downlink") { capture(input) } }
            track?.let { sink -> workers += thread(name = "xcall-uplink") { inject(sink) } }
        }

        fun play(pcm: ByteArray) {
            if (pcm.isEmpty() || pcm.size % 2 != 0) return
            synchronized(this@CallAudioServer) {
                if (media !== this || !running || !receiverReady) return
                while (!uplink.offer(pcm)) uplink.poll()
            }
        }

        fun queueDtmf(digit: Char) {
            synchronized(this@CallAudioServer) {
                if (media === this && running && receiverReady) dtmf.offer(digit)
            }
        }

        fun setReceiverReady(ready: Boolean, recovering: Boolean) {
            if (!ready) return pauseRelay(recovering)
            val sink = track ?: return
            if (receiverReady || !running || failed) return
            runCatching {
                sink.play()
                // Prime the track with silence before any receiver microphone audio is queued.
                val silence = ByteArray(AudioBridge.FRAME_BYTES * 2)
                val primed = sink.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                check(primed >= 0) { "Uplink priming failed: $primed" }
                checkTelephonyOutput(sink)
                receiverReady = true
                log("Call audio relay active")
            }.onFailure { mediaFailed("Android couldn't relay call audio to the receiving phone.", it) }
            if (receiverReady) {
                handset.silence(this)
                startCheck()
            }
        }

        fun relaying() = running && receiverReady && !failed

        /** True while far-end audio still arrives and the receiver's voice still reaches the modem. */
        fun streamsHealthy(): Boolean {
            val input = record ?: return false
            val sink = track ?: return false
            val route = sink.routedDevice?.type
            return relaying() && input.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                input.activeRecordingConfiguration?.isClientSilenced != true &&
                SystemClock.elapsedRealtime() - lastDownlinkAt < 200 &&
                sink.playState == AudioTrack.PLAYSTATE_PLAYING &&
                (route == null || route == AudioDeviceInfo.TYPE_TELEPHONY)
        }

        fun close(preserveGrace: Boolean = false) {
            if (!running) return
            running = false
            pauseRelay(preserveGrace)
            runCatching { record?.stop() }
            workers.forEach { it.join(500) }
            monitor?.join(500)
            runCatching { record?.release() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }

        private fun pauseRelay(recovering: Boolean = false) {
            receiverReady = false
            uplinkGeneration++
            handset.restore(delayed = recovering)
            uplink.clear()
            dtmf.clear()
            runCatching {
                track?.pause()
                track?.flush()
            }
        }

        private fun mediaFailed(message: String, error: Throwable) {
            if (!running) return
            synchronized(this@CallAudioServer) {
                if (!running || failed) return
                failed = true
                pauseRelay()
            }
            log("$message: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            runCatching { AudioBridge.write(output, AudioBridge.ERROR, message.toByteArray()) }
        }

        private fun capture(input: AudioRecord) {
            val frame = ByteArray(AudioBridge.FRAME_BYTES)
            var checkCaptureAt = 0L
            var silencedSince: Long? = null
            val stats = AudioStats("Far-end audio", "captured")
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            try {
                while (running) {
                    var filled = 0
                    while (running && filled < frame.size) {
                        val count = input.read(frame, filled, frame.size - filled)
                        if (count < 0) throw IOException("Audio read failed: $count")
                        filled += count
                    }
                    lastDownlinkAt = SystemClock.elapsedRealtime()
                    if (!running || !receiverReady) {
                        silencedSince = null
                        stats.flush()
                        continue
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now >= checkCaptureAt) {
                        checkCaptureAt = now + 250
                        if (input.activeRecordingConfiguration?.isClientSilenced == true) {
                            val since = silencedSince ?: now
                            silencedSince = since
                            if (now - since >= 750) {
                                mediaFailed("Android stopped capturing call audio.", IllegalStateException("PSTN capture silenced"))
                            }
                        } else silencedSince = null
                    }
                    if (receiverReady) {
                        stats.add(frame)
                        AudioBridge.write(output, AudioBridge.PCM, frame)
                    }
                }
            } catch (error: Exception) {
                mediaFailed("Android stopped capturing call audio.", error)
            } finally {
                stats.flush()
            }
        }

        private fun inject(sink: AudioTrack) {
            var generation = uplinkGeneration
            var voice = AudioBridge.UplinkPcm()
            var tone: ByteArray? = null
            var toneOffset = 0
            val stats = AudioStats("Receiver audio", "received")
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            try {
                while (running) {
                    if (!receiverReady) {
                        stats.flush()
                        Thread.sleep(10)
                        continue
                    }
                    if (generation != uplinkGeneration) {
                        generation = uplinkGeneration
                        voice = AudioBridge.UplinkPcm()
                        tone = null
                    }
                    if (tone == null) {
                        tone = dtmf.poll()?.let(AudioBridge::dtmfPcm)
                        toneOffset = 0
                    }
                    val signal = tone
                    // The blocking track write paces the stream even while Bluetooth supplies no PCM.
                    val received = uplink.poll()
                    stats.add(received)
                    val pcm = voice.frame(received)
                    if (signal != null) {
                        AudioBridge.mixDtmf(pcm, signal, toneOffset)
                        toneOffset += pcm.size
                        if (toneOffset >= signal.size) tone = null
                    }
                    check?.injected(SystemClock.elapsedRealtime(), UplinkCheck.rms(pcm))
                    var offset = 0
                    while (running && receiverReady && generation == uplinkGeneration && offset < pcm.size) {
                        checkTelephonyOutput(sink)
                        val written = sink.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (!receiverReady || generation != uplinkGeneration) break
                        if (written <= 0) throw IOException("Call audio injection failed: $written")
                        offset += written
                    }
                }
            } catch (error: Exception) {
                mediaFailed("Android stopped receiving call audio.", error)
            } finally {
                stats.flush()
            }
        }

        /** Shows, every 5 s, whether audio flows through this component in one direction and how loud it is. */
        private inner class AudioStats(private val label: String, private val verb: String) {
            private var frames = 0
            private var voiced = 0
            private var gaps = 0
            private var peak = 0f
            private var sum = 0.0

            fun add(pcm: ByteArray?) {
                if (pcm == null) gaps++ else {
                    val level = UplinkCheck.level(UplinkCheck.rms(pcm))
                    frames++
                    sum += level
                    peak = maxOf(peak, level)
                    if (level >= 45f) voiced++
                }
                if (frames + gaps >= STATS_FRAMES) flush()
            }

            fun flush() {
                if (frames + gaps == 0) return
                val ms = AudioBridge.FRAME_SAMPLES * 1000L / AudioBridge.SAMPLE_RATE
                val average = if (frames == 0) 0 else (sum / frames).toInt()
                log("$label: ${frames * ms}ms $verb, ${gaps * ms}ms missing, ${voiced * ms}ms voiced, " +
                    "level=${average}dB peak=${peak.toInt()}dB, ${modeName()} mode, track=${track?.playState}")
                frames = 0
                voiced = 0
                gaps = 0
                peak = 0f
                sum = 0.0
            }
        }

        /**
         * Confirms in the live call that the receiver's voice reaches the modem. Only a match counts: some HALs tap the
         * uplink capture before injected audio is mixed in, so no match is no evidence of a fault.
         */
        private fun startCheck() {
            if (checkDone || monitor != null || !running) return
            val worker = thread(start = false, name = "xcall-uplink-check", isDaemon = true) {
                try {
                    watchUplink()
                } finally {
                    check = null
                    monitor = null
                }
            }
            monitor = worker
            worker.start()
        }

        private fun watchUplink() {
            // Let the handset redirect settle first, so the check measures the mode the call stays in.
            if (!settle()) return stoppedEarly("before uplink capture opened")
            log("Uplink check starting in ${modeName()} mode")
            val probe = openUplinkMonitor()
            if (probe == null) {
                finishCheck(AudioBridge.CHECK_UNKNOWN, "uplink capture unavailable")
                return
            }
            log("Uplink capture open: ${probe.sampleRate} Hz, route=${runCatching { probe.routedDevice?.type }.getOrNull()}")
            try {
                val frame = ByteArray(probe.sampleRate / 50 * 2)
                val current = UplinkCheck().also { check = it }
                val deadline = SystemClock.elapsedRealtime() + CHECK_TIMEOUT_MS
                var nextVerdict = SystemClock.elapsedRealtime() + 1_000
                var nextConflictCheck = 0L
                var nextProgress = SystemClock.elapsedRealtime() + PROGRESS_MS
                var lastAudioAt = SystemClock.elapsedRealtime()
                while (running && receiverReady && !failed) {
                    // Non-blocking, so a capture that never delivers can't hold this thread or the recording open.
                    val count = probe.read(frame, 0, frame.size, AudioRecord.READ_NON_BLOCKING)
                    if (count < 0) return finishCheck(AudioBridge.CHECK_UNKNOWN, "uplink capture read $count")
                    val now = SystemClock.elapsedRealtime()
                    if (count > 0) {
                        lastAudioAt = now
                        current.captured(now, UplinkCheck.rms(frame, count))
                    } else if (now - lastAudioAt > NO_CAPTURE_MS) {
                        return finishCheck(AudioBridge.CHECK_UNKNOWN,
                            "uplink capture delivered no audio for ${NO_CAPTURE_MS / 1000} s")
                    } else Thread.sleep(10)
                    if (now >= nextConflictCheck) {
                        nextConflictCheck = now + 100
                        // Never let the check take call audio away from the receiver.
                        if (record?.activeRecordingConfiguration?.isClientSilenced == true ||
                            probe.activeRecordingConfiguration?.isClientSilenced == true) {
                            return finishCheck(AudioBridge.CHECK_UNKNOWN, "uplink capture conflicts with call capture")
                        }
                    }
                    if (now < nextVerdict) continue
                    nextVerdict = now + 1_000
                    val verdict = current.evaluate(now)
                    if (verdict == UplinkCheck.Verdict.PENDING && now >= nextProgress) {
                        nextProgress = now + PROGRESS_MS
                        log("Uplink check pending: ${current.summary()}")
                    }
                    if (verdict == UplinkCheck.Verdict.REACHED) {
                        return finishCheck(AudioBridge.CHECK_REACHED, "receiver voice reached the call")
                    }
                    if (now > deadline) return finishCheck(AudioBridge.CHECK_UNKNOWN, "couldn't confirm from uplink capture")
                }
                stoppedEarly(when {
                    !running -> "call audio closed"
                    failed -> "call audio failed"
                    else -> "receiver paused audio"
                })
            } catch (error: Exception) {
                finishCheck(AudioBridge.CHECK_UNKNOWN, "uplink check failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            } finally {
                runCatching { probe.stop() }
                probe.release()
            }
        }

        private fun settle(): Boolean {
            val until = SystemClock.elapsedRealtime() + CHECK_SETTLE_MS
            while (running && receiverReady && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            return running && receiverReady
        }

        private fun modeName() = if (handset.isRedirected()) "redirect" else "in-call"

        /** Leaves the check undecided, so it resumes if the receiver comes back during this call. */
        private fun stoppedEarly(reason: String) {
            if (checkDone) return
            lastCheck = "stopped early, $reason (${modeName()} mode); ${check?.summary() ?: "no audio measured"}"
            log("Uplink check $lastCheck")
        }

        private fun finishCheck(result: Int, detail: String) {
            if (checkDone) return
            checkDone = true
            val redirect = handset.isRedirected()
            val mode = if (redirect) AudioBridge.MODE_REDIRECT else AudioBridge.MODE_IN_CALL
            lastCheck = "$detail (${modeName()} mode)" + (check?.let { "; ${it.summary()}" } ?: "")
            log("Uplink check: $lastCheck")
            runCatching { AudioBridge.write(output, AudioBridge.UPLINK_CHECK, byteArrayOf(result.toByte(), mode.toByte())) }
        }

        private fun openUplinkMonitor(): AudioRecord? {
            if (Build.VERSION.SDK_INT < 33) return null
            for (rate in intArrayOf(AudioBridge.SAMPLE_RATE, 16_000, 8_000)) {
                val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) {
                    log("Uplink capture $rate Hz: no buffer size ($minimum)")
                    continue
                }
                val format = AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                val probe = runCatching {
                    AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_UPLINK).setAudioFormat(format)
                        .setBufferSizeInBytes(maxOf(minimum, rate / 50 * 2 * 4)).setContext(AudioProcessContext.context)
                        .build()
                }.onFailure {
                    log("Uplink capture $rate Hz refused: ${it.javaClass.simpleName}: ${it.message.orEmpty()}")
                }.getOrNull() ?: continue
                val state = probe.state
                if (started(probe)) return probe
                log("Uplink capture $rate Hz didn't start (state=$state)")
            }
            return null
        }

        private fun checkTelephonyOutput(sink: AudioTrack) {
            // Some HALs only report the route after playback starts.
            sink.routedDevice?.let { check(it.type == AudioDeviceInfo.TYPE_TELEPHONY) { "Uplink left the modem route" } }
        }

        private fun downlinkFormat() = AudioFormat.Builder().setSampleRate(AudioBridge.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()

        private fun openTelephonyDownlink() {
            val intercepted = runCatching {
                hidden("getCallDownlinkExtractionAudioRecord", downlinkFormat()) as? AudioRecord
            }.getOrNull() ?: return
            if (started(intercepted)) record = intercepted
        }

        private fun openUplink() {
            val format = AudioFormat.Builder().setSampleRate(AudioBridge.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val candidate = runCatching { hidden("getCallUplinkInjectionAudioTrack", format) as? AudioTrack }.getOrNull() ?: return
            if (candidate.state == AudioTrack.STATE_INITIALIZED) {
                audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    ?.filter { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
                    ?.singleOrNull()
                    ?.let { candidate.setPreferredDevice(it) }
                runCatching {
                    candidate.setBufferSizeInFrames(AudioBridge.FRAME_SAMPLES * 3)
                    candidate.setStartThresholdInFrames(AudioBridge.FRAME_SAMPLES * 2)
                }
                track = candidate
                return
            }
            candidate.release()
        }

        private fun started(candidate: AudioRecord): Boolean {
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                runCatching { candidate.startRecording() }
                if (candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) return true
            }
            candidate.release()
            return false
        }
    }

    /**
     * Redirects the call away from the handset and mutes its playback independently, since some devices still
     * play call audio in redirect mode. Keep the handset quiet during a brief Bluetooth recovery.
     */
    @SuppressLint("InlinedApi")
    private inner class HandsetAudio {
        private val control = Executors.newSingleThreadScheduledExecutor()
        // Written only on the control thread.
        @Volatile private var redirected = false
        private var reapplied = 0
        private var savedVolume: Int? = null
        private var mutedByRelay = false
        private var micMutedByRelay = false
        private var watch: ScheduledFuture<*>? = null
        private var restoreTask: ScheduledFuture<*>? = null

        fun silence(owner: Media) {
            control.execute {
                if (!owner.relaying()) return@execute
                restoreTask?.cancel(false)
                restoreTask = null
                mutePlayback()
                if (!redirected && owner.allowRedirect) redirect(owner)
            }
        }

        fun isRedirected() = redirected

        fun restore(wait: Boolean = false, delayed: Boolean = false) {
            val task = control.submit(Runnable {
                if (!delayed) {
                    restoreNow()
                } else if (restoreTask == null && (redirected || mutedByRelay || micMutedByRelay || savedVolume != null)) {
                    restoreTask = control.schedule(::restoreNow, HANDSET_RECOVERY_MS, TimeUnit.MILLISECONDS)
                    log("Keeping handset playback quiet for Bluetooth recovery")
                }
            })
            if (wait) runCatching { task.get(1, TimeUnit.SECONDS) }
        }

        private fun restoreNow() {
            restoreTask?.cancel(false)
            restoreTask = null
            watch?.cancel(false)
            watch = null
            val manager = audio ?: return
            restoreMicrophone(manager)
            if (redirected) {
                redirected = false
                releaseMode(manager)
                log("Handset audio restored")
            }
            if (mutedByRelay) {
                runCatching {
                    manager.adjustStreamVolume(CALL_STREAM, AudioManager.ADJUST_UNMUTE, 0)
                    check(!manager.isStreamMute(CALL_STREAM)) { "Call stream still muted" }
                    mutedByRelay = false
                    log("Handset call playback unmuted")
                }.onFailure { log("Couldn't unmute handset call playback: ${it.message.orEmpty()}") }
            }
            val saved = savedVolume ?: return
            savedVolume = null
            runCatching {
                // Leave the volume alone if the user changed it during the call.
                if (manager.getStreamVolume(CALL_STREAM) == manager.getStreamMinVolume(CALL_STREAM)) {
                    manager.setStreamVolume(CALL_STREAM, saved, 0)
                    log("Handset call volume restored to $saved")
                }
            }.onFailure { log("Couldn't restore handset call volume: ${it.message.orEmpty()}") }
        }

        private fun mutePlayback() {
            val manager = audio ?: return
            runCatching {
                if (manager.isStreamMute(CALL_STREAM)) return
                manager.adjustStreamVolume(CALL_STREAM, AudioManager.ADJUST_MUTE, 0)
                if (manager.isStreamMute(CALL_STREAM)) {
                    mutedByRelay = true
                    log("Handset call playback muted")
                    return
                }
            }.onFailure { log("Couldn't mute handset call playback: ${it.message.orEmpty()}") }
            if (savedVolume == null) lowerVolume()
        }

        private fun muteMicrophone(manager: AudioManager) {
            if (!supportsHandsetMicMute(Build.MODEL, Build.VERSION.SDK_INT)) return
            runCatching {
                if (manager.isMicrophoneMute) return
                manager.isMicrophoneMute = true
                check(manager.isMicrophoneMute) { "Microphone mute did not take effect" }
                micMutedByRelay = true
                log("Handset microphone muted during call redirect")
            }.onFailure { log("Couldn't mute handset microphone: ${it.message.orEmpty()}") }
        }

        private fun restoreMicrophone(manager: AudioManager) {
            if (!micMutedByRelay) return
            runCatching {
                manager.isMicrophoneMute = false
                check(!manager.isMicrophoneMute) { "Microphone is still muted" }
                micMutedByRelay = false
                log("Handset microphone restored")
            }.onFailure { log("Couldn't restore handset microphone: ${it.message.orEmpty()}") }
        }

        private fun redirect(owner: Media) {
            val manager = audio ?: return
            if (Build.VERSION.SDK_INT < 33) return
            runCatching { manager.mode = AudioManager.MODE_CALL_REDIRECT }.onFailure {
                log("Call redirect mode refused: ${it.javaClass.simpleName}: ${it.message.orEmpty()}")
                return
            }
            // Give the audio policy time to reroute, then confirm both relayed directions survived.
            Thread.sleep(REDIRECT_SETTLE_MS)
            val mode = runCatching { manager.mode }.getOrDefault(-1)
            // A queued pause or stop will restore the mode according to its own deadline.
            val healthy = !owner.relaying() || owner.streamsHealthy()
            if (mode == AudioManager.MODE_CALL_REDIRECT && healthy) {
                redirected = true
                reapplied = 0
                if (owner.relaying()) muteMicrophone(manager)
                watch = control.scheduleWithFixedDelay(::keepRedirect, 1, 1, TimeUnit.SECONDS)
                log("Call redirect mode active")
                return
            }
            releaseMode(manager)
            log("Call redirect mode unusable (mode=$mode, streams=${if (healthy) "ok" else "disturbed"})")
        }

        private fun keepRedirect() {
            val manager = audio ?: return
            if (!redirected) return
            if (mutedByRelay) mutePlayback()
            val mode = runCatching { manager.mode }.getOrNull() ?: return
            if (mode == AudioManager.MODE_CALL_REDIRECT) {
                muteMicrophone(manager)
                return
            }
            if (reapplied >= MAX_REDIRECT_RETRIES) {
                log("Android keeps leaving call redirect mode (mode=$mode)")
                watch?.cancel(false)
                watch = null
                redirected = false
                restoreMicrophone(manager)
                releaseMode(manager)
                return
            }
            reapplied++
            log("Android left call redirect mode (mode=$mode); reapplying")
            runCatching { manager.mode = AudioManager.MODE_CALL_REDIRECT }
        }

        private fun lowerVolume() {
            val manager = audio ?: return
            runCatching {
                val current = manager.getStreamVolume(CALL_STREAM)
                val lowest = manager.getStreamMinVolume(CALL_STREAM)
                if (current > lowest) {
                    manager.setStreamVolume(CALL_STREAM, lowest, 0)
                    savedVolume = current
                }
                log("Handset call volume lowered ($current -> $lowest); its microphone stays on")
            }.onFailure { log("Couldn't lower handset call volume: ${it.message.orEmpty()}") }
        }

        // Withdraws this process's mode request, so Telecom's in-call mode applies again.
        private fun releaseMode(manager: AudioManager) {
            runCatching { manager.mode = AudioManager.MODE_NORMAL }
        }
    }

    // Android 13 system APIs route call audio through the HAL's telephony RX and TX devices when the phone has them.
    private fun canIntercept(): Boolean = Build.VERSION.SDK_INT >= 33 && runCatching {
        AudioManager::class.java.getMethod("isPstnCallAudioInterceptable").invoke(audio) == true
    }.getOrDefault(false)

    private fun diagnostics(): ByteArray {
        val manager = audio
        val telephony = listOf(AudioManager.GET_DEVICES_OUTPUTS to "tx", AudioManager.GET_DEVICES_INPUTS to "rx")
            .joinToString(" ") { (flag, name) ->
                val present = runCatching {
                    manager?.getDevices(flag)?.any { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
                }.getOrNull()
                "$name=$present"
            }
        val soc = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} (${Build.HARDWARE})"
        val header = buildString {
            appendLine("Component: uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT} android=${Build.VERSION.RELEASE}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) soc=$soc")
            appendLine("Build: ${Build.FINGERPRINT}")
            appendLine("Interceptable: ${canIntercept()} mode=${runCatching { manager?.mode }.getOrNull()} telephony $telephony")
            appendLine("Redirect: active=${handset.isRedirected()} allowed=${synchronized(this@CallAudioServer) { media?.allowRedirect }}")
            appendLine("Last uplink check: $lastCheck")
            appendLine("Log:")
        }
        return fitText(header, synchronized(history) { history.toList() })
    }

    private fun hidden(name: String, format: AudioFormat): Any? =
        AudioManager::class.java.getMethod(name, AudioFormat::class.java).invoke(audio, format)

    private fun audioManager(): AudioManager? = runCatching {
        AudioManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            .newInstance(AudioProcessContext.context)
    }.recoverCatching { AudioProcessContext.context.getSystemService(AudioManager::class.java) }.getOrNull()

    companion object {
        const val TOKEN_ENV = "XCALL_AUDIO_TOKEN"
        const val PROCESS_NAME = "ezcall_audio"
        private const val CALL_STREAM = AudioManager.STREAM_VOICE_CALL
        private const val REDIRECT_SETTLE_MS = 400L
        private const val HANDSET_RECOVERY_MS = 5_000L
        private const val MAX_REDIRECT_RETRIES = 2
        private const val CHECK_SETTLE_MS = 1_500L
        private const val CHECK_TIMEOUT_MS = 45_000L
        private const val PROGRESS_MS = 5_000L
        private const val NO_CAPTURE_MS = 3_000L
        private const val STATS_FRAMES = 250 // 5 s
        private const val LOG_LINES = 150
        private val history = ArrayDeque<String>()

        // shortcut: modem mute is verified on this model and Android version, expand after testing other builds.
        internal fun supportsHandsetMicMute(model: String, sdk: Int) = model == "SM-A175F" && sdk == 37

        @JvmStatic
        fun main(args: Array<String>) {
            val port = args.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1024..65535 }
            val token = System.getenv(TOKEN_ENV)?.let(::decodeHex)
            if (port == null || token?.size != 32) {
                log("Missing port or token")
                exitProcess(2)
            }
            Looper.prepareMainLooper()
            AudioProcessContext.install()
            val server = CallAudioServer(token)
            if (Process.myUid() == 0 && !server.canIntercept()) exitProcess(4)
            val listener = try {
                ServerSocket(port, 4, InetAddress.getLoopbackAddress())
            } catch (_: IOException) {
                log("Port $port is busy")
                exitProcess(3)
            }
            log("Ready, sdk=${Build.VERSION.SDK_INT}, intercept=${server.canIntercept()}")
            while (true) {
                val socket = try {
                    listener.accept()
                } catch (_: IOException) {
                    continue
                }
                if (server.clients.incrementAndGet() > 4) {
                    server.clients.decrementAndGet()
                    runCatching { socket.close() }
                    continue
                }
                thread(name = "xcall-audio-client") { server.serve(socket) }
            }
        }

        internal fun decodeHex(value: String): ByteArray? {
            if (value.length % 2 != 0) return null
            return ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
            }
        }

        private fun log(message: String) {
            System.err.println("$PROCESS_NAME: $message")
            synchronized(history) {
                history.addLast("${SystemClock.elapsedRealtime() / 1000}s $message")
                while (history.size > LOG_LINES) history.removeFirst()
            }
        }

        internal fun fitText(header: String, lines: List<String>, limit: Int = AudioBridge.MAX_TEXT): ByteArray {
            var kept = lines
            while (true) {
                val text = (header + kept.joinToString(separator = System.lineSeparator())).toByteArray(Charsets.UTF_8)
                if (text.size <= limit) return text
                if (kept.isEmpty()) return text.copyOf(limit)
                kept = kept.drop(maxOf(1, kept.size / 8))
            }
        }
    }
}

// app_process starts without an application. Audio clients still need attribution for the process identity.
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "StaticFieldLeak")
private object AudioProcessContext {
    private val uid = Process.myUid()
    private val identityPackage = if (uid == 0) "root" else "com.android.shell"
    lateinit var context: Context
        private set

    fun install() {
        val threadClass = Class.forName("android.app.ActivityThread")
        val activityThread = threadClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        threadClass.field("sCurrentActivityThread").set(null, activityThread)
        threadClass.field("mSystemThread").setBoolean(activityThread, true)
        runCatching {
            val controller = Class.forName("android.app.ConfigurationController")
                .getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"))
                .apply { isAccessible = true }.newInstance(activityThread)
            threadClass.field("mConfigurationController").set(activityThread, controller)
        }
        runCatching {
            val bindingClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val binding = bindingClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            bindingClass.field("appInfo").set(binding, ApplicationInfo().apply { packageName = identityPackage })
            threadClass.field("mBoundApplication").set(activityThread, binding)
        }
        val system = threadClass.getDeclaredMethod("getSystemContext").invoke(activityThread) as Context
        context = ProcessIdentity(system)
        runCatching {
            val application = Application()
            ContextWrapper::class.java.field("mBase").set(application, context)
            threadClass.field("mInitialApplication").set(activityThread, application)
        }
    }

    private fun Class<*>.field(name: String) = getDeclaredField(name).apply { isAccessible = true }

    private class ProcessIdentity(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = identityPackage
        override fun getOpPackageName() = identityPackage
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(uid).setPackageName(identityPackage).build()
        override fun getApplicationContext(): Context = this
    }
}
