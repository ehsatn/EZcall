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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Call audio component. ezcall starts it through this phone's own Wireless debugging connection, so it runs as the
 * shell user, which may capture call audio. It only exchanges call audio with an authenticated ezcall relay on the
 * loopback interface and accepts no commands beyond start, stop and shutdown.
 */
@SuppressLint("MissingPermission")
class CallAudioServer private constructor(private val token: ByteArray) {
    private val audio: AudioManager? by lazy(::shellAudioManager)
    private val clients = AtomicInteger()
    private var media: Media? = null

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
                    AudioBridge.START -> owner = start(output)
                    AudioBridge.STOP -> owner = owner?.let { stop(it); null }
                    AudioBridge.ROUTE -> {
                        val route = payload.singleOrNull()?.toInt()
                        require(route == 0 || route == 1)
                        synchronized(this) {
                            if (owner === media) owner?.setPhoneReady(route == 1)
                        }
                    }
                    AudioBridge.PCM -> owner?.play(payload)
                    AudioBridge.SHUTDOWN -> {
                        log("Shutdown requested")
                        synchronized(this) { media?.close() }
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

    private fun start(output: DataOutputStream): Media? {
        val next = Media(output)
        synchronized(this) {
            media?.close()
            media = null
            next.open()
            if (next.record == null && next.track == null) {
                next.close()
                AudioBridge.write(output, AudioBridge.ERROR, "This phone did not allow call audio capture.".toByteArray())
                log("No call audio route opened")
                return null
            }
            val active = (if (next.record != null) AudioBridge.DOWNLINK else 0) or
                (if (next.track != null) AudioBridge.UPLINK else 0)
            try {
                AudioBridge.write(output, AudioBridge.STARTED, byteArrayOf(active.toByte()) + next.source.toByteArray())
            } catch (error: IOException) {
                next.close()
                throw error
            }
            media = next
            next.begin()
        }
        log("Call audio started, source=${next.source}, uplink=${next.track != null}")
        return next
    }

    private fun stop(owner: Media) {
        synchronized(this) {
            owner.close()
            if (media === owner) media = null
        }
    }

    private inner class Media(private val output: DataOutputStream) {
        @Volatile private var running = true
        private val uplink = ArrayBlockingQueue<ByteArray>(AudioBridge.LIVE_QUEUE_FRAMES)
        private val workers = mutableListOf<Thread>()
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var source = "none"
        private var phoneReady = false
        private var redirectRequested = false
        private var failed = false

        fun open() {
            val canIntercept = canIntercept()
            // OFFHOOK can precede Telecom entering cellular call mode.
            for (attempt in 0..10) {
                if (canIntercept && audio?.mode == AudioManager.MODE_IN_CALL) {
                    if (record == null) openTelephonyDownlink()
                    if (track == null) openUplink()
                    if (record != null && track != null) return
                }
                if ((!canIntercept || attempt == 10) && record == null) openFallbackDownlink()
                if (!canIntercept && record != null) return
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
                if (media !== this || !running || failed || track == null) return
                while (!uplink.offer(pcm)) uplink.poll()
                if (phoneReady) redirect(true)
            }
        }

        fun setPhoneReady(ready: Boolean) {
            phoneReady = ready
            if (!ready) {
                uplink.clear()
                redirect(false)
            }
        }

        fun close() {
            setPhoneReady(false)
            if (!running) return
            running = false
            runCatching { record?.stop() }
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            workers.forEach { it.join(500) }
            runCatching { record?.release() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }

        private fun redirect(enabled: Boolean) {
            val manager = audio ?: return
            if (!enabled) {
                if (!redirectRequested) return
                // MODE_NORMAL releases this process's mode request so Telecom restores the handset.
                runCatching { manager.mode = AudioManager.MODE_NORMAL }
                    .onSuccess {
                        redirectRequested = false
                        log("Handset call audio restored")
                    }
                    .onFailure { log("Handset call audio restore failed: ${it.javaClass.simpleName}") }
                return
            }
            if (redirectRequested || source != "telephony" || track == null || !running || failed ||
                manager.mode != AudioManager.MODE_IN_CALL) return
            // The call redirect route replaces both local call devices without muting the injected audio.
            runCatching { manager.mode = AudioManager.MODE_CALL_REDIRECT }
                .onSuccess {
                    redirectRequested = true
                    log("Requested call audio redirection to iPhone")
                }
                .onFailure { log("Call audio redirection failed: ${it.javaClass.simpleName}") }
        }

        private fun mediaFailed(message: String, error: Exception) {
            synchronized(this@CallAudioServer) {
                if (!running || failed) return
                failed = true
                redirect(false)
            }
            log("$message: ${error.javaClass.simpleName}")
            runCatching { AudioBridge.write(output, AudioBridge.ERROR, message.toByteArray()) }
        }

        private fun capture(input: AudioRecord) {
            val frame = ByteArray(AudioBridge.FRAME_BYTES)
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            try {
                while (running) {
                    var filled = 0
                    while (running && filled < frame.size) {
                        val count = input.read(frame, filled, frame.size - filled)
                        if (count < 0) throw IOException("Audio read failed: $count")
                        filled += count
                    }
                    if (running) AudioBridge.write(output, AudioBridge.PCM, frame.copyOf())
                }
            } catch (error: Exception) {
                mediaFailed("Android stopped capturing call audio.", error)
            }
        }

        private fun inject(sink: AudioTrack) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            try {
                while (running) {
                    val pcm = uplink.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    var offset = 0
                    while (running && offset < pcm.size) {
                        val written = sink.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) throw IOException("Call audio injection failed: $written")
                        offset += written
                    }
                }
            } catch (error: Exception) {
                mediaFailed("Android stopped receiving iPhone call audio.", error)
            }
        }

        private fun downlinkFormat() = AudioFormat.Builder().setSampleRate(AudioBridge.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()

        private fun openTelephonyDownlink() {
            val intercepted = runCatching {
                hidden("getCallDownlinkExtractionAudioRecord", downlinkFormat()) as? AudioRecord
            }.getOrNull() ?: return
            if (started(intercepted)) {
                record = intercepted
                source = "telephony"
            }
        }

        private fun openFallbackDownlink() {
            val format = downlinkFormat()
            val size = maxOf(AudioRecord.getMinBufferSize(AudioBridge.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT), AudioBridge.FRAME_BYTES * AudioBridge.LIVE_QUEUE_FRAMES)
            // The far-end-only source is preferred. The mixed call source also carries the receiving phone's own voice back.
            for ((input, name) in listOf(MediaRecorder.AudioSource.VOICE_DOWNLINK to "downlink",
                    MediaRecorder.AudioSource.VOICE_CALL to "call")) {
                val candidate = runCatching {
                    AudioRecord.Builder().setContext(ShellContext.context).setAudioSource(input)
                        .setAudioFormat(format).setBufferSizeInBytes(size).build()
                }.getOrNull() ?: continue
                if (started(candidate)) {
                    record = candidate
                    source = name
                    return
                }
            }
        }

        private fun openUplink() {
            val format = AudioFormat.Builder().setSampleRate(AudioBridge.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val candidate = runCatching { hidden("getCallUplinkInjectionAudioTrack", format) as? AudioTrack }.getOrNull() ?: return
            if (candidate.state == AudioTrack.STATE_INITIALIZED) {
                // Some audio policies otherwise route the call-assistant track to the handset earpiece.
                audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
                    ?.let { candidate.setPreferredDevice(it) }
                runCatching {
                    candidate.setBufferSizeInFrames(AudioBridge.FRAME_SAMPLES * AudioBridge.LIVE_QUEUE_FRAMES)
                    candidate.setStartThresholdInFrames(AudioBridge.FRAME_SAMPLES)
                }
                if (runCatching { candidate.play() }.isSuccess) {
                    track = candidate
                    return
                }
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

    // Android 13 system APIs route call audio through the HAL's telephony RX and TX devices when the phone has them.
    private fun canIntercept(): Boolean = Build.VERSION.SDK_INT >= 33 && runCatching {
        AudioManager::class.java.getMethod("isPstnCallAudioInterceptable").invoke(audio) == true
    }.getOrDefault(false)

    private fun hidden(name: String, format: AudioFormat): Any? =
        AudioManager::class.java.getMethod(name, AudioFormat::class.java).invoke(audio, format)

    private fun shellAudioManager(): AudioManager? = runCatching {
        AudioManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            .newInstance(ShellContext.context)
    }.recoverCatching { ShellContext.context.getSystemService(AudioManager::class.java) }.getOrNull()

    companion object {
        const val TOKEN_ENV = "XCALL_AUDIO_TOKEN"
        const val PROCESS_NAME = "ezcall_audio"

        @JvmStatic
        fun main(args: Array<String>) {
            val port = args.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1024..65535 }
            val token = System.getenv(TOKEN_ENV)?.let(::decodeHex)
            if (port == null || token?.size != 32) {
                log("Missing port or token")
                exitProcess(2)
            }
            Looper.prepareMainLooper()
            ShellContext.install()
            val listener = try {
                ServerSocket(port, 4, InetAddress.getLoopbackAddress())
            } catch (_: IOException) {
                log("Port $port is busy")
                exitProcess(3)
            }
            val server = CallAudioServer(token)
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

        private fun log(message: String) = System.err.println("$PROCESS_NAME: $message")
    }
}

// app_process starts without an application. Audio clients need a context that identifies the shell package.
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "StaticFieldLeak")
private object ShellContext {
    private const val SHELL_PACKAGE = "com.android.shell"
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
            bindingClass.field("appInfo").set(binding, ApplicationInfo().apply { packageName = SHELL_PACKAGE })
            threadClass.field("mBoundApplication").set(activityThread, binding)
        }
        val system = threadClass.getDeclaredMethod("getSystemContext").invoke(activityThread) as Context
        context = ShellIdentity(system)
        runCatching {
            val application = Application()
            ContextWrapper::class.java.field("mBase").set(application, context)
            threadClass.field("mInitialApplication").set(activityThread, application)
        }
    }

    private fun Class<*>.field(name: String) = getDeclaredField(name).apply { isAccessible = true }

    private class ShellIdentity(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL_PACKAGE).build()
        override fun getApplicationContext(): Context = this
    }
}
