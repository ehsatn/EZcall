package app.ezcall.android

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Relay side of live call audio. The shell-user component captures the far end of the SIM call and injects the
 * receiving phone microphone into it. This class encodes and decodes Opus and seals each packet for the BLE audio channel.
 */
internal class CallAudio(private val context: Context, private val events: Events) {
    interface Events {
        fun onAudioStatus(status: Status)
        fun onAudioStarted(callId: String, key: ByteArray, uplink: Boolean)
        fun onAudioStopped(callId: String, reason: String?)
        fun onAudioPacket(packet: ByteArray)
    }

    enum class Status(val wire: String) { NONE("none"), LISTEN("listen"), TWO_WAY("two_way") }

    private val control = Executors.newSingleThreadExecutor()
    private val decoding = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(AudioBridge.LIVE_QUEUE_FRAMES), ThreadPoolExecutor.DiscardOldestPolicy())
    @Volatile private var link: Link? = null
    @Volatile private var stream: Stream? = null
    @Volatile var status = Status.NONE
        private set

    private class Stream(val callId: String) {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val seal = Wire.AudioSeal(key, Wire.AudioSeal.ANDROID_TO_IPHONE, Wire.AudioSeal.IPHONE_TO_ANDROID)
        var encoder: Opus.Encoder? = null
        var decoder: Opus.Decoder? = null
        var open = true
    }

    fun connect(launch: Boolean) = post { ensureLink(launch) }

    fun begin(callId: String) = post {
        endStream(null)
        val current = ensureLink(launch = true)
        if (current == null) {
            events.onAudioStopped(callId, "Call audio isn't running on Android. Set it up in ezcall on Android.")
            return@post
        }
        val next = Stream(callId)
        stream = next
        try {
            AudioBridge.write(current.output, AudioBridge.START)
        } catch (_: IOException) {
            drop(current)
        }
    }

    fun end() = post { endStream(null) }

    fun usePhoneAudioRedirect(enabled: Boolean) = post {
        val current = link ?: return@post
        if (stream == null) return@post
        try {
            AudioBridge.write(current.output, AudioBridge.ROUTE, byteArrayOf(if (enabled) 1 else 0))
        } catch (_: IOException) {
            drop(current)
        }
    }

    fun receive(packet: ByteArray) {
        val current = stream ?: return
        decoding.execute { decode(current, packet) }
    }

    fun close() {
        post {
            endStream(null)
            link?.let(::drop)
        }
        control.shutdown()
        decoding.shutdown()
    }

    private fun ensureLink(launch: Boolean): Link? {
        link?.let { if (!it.socket.isClosed) return it }
        val opened = if (launch && Setup.enabled(context)) {
            runCatching { Setup.launch(context) }.getOrNull()
        } else Setup.open(context)
        link = opened
        setStatus(opened?.status ?: Status.NONE)
        if (opened != null) thread(name = "xcall-audio-link", isDaemon = true) { read(opened) }
        return opened
    }

    private fun read(current: Link) {
        try {
            while (true) {
                val (type, payload) = AudioBridge.read(current.input)
                when (type) {
                    AudioBridge.STARTED -> post { started(payload) }
                    AudioBridge.PCM -> encode(payload)
                    AudioBridge.ERROR -> post { endStream(String(payload, Charsets.UTF_8).take(160)) }
                }
            }
        } catch (_: Exception) {
        } finally {
            post {
                if (link === current) drop(current)
            }
        }
    }

    private fun started(payload: ByteArray) {
        val current = stream ?: return
        val active = payload.firstOrNull()?.toInt() ?: 0
        synchronized(current) {
            if (active and AudioBridge.DOWNLINK != 0) current.encoder = runCatching { Opus.Encoder() }.getOrNull()
            if (active and AudioBridge.UPLINK != 0) current.decoder = runCatching { Opus.Decoder() }.getOrNull()
        }
        if (current.encoder == null && current.decoder == null) {
            endStream("Android couldn't start the Opus codec.")
            return
        }
        events.onAudioStarted(current.callId, current.key.copyOf(), current.decoder != null)
    }

    private fun encode(pcm: ByteArray) {
        val current = stream ?: return
        val packets = synchronized(current) {
            if (!current.open) return
            runCatching { current.encoder?.encode(pcm) }.getOrNull()
        } ?: return
        for (packet in packets) {
            if (packet.size <= MAX_OPUS) events.onAudioPacket(current.seal.seal(packet))
        }
    }

    private fun decode(current: Stream, packet: ByteArray) {
        val payload = current.seal.open(packet) ?: return
        val pcm = synchronized(current) {
            if (!current.open) return
            runCatching { current.decoder?.decode(payload) }.getOrNull()
        } ?: return
        val output = link?.output ?: return
        synchronized(output) {
            if (stream === current) runCatching { AudioBridge.write(output, AudioBridge.PCM, pcm) }
        }
    }

    private fun endStream(reason: String?) {
        val current = stream ?: return
        stream = null
        synchronized(current) {
            current.open = false
            current.encoder?.release()
            current.decoder?.release()
            current.encoder = null
            current.decoder = null
        }
        link?.let { runCatching { AudioBridge.write(it.output, AudioBridge.STOP) } }
        events.onAudioStopped(current.callId, reason)
    }

    private fun drop(current: Link) {
        runCatching { current.socket.close() }
        if (link === current) {
            link = null
            endStream("Call audio stopped on Android.")
            setStatus(Status.NONE)
        }
    }

    private fun setStatus(next: Status) {
        if (status == next) return
        status = next
        events.onAudioStatus(next)
    }

    private fun post(task: () -> Unit) {
        try {
            control.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }

    class Link(val socket: Socket, val input: DataInputStream, val output: DataOutputStream, val status: Status)

    // Starting, finding and authenticating the shell-user component.
    object Setup {
        private const val PREFS = "call_audio"

        fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)

        fun disable(context: Context) {
            prefs(context).edit().putBoolean("enabled", false).apply()
        }

        /** Starts or restarts the component for the setup screen and reports what this phone allows. */
        fun start(context: Context, connectPort: Int? = null): Status {
            val probe = launch(context, connectPort)
            runCatching { probe.socket.close() }
            return probe.status
        }

        /** Starts or restarts the component over Wireless debugging and waits for it to answer. */
        @Synchronized
        fun launch(context: Context, connectPort: Int? = null): Link {
            open(context)?.let {
                if (!enabled(context)) prefs(context).edit().putBoolean("enabled", true).apply()
                return it
            }
            val port = credentials(context).first
            val token = ByteArray(32).also(SecureRandom()::nextBytes)
            val apk = context.applicationInfo.sourceDir
            require(apk.matches(Regex("[A-Za-z0-9/._=~+-]+")))
            val name = CallAudioServer.PROCESS_NAME
            // Give app_process time to detach before Wireless debugging closes the shell.
            LocalAdb.shell(context, "trap '' HUP; pkill -x $name; ${CallAudioServer.TOKEN_ENV}=${hex(token)} CLASSPATH='$apk' " +
                "setsid app_process /system/bin --nice-name=$name ${CallAudioServer::class.java.name} $port " +
                "</dev/null >/data/local/tmp/$name.log 2>&1 & sleep 0.4", connectPort)
            // Store the new token only once adbd has it, so a refused launch leaves the running component reachable.
            check(prefs(context).edit().putString("token", hex(token)).commit())
            repeat(24) {
                Thread.sleep(250)
                open(context)?.let {
                    prefs(context).edit().putBoolean("enabled", true).apply()
                    return it
                }
            }
            throw ComponentNotReady()
        }

        class ComponentNotReady : IOException("The call audio component didn't start")

        /** Opens an authenticated loopback link, or returns null when no current component is running. */
        fun open(context: Context): Link? {
            val (port, token) = credentials(context)
            val socket = Socket()
            return try {
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                socket.tcpNoDelay = true
                socket.soTimeout = 3_000
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val client = AudioBridge.nonce()
                AudioBridge.write(output, AudioBridge.HELLO, byteArrayOf(AudioBridge.VERSION.toByte()) + client)
                val (type, hello) = AudioBridge.read(input)
                check(type == AudioBridge.SERVER_HELLO && hello.size == 50)
                val server = hello.copyOfRange(2, 18)
                check(AudioBridge.matches(AudioBridge.serverProof(token, client, server), hello.copyOfRange(18, 50)))
                AudioBridge.write(output, AudioBridge.AUTH, AudioBridge.clientProof(token, client, server))
                if (hello[0].toInt() != AudioBridge.VERSION) {
                    // An older component from a previous app version. Relaunching replaces it.
                    AudioBridge.write(output, AudioBridge.SHUTDOWN)
                    socket.close()
                    return null
                }
                socket.soTimeout = 0
                val status = if (hello[1].toInt() and AudioBridge.CAN_INJECT != 0) Status.TWO_WAY else Status.LISTEN
                Link(socket, input, output, status)
            } catch (_: Exception) {
                runCatching { socket.close() }
                null
            }
        }

        @Synchronized
        private fun credentials(context: Context): Pair<Int, ByteArray> {
            val preferences = prefs(context)
            var port = preferences.getInt("port", 0)
            var token = preferences.getString("token", null)?.let(CallAudioServer::decodeHex)
            if (port !in 20_000..59_999 || token?.size != 32) {
                val random = SecureRandom()
                port = 20_000 + random.nextInt(40_000)
                token = ByteArray(32).also(random::nextBytes)
                check(preferences.edit().putInt("port", port).putString("token", hex(token)).commit())
            }
            return port to token
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_OPUS = 400
    }
}
