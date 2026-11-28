package app.ezcall.android

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class RelayState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val qr: String? = null,
    val detail: String? = null,
    val tested: Boolean = false
) {
    companion object {
        var current = RelayState()
            private set
        var listener: ((RelayState) -> Unit)? = null
        var cancelPairing: (() -> Unit)? = null
        var forget: (() -> Unit)? = null
        var simulateCall: (() -> Boolean)? = null
        var refreshAudio: (() -> Unit)? = null
        var refreshSharing: (() -> Unit)? = null
        var audio: CallAudio.Status? = null
            private set
        var testCall: TestCallReport? = null
            private set
        var testLog: AudioTestLog? = null
            private set

        fun set(state: RelayState) {
            current = state
            listener?.invoke(state)
        }

        fun setAudio(status: CallAudio.Status?) {
            audio = status
            listener?.invoke(current)
        }

        fun reportTest(report: TestCallReport) {
            if (testCall == report) return
            testCall = report
            testLog?.add(report.toString())
            listener?.invoke(current)
        }

        fun startTest(id: String, context: Context) {
            testCall = null
            testLog = AudioTestLog("Sender", id, SystemClock::elapsedRealtime).apply { describeDevice(context) }
            reportTest(TestCallReport())
        }

        fun testStage(stage: TestCallReport.Stage) {
            testCall?.let { reportTest(it.copy(stage = stage)) }
        }
    }
}

@SuppressLint("MissingPermission")
internal class RelayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var transport: Peripheral? = null
    private var secret: ByteArray? = null
    private var enrolling = false
    private var activeGeneration = 0
    private var busySync = false
    private var registered = false
    private var failure: String? = null
    private var call: Call? = null
    private var anonymousRing: Runnable? = null
    private val callSetupWakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:call-setup")
            .apply { setReferenceCounted(false) }
    }
    private var simulatedCall: Call? = null
    @Volatile private var simulatedAudio: Wire.AudioSeal? = null
    private var simulationCueUntil = 0L
    private var simulationEchoStarted = false
    private var simulationMicFrames: ArrayBlockingQueue<ByteArray>? = null
    private var forgetID: String? = null
    private var stopped = false
    private var retryDelay = INITIAL_RETRY_MS
    private val retryTransport = Runnable { openTransport() }
    private var callAudio: CallAudio? = null
    // The call that the receiving phone answered or placed. Only that call's audio moves to the receiving phone.
    private var relayedCall: String? = null
    private var handsetAudioCall: String? = null
    private var audioCall: String? = null
    private var audioPublished = false
    private var retriedAudioCall: String? = null
    private var restartAudioAfterStop = false
    private var callAudioOverlay: Triple<WindowManager, MaterialButton, String>? = null
    private var recoveringCall: CallRecovery? = null
    private var answeringCall: String? = null
    private var pendingDial: Dial? = null

    internal data class Call(val id: String, val address: String, val date: Long, val state: String, val fromReceiver: Boolean = false) {
        fun canRetryAudio(failedId: String, relayedId: String?, handsetId: String?, retriedId: String?) =
            state == "active" && id == failedId && id == relayedId && id != handsetId && id != retriedId

        fun withCallerSharing(enabled: Boolean) =
            if (enabled || fromReceiver || address.isEmpty()) this else copy(address = "")

        fun packet(simulated: Boolean = false) = PhoneData.packet("call", id).put("address", address)
            .put("date", date).put("state", state).put("simulated", simulated)
            .apply {
                if (simulated) {
                    put("test_audio_confirmation", true)
                    put("test_audio_diagnostics", true)
                }
            }
    }

    internal data class Dial(val id: String, val address: String, val deadline: Long) {
        fun canCancel(request: String, active: Call?, now: Long) = id == request && active == null && now < deadline
    }

    private val audioEvents = object : CallAudio.Events {
        override fun onAudioStatus(status: CallAudio.Status) {
            handler.post {
                if (stopped) return@post
                RelayState.setAudio(status)
                sendCapabilities()
            }
        }

        override fun onAudioStarted(callId: String, key: ByteArray, uplink: Boolean, pairFrames: Boolean) {
            handler.post {
                try {
                    if (stopped || audioCall != callId || relayedCall != callId ||
                        handsetAudioCall == callId || recoveringCall != null || restartAudioAfterStop) return@post
                    transport?.setAudioActive(true)
                    releaseCallSetupWakeLock()
                    audioPublished = transport?.send(PhoneData.packet("audio_start", callId)
                        .put("key", Base64.encodeToString(key, Base64.NO_WRAP)).put("uplink", uplink).put("dtmf", uplink)
                        .apply { if (pairFrames) put("audio_pairs", true) }) == true
                } finally {
                    key.fill(0)
                }
            }
        }

        override fun onAudioStopped(callId: String, reason: String?) {
            handler.post {
                if (audioCall != callId) return@post
                val restart = restartAudioAfterStop
                val failedBeforePublishing = reason != null && !audioPublished
                restartAudioAfterStop = false
                releaseCallSetupWakeLock()
                audioCall = null
                audioPublished = false
                transport?.setAudioActive(false)
                if (restart && !stopped && relayedCall == callId && call?.state == "active" &&
                    handsetAudioCall != callId && recoveringCall == null) {
                    Log.i("EzcallRelay", "Restarting call audio after Bluetooth recovery")
                    startAudio(callId)
                    return@post
                }
                if (failedBeforePublishing && !stopped && recoveringCall == null &&
                    call?.canRetryAudio(callId, relayedCall, handsetAudioCall, retriedAudioCall) == true &&
                    transport?.let { it.audioReady() && it.audioPayloadLimit() > 0 } == true) {
                    retriedAudioCall = callId
                    Log.i("EzcallRelay", "Retrying call audio setup for the active call")
                    startAudio(callId)
                    return@post
                }
                val stop = PhoneData.packet("audio_stop", callId)
                if (reason != null) stop.put("message", reason)
                transport?.send(stop)
                if (!stopped) startVisible(RelayState.current.connected)
            }
        }

        override fun onAudioPacket(packet: ByteArray) {
            val expires = SystemClock.elapsedRealtime() + AudioBridge.LIVE_QUEUE_MS
            handler.post {
                if (audioPublished && audioCall != handsetAudioCall && SystemClock.elapsedRealtime() < expires) {
                    transport?.sendAudio(packet, expires)
                }
            }
        }

        override fun onUplinkCheck(result: Int, mode: Int) {
            handler.post {
                if (stopped) return@post
                val saved = UplinkHealth.save(this@RelayService, result, mode)
                Log.i("EzcallRelay", "Uplink check: $saved")
                RelayState.setAudio(RelayState.audio)
            }
        }
    }

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> openTransport()
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> handleCall(intent)
                Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> handleSms(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopped) return START_NOT_STICKY
        if (intent?.action == ACTION_HANDSET_AUDIO) {
            if (secret == null) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            intent.data?.schemeSpecificPart?.let(::useHandsetAudio)
            return START_STICKY
        }
        if (secret != null) return START_STICKY
        try {
            require(hasBluetoothPermissions(this))
            startVisible()
            val pair = PairKey(this)
            val manual = intent?.action == ACTION_START
            if (!manual && !pair.exists()) {
                stopWith(null)
                return START_NOT_STICKY
            }
            val key = pair.load()
            check(manual || key != null)
            enrolling = key == null
            secret = key ?: PairKey.random()
            listen()
            callAudio = CallAudio(applicationContext, audioEvents).also {
                it.connect(launch = CallAudio.Setup.enabled(this))
            }
            RelayState.cancelPairing = { if (enrolling) stopWith(null) }
            RelayState.forget = ::forgetPhone
            RelayState.refreshAudio = { callAudio?.connect(launch = false) }
            RelayState.refreshSharing = ::refreshSharing
            snapshotSharing = readableData()
            RelayState.simulateCall = ::startAudioTest
            openTransport()
            if (enrolling) handler.postDelayed({
                if (enrolling) stopWith("Pairing code expired. Start pairing again.")
            }, 120_000)
        } catch (_: Exception) {
            stopWith("Could not start relay. Check Bluetooth and Nearby devices permissions.")
        }
        return if (stopped) START_NOT_STICKY else START_STICKY
    }

    private fun openTransport() {
        val key = secret ?: return
        if (stopped) return
        if (!hasBluetoothPermissions(this)) {
            stopWith("Nearby devices permission is needed to resume the relay.")
            return
        }
        handler.removeCallbacks(retryTransport)
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null) {
            stopWith("Bluetooth LE advertising is unavailable.")
            return
        }
        if (!adapter.isEnabled) {
            if (forgetID != null) { finishForget(confirmed = false); return }
            closeTransport()
            retryDelay = INITIAL_RETRY_MS
            startVisible()
            RelayState.set(RelayState(running = true, detail = "Waiting for Bluetooth to turn on."))
            return
        }
        if (transport != null) return
        if (!adapter.isMultipleAdvertisementSupported) {
            stopWith("Bluetooth LE advertising is unavailable.")
            return
        }
        RelayState.set(RelayState(running = true, detail = "Starting Bluetooth"))
        try {
            val peripheral = Peripheral(this, handler, { checkNotNull(secret) },
                onReady = ::authenticated,
                onRequest = ::handleRequest,
                onAudio = ::receiveAudio,
                onDisconnect = ::disconnected,
                onAdvertising = {
                    retryDelay = INITIAL_RETRY_MS
                    RelayState.set(RelayState(running = true, qr = if (enrolling) PairKey.qr(key) else null))
                },
                onError = ::recoverTransport,
                testLog = { RelayState.testLog.takeIf { simulatedCall != null } }
            )
            transport = peripheral
            peripheral.start(this)
        } catch (_: RuntimeException) {
            recoverTransport("Retrying Bluetooth discovery.")
        }
    }

    private fun recoverTransport(message: String) {
        if (forgetID != null) { finishForget(confirmed = false); return }
        closeTransport()
        if (stopped) return
        startVisible()
        RelayState.set(RelayState(running = true, detail = message))
        handler.removeCallbacks(retryTransport)
        handler.postDelayed(retryTransport, retryDelay)
        retryDelay = nextRetryDelay(retryDelay)
    }

    private fun closeTransport() {
        val old = transport ?: return
        transport = null
        resetLink()
        old.stop()
    }

    private fun listen() {
        val actions = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, actions, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(events, actions)
        registered = true
        val status = IntentFilter(ACTION_SMS_SENT).apply {
            addDataScheme("xcall-sms")
        }
        ContextCompat.registerReceiver(this, smsSent, status, ContextCompat.RECEIVER_NOT_EXPORTED)
        smsReceiverRegistered = true
    }

    private fun startVisible(connected: Boolean = false) {
        val notifications = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel("relay", ezcallContext().getString(R.string.s_relay_channel), NotificationManager.IMPORTANCE_LOW)
        channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
        notifications.createNotificationChannel(channel)
        val main = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val control = callAudioControl(connected)
        showCallAudioOverlay(control)
        val notification = Notification.Builder(this, channel.id).setSmallIcon(R.drawable.ic_ezcall_notification)
            .setContentTitle(ezcallContext().getString(R.string.ez_brand))
            .setContentText(ezcallContext().getString(if (connected) R.string.s_relay_notice_connected else R.string.s_relay_notice_wait))
            .setContentIntent(main).setVisibility(Notification.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (control != null) {
                    val action = Intent(this@RelayService, RelayService::class.java).setAction(ACTION_HANDSET_AUDIO)
                        .setData(Uri.fromParts("ezcall-audio", control.id, null))
                    addAction(Notification.Action.Builder(null, callAudioText(R.string.s_use_handset_audio),
                        PendingIntent.getService(this@RelayService, 0, action, PendingIntent.FLAG_IMMUTABLE)).build())
                }
            }
            .setOnlyAlertOnce(true).setOngoing(true).build()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (Build.VERSION.SDK_INT >= 34 && Feature.SMS.active(this))
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        startForeground(17, notification, types)
    }

    private fun callAudioControl(connected: Boolean) = call?.takeIf {
        connected && handsetAudioCall != it.id && Feature.CONTROL.active(this) &&
            (it.state == "active" && audioCall == it.id ||
                it.state == "ringing" && CallAudio.Setup.enabled(this) && transport?.audioReady() == true)
    }

    private fun callAudioText(id: Int) = ezcallContext().getString(id)

    private fun showCallAudioOverlay(control: Call?) {
        val id = control?.id?.takeIf { Settings.canDrawOverlays(this) }
        if (callAudioOverlay?.third == id) {
            callAudioOverlay?.second?.let(::localizeCallAudioOverlay)
            return
        }
        callAudioOverlay?.let { (window, button) -> runCatching { window.removeViewImmediate(button) } }
        callAudioOverlay = null
        if (id == null) return
        runCatching {
            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val context = createDisplayContext(display).createWindowContext(type, null)
            val window = context.getSystemService(WindowManager::class.java)
            val density = context.resources.displayMetrics.density
            val themed = ContextThemeWrapper(context, R.style.Theme_ezcall_CallAudioOverlay)
            val button = MaterialButton(themed, null, MaterialR.attr.materialButtonStyle).apply {
                localizeCallAudioOverlay(this)
                setIconResource(R.drawable.ic_call)
                iconSize = (18 * density).toInt()
                iconPadding = (8 * density).toInt()
                minimumHeight = (48 * density).toInt()
                maxWidth = ((context.resources.configuration.screenWidthDp - 32) * density).toInt()
                maxLines = 2
                setOnClickListener { useHandsetAudio(id) }
            }
            val size = WindowManager.LayoutParams.WRAP_CONTENT
            val params = WindowManager.LayoutParams(size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (120 * density).toInt()
            }
            window.addView(button, params)
            callAudioOverlay = Triple(window, button, id)
        }.onFailure { Log.w("EzcallRelay", "Call audio overlay unavailable", it) }
    }

    private fun localizeCallAudioOverlay(button: MaterialButton) {
        val localized = ezcallContext()
        button.text = localized.getString(R.string.s_use_handset_audio)
        button.layoutDirection = localized.resources.configuration.layoutDirection
    }

    private fun useHandsetAudio(id: String) {
        val current = callAudioControl(RelayState.current.connected)?.takeIf { it.id == id } ?: return
        handsetAudioCall = id
        relayedCall = null
        if (recoveringCall?.id == id) recoveringCall = null
        audioPublished = false
        transport?.setAudioActive(false)
        releaseCallSetupWakeLock()
        if (audioCall == id) callAudio?.end(HANDSET_AUDIO_MESSAGE)
        transport?.send(current.copy(state = "ended").packet())
        startVisible(RelayState.current.connected)
    }

    private fun authenticated(next: ByteArray?): Boolean {
        if (stopped) return false
        val current = secret ?: return false
        if (enrolling || next != null) {
            // The receiver already stored `next`, so the QR secret is never saved and stops working.
            val saved = runCatching { PairKey(this).save(next ?: current) }.isSuccess
            if (!saved && enrolling) {
                stopWith("Could not save the secure pairing.")
                return false
            }
            // A failed rotation keeps the old key. The receiver still holds it and retries next time.
            if (saved && next != null) secret = next.copyOf()
            enrolling = false
        }
        RelayState.set(RelayState(running = true, connected = true))
        startVisible(connected = true)
        val generation = activeGeneration
        handler.post {
            if (activeGeneration != generation) return@post
            sendCapabilities()
            if (Feature.CALLS.active(this)) call?.takeUnless { it.id == handsetAudioCall }
                ?.let { transport?.send(it.withCallerSharing(Feature.CALLER.active(this)).packet()) }
        }
        return true
    }

    private fun disconnected() {
        if (forgetID != null) { finishForget(confirmed = false); return }
        resetLink()
        if (!stopped) {
            startVisible()
            RelayState.set(RelayState(running = true, detail = "Waiting for the receiving phone"))
        }
    }

    private fun resetLink() {
        stopWatchingData()
        activeGeneration++
        busySync = false
        snapshotID = null
        val active = simulatedCall?.takeIf { it.state == "active" }
            ?: call?.takeIf { it.id == relayedCall && (it.state == "active" || it.id == answeringCall) }
        if (!stopped && forgetID == null && active != null) {
            if (recoveringCall?.id == active.id) return
            callAudio?.setReceiverReady(false, recovering = true)
            if (active === simulatedCall) RelayState.testStage(TestCallReport.Stage.RECONNECTING)
            val recovery = CallRecovery(active.id, SystemClock.elapsedRealtime())
            recoveringCall = recovery
            handler.postDelayed({
                if (recoveringCall === recovery) abandonRecovery()
            }, CallRecovery.WINDOW_MS)
            return
        }
        abandonRecovery()
    }

    private fun abandonRecovery() {
        recoveringCall = null
        stopSimulation(TestCallReport.Stage.LINK_LOST)
        relayedCall = null
        stopAudio()
    }

    private fun sendCapabilities() {
        val calls = Feature.CALLS.active(this) && Feature.CONTROL.active(this)
        val (texts, contacts) = readableData()
        transport?.send(JSONObject().put("type", "capabilities")
            .put("control", calls && hasPermission(permission.ANSWER_PHONE_CALLS))
            .put("dial", calls && hasPermission(permission.CALL_PHONE))
            .put("resume_call", true)
            .put("audio_route", true)
            .put("active_call_id", (simulatedCall ?: call?.takeUnless { it.id == handsetAudioCall || it.state == "ended" })?.id.orEmpty())
            .put("audio", (callAudio?.status ?: CallAudio.Status.NONE).wire)
            .put("texts", texts)
            .put("incremental_sync", true)
            .put("contacts", contacts)
            .put("send_sms", Feature.SEND_SMS.active(this)))
    }

    private fun readableData() =
        (Feature.SMS.active(this) && hasPermission(permission.READ_SMS)) to Feature.CONTACTS.active(this)

    private var snapshotSharing = false to false
    private var snapshotID: String? = null
    private val notifyDataChanged = Runnable {
        if (RelayState.current.connected && forgetID == null && !stopped) {
            transport?.send(JSONObject().put("type", "sync_changed"))
        }
    }
    private val dataObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (watchingData && !handler.hasCallbacks(notifyDataChanged)) {
                handler.postDelayed(notifyDataChanged, 1_000)
            }
        }
    }
    private var watchingData = false

    private fun watchData() {
        if (watchingData) return
        val (sms, contacts) = readableData()
        try {
            if (sms) {
                contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, dataObserver)
                watchingData = true
            }
            if (contacts) {
                contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, dataObserver)
                watchingData = true
            }
        } catch (_: SecurityException) {
            stopWatchingData()
        }
    }

    private fun stopWatchingData() {
        if (watchingData) contentResolver.unregisterContentObserver(dataObserver)
        watchingData = false
        handler.removeCallbacks(notifyDataChanged)
    }
    private data class OutgoingSms(val id: String, val generation: Int, var remaining: Int, var failed: Boolean = false)
    private var outgoingSms: OutgoingSms? = null
    private var smsReceiverRegistered = false
    private val smsSent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sending = outgoingSms ?: return
            if (intent.getStringExtra("id") != sending.id) return
            if (resultCode != Activity.RESULT_OK) sending.failed = true
            if (--sending.remaining == 0) {
                outgoingSms = null
                if (sending.generation == activeGeneration) {
                    sendSmsResult(sending.id, if (sending.failed) "Some or all of the SMS could not be sent. Check Android before retrying." else null)
                }
            }
        }
    }

    private fun refreshSharing() {
        if (stopped) return
        val sharing = readableData()
        if (snapshotSharing != sharing) {
            stopWatchingData()
            snapshotSharing = sharing
            transport?.clearBulk()
            snapshotID?.let(::sendSyncError)
            snapshotID = null
            busySync = false
        }
        val current = call
        val visible = current?.withCallerSharing(Feature.CALLER.active(this))
        call = visible
        if (!Feature.CALLS.active(this)) {
            cancelAnonymousRing()
            if (call != null) sendCall("ended", "")
            call = null
        } else if (visible != null && visible !== current && visible.id != handsetAudioCall) {
            transport?.send(visible.packet())
        }
        if (!Feature.CALLS.active(this) || !Feature.CONTROL.active(this)) {
            if (recoveringCall?.id == relayedCall) recoveringCall = null
            relayedCall = null
            stopAudio()
        }
        startVisible(RelayState.current.connected)
        sendCapabilities()
    }

    private fun handleRequest(message: JSONObject) {
        val type = message.opt("type") as? String ?: error("Invalid request")
        val id = message.opt("id") as? String ?: error("Missing request ID")
        require(id.length == 36 && UUID.fromString(id).toString().equals(id, ignoreCase = true))
        forgetID?.let {
            if (type == "unpair_ack" && id == it) finishForget(confirmed = true)
            return
        }
        when (type) {
            "ping" -> {
                if (transport?.send(PhoneData.packet("pong", id)) == true) {
                    RelayState.set(RelayState.current.copy(tested = true))
                }
            }
            "sync" -> {
                if (message.optBoolean("watch")) watchData()
                val since = (message.opt("smsCursor") as? String)?.let(PhoneData.SmsCursor::decode)
                val contactsVersion = (message.opt("contactsVersion") as? String)?.takeIf { it.length == 64 }
                sync(id, since, contactsVersion)
            }
            "call_answer" -> answer(id.lowercase())
            "call_end" -> hangUp(id.lowercase())
            "call_resume" -> resumeCall(id.lowercase(), message.optString("audio") == "1")
            "test_audio_heard" -> {
                if (simulatedCall?.id == id && simulatedCall?.state == "active") {
                    RelayState.testCall?.let { RelayState.reportTest(it.copy(playbackConfirmed = true)) }
                }
            }
            "test_audio_status" -> {
                if (simulatedCall?.id != id || simulatedCall?.state != "active") return
                val name = message.optString("step")
                val step = ReceiverTestStep.entries.firstOrNull { it.name == name } ?: return
                val report = RelayState.testCall ?: return
                if ((step == ReceiverTestStep.ECHO_HEARD || step == ReceiverTestStep.ECHO_PLAYED) && !report.echoQueued) return
                val elapsed = message.optLong("elapsed_ms", -1L).takeIf { it in 0..120_000 }
                val prefix = if (elapsed == null) "Receiver: " else "Receiver +$elapsed ms · "
                RelayState.testLog?.record(step, prefix)
                when (step) {
                    ReceiverTestStep.CUE_PLAYED -> RelayState.reportTest(report.copy(playbackConfirmed = true))
                    ReceiverTestStep.MIC_SIGNAL -> RelayState.reportTest(report.copy(microphoneSignal = true))
                    ReceiverTestStep.ECHO_PLAYED, ReceiverTestStep.ECHO_HEARD -> RelayState.reportTest(report.copy(echoConfirmed = true))
                    else -> Unit
                }
                if (step in setOf(ReceiverTestStep.CUE_PLAYED, ReceiverTestStep.MIC_SIGNAL, ReceiverTestStep.ECHO_PLAYED) &&
                    RelayState.testCall?.verified == true) stopSimulation()
            }
            "audio_ready", "audio_paused" -> {
                if (call?.id == id && relayedCall == id && audioCall == id && audioPublished && handsetAudioCall != id) {
                    callAudio?.setReceiverReady(type == "audio_ready" && recoveringCall == null)
                }
            }
            "call_dtmf" -> {
                val digit = (message.opt("digit") as? String)?.singleOrNull()
                val callId = id.lowercase()
                if (digit != null && digit in AudioBridge.DTMF_DIGITS &&
                    call?.id == callId && call?.state == "active" && relayedCall == callId &&
                    audioCall == callId && audioPublished && handsetAudioCall != callId && recoveringCall == null &&
                    controlAllowed(permission.ANSWER_PHONE_CALLS)) {
                    callAudio?.sendDtmf(callId, digit)
                }
            }
            "call_dial" -> dial(id.lowercase(), message.opt("address") as? String ?: "")
            "sms_send" -> sendSms(id, message.opt("address") as? String, message.opt("body") as? String)
            else -> error("Unsupported request")
        }
    }

    @Suppress("DEPRECATION")
    private fun answer(id: String) {
        simulatedCall?.takeIf { it.id == id && it.state == "ringing" }?.let {
            answerSimulation(it)
            return
        }
        val current = call
        if (handsetAudioCall == id) return
        if (current?.id == id && answeringCall == id) {
            if (recoveringCall?.id == id) recoveringCall = null
            return
        }
        val telecom = getSystemService(TelecomManager::class.java)
        when {
            current == null || current.id != id || current.state != "ringing" ->
                sendCallError(id, "The call is no longer ringing.")
            !controlAllowed(permission.ANSWER_PHONE_CALLS) -> sendCallError(id, "Allow call control in ezcall on Android.")
            else -> {
                relayedCall = id
                answeringCall = id
                Log.i("EzcallRelay", "Answering incoming cellular call through Telecom")
                if (runCatching { telecom.acceptRingingCall(VideoProfile.STATE_AUDIO_ONLY) }.isFailure) {
                    answerFailed(id)
                } else {
                    handler.postDelayed({ if (answeringCall == id) answerFailed(id) }, 8_000)
                }
            }
        }
    }

    private fun answerFailed(id: String) {
        answeringCall = null
        if (relayedCall == id) relayedCall = null
        if (recoveringCall?.id == id) recoveringCall = null
        Log.i("EzcallRelay", "Telecom did not confirm the cellular answer")
        sendCallError(id, "Android couldn't answer the call. You can still answer on Android if it's ringing.")
    }

    @Suppress("DEPRECATION")
    private fun hangUp(id: String) {
        simulatedCall?.takeIf { it.id == id }?.let { test ->
            RelayState.testLog?.add("Sender handling call_end: state=${test.state}")
            if (test.state == "ringing") stopSimulation(TestCallReport.Stage.NO_ANSWER)
            else stopSimulation()
            return
        }
        val dialing = pendingDial?.canCancel(id, call, SystemClock.elapsedRealtime()) == true
        if (handsetAudioCall == id) return
        if (dialing) {
            pendingDial = null
            releaseCallSetupWakeLock()
        }
        if (call?.id != id && !dialing) return
        Log.i("EzcallRelay", "Receiver requested cellular hangup")
        // The receiving phone has already ended its side. Audio returns to Android even if Telecom keeps the call.
        if (call?.id == id) {
            if (answeringCall == id) answeringCall = null
            if (recoveringCall?.id == id) recoveringCall = null
            relayedCall = null
            stopAudio()
        }
        if (controlAllowed(permission.ANSWER_PHONE_CALLS)) {
            runCatching { getSystemService(TelecomManager::class.java).endCall() }
        }
    }

    private fun resumeCall(id: String, wantsAudio: Boolean) {
        val recovery = recoveringCall
        val simulation = simulatedCall?.let { it.id == id && it.state == "active" } == true
        val relayed = relayedCall == id && call?.let { it.id == id && it.state == "active" } == true &&
            Feature.CALLS.active(this) && Feature.CONTROL.active(this)
        if (recovery?.accepts(id, SystemClock.elapsedRealtime()) != true || (!simulation && !relayed)) {
            sendCallError(id, "The call is no longer available on the receiving phone.")
            return
        }
        val channel = transport ?: return
        val audio = wantsAudio && channel.audioReady() &&
            (if (simulation) simulatedAudio != null else audioCall == id && audioPublished)
        if (!channel.send(PhoneData.packet("call_resumed", id).put("audio", audio))) return
        recoveringCall = null
        Log.i("EzcallRelay", "Bluetooth call recovered; retained audio=$audio")
        if (audio) channel.setAudioActive(true)
        else if (simulation) simulatedAudio = null
        else if (audioCall == null) startAudio(id)
        else stopAudio(restart = true)
        if (simulation) RelayState.testStage(if (audio) TestCallReport.Stage.AUDIO else TestCallReport.Stage.NO_AUDIO)
    }

    private fun dial(id: String, address: String) {
        val number = dialable(address)
        val waiting = pendingDial?.takeIf { SystemClock.elapsedRealtime() < it.deadline }
        when {
            number == null -> sendCallError(id, "That number can't be dialed.")
            call != null || waiting != null -> sendCallError(id, "Finish the current call on Android first.")
            !controlAllowed(permission.CALL_PHONE) -> sendCallError(id, "Allow call control in ezcall on Android.")
            else -> {
                // Telecom and the audio component can finish setting up after the BLE request returns.
                callSetupWakeLock.acquire(DIAL_TIMEOUT_MS)
                pendingDial = Dial(id, number, SystemClock.elapsedRealtime() + DIAL_TIMEOUT_MS)
                val placed = runCatching {
                    getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
                }.isSuccess
                if (!placed) {
                    pendingDial = null
                    releaseCallSetupWakeLock()
                    sendCallError(id, "Android couldn't place the call.")
                }
            }
        }
    }

    private fun controlAllowed(name: String) =
        Feature.CALLS.active(this) && Feature.CONTROL.active(this) && hasPermission(name)

    private fun sendCallError(id: String, message: String) {
        transport?.send(PhoneData.packet("call_error", id).put("message", message))
    }

    private fun sendSms(id: String, address: String?, body: String?) {
        val number = address?.let(::smsAddress)
        val text = body?.takeIf(Wire::validSmsBody)
        when {
            !Feature.SEND_SMS.active(this) ->
                sendSmsResult(id, "Allow Send SMS from the receiving phone in ezcall on Android.")
            number == null || text == null ->
                sendSmsResult(id, "Enter a phone number and a shorter message.")
            outgoingSms != null ->
                sendSmsResult(id, "Wait for Android to finish sending the previous SMS.")
            else -> {
                val manager = getSystemService(SmsManager::class.java)
                val parts = runCatching { manager.divideMessage(text) }.getOrNull()
                if (parts == null || parts.size !in 1..8) {
                    sendSmsResult(id, "This SMS is too long to send.")
                    return
                }
                val sending = OutgoingSms(id, activeGeneration, parts.size)
                val callbacks = ArrayList(parts.indices.map { part ->
                    val intent = Intent(ACTION_SMS_SENT).setPackage(packageName)
                        .setData(Uri.parse("xcall-sms://sent/$id/$part"))
                        .putExtra("id", id)
                    PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                })
                outgoingSms = sending
                val sent = runCatching {
                    manager.sendMultipartTextMessage(number, null, parts, callbacks, null)
                }.isSuccess
                if (!sent) {
                    outgoingSms = null
                    callbacks.forEach(PendingIntent::cancel)
                    sendSmsResult(id, "Android could not send the SMS. Check the SIM and permissions.")
                    return
                }
                handler.postDelayed({
                    if (outgoingSms !== sending) return@postDelayed
                    outgoingSms = null
                    callbacks.forEach(PendingIntent::cancel)
                    if (sending.generation == activeGeneration) {
                        sendSmsResult(id, "Android could not confirm the SMS. Check Android before retrying.")
                    }
                }, 30_000)
            }
        }
    }

    private fun sendSmsResult(id: String, error: String?) {
        val packet = PhoneData.packet("sms_result", id).put("sent", error == null)
        if (error != null) packet.put("message", error)
        transport?.send(packet)
    }

    private fun startAudio(id: String) {
        val audio = callAudio ?: return
        if (handsetAudioCall == id) {
            transport?.send(PhoneData.packet("audio_stop", id).put("message", HANDSET_AUDIO_MESSAGE))
            return
        }
        val channel = transport ?: return
        val payloadLimit = channel.audioPayloadLimit()
        if (recoveringCall != null || audioCall == id || !channel.audioReady() || payloadLimit == 0) return
        callSetupWakeLock.acquire(AUDIO_SETUP_TIMEOUT_MS)
        audioCall = id
        audioPublished = false
        audio.begin(id, payloadLimit, pairFrames = payloadLimit >= 96 && channel.supportsAudioPairs())
        startVisible(RelayState.current.connected)
    }

    private fun stopAudio(restart: Boolean = false) {
        releaseCallSetupWakeLock()
        if (audioCall == null) return
        restartAudioAfterStop = restart
        audioPublished = false
        transport?.setAudioActive(false)
        callAudio?.end(replacingAfterRecovery = restart)
    }

    private fun releaseCallSetupWakeLock() {
        if (callSetupWakeLock.isHeld) callSetupWakeLock.release()
    }

    private fun sync(id: String, since: PhoneData.SmsCursor?, contactsVersion: String?) {
        if (busySync) {
            sendSyncError(id)
            return
        }
        val channel = transport ?: return
        val generation = activeGeneration
        snapshotID = id
        busySync = true
        worker.execute {
            val result = runCatching { PhoneData(this).snapshot(id, since, contactsVersion) }.getOrNull()
            handler.post {
                if (channel !== transport || activeGeneration != generation || snapshotID != id || stopped) return@post
                busySync = false
                if (result == null || !channel.enqueue(result)) sendSyncError(id)
            }
        }
    }

    private fun sendSyncError(id: String) {
        transport?.send(PhoneData.packet("error", id).put("message", "Sync could not finish. Try again."))
    }

    private fun handleSms(intent: Intent) {
        if (!RelayState.current.connected || !Feature.SMS.active(this) || !hasPermission(permission.RECEIVE_SMS)) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent).filterNotNull() }.getOrNull() ?: return
        val first = parts.firstOrNull() ?: return
        val date = System.currentTimeMillis()
        val message = PhoneData.message(first.originatingAddress.orEmpty(),
            parts.joinToString("") { it.messageBody.orEmpty() }, date, "in", first.timestampMillis)
        transport?.send(message.put("type", "sms"))
    }

    @Suppress("DEPRECATION")
    private fun handleCall(intent: Intent) {
        if (!Feature.CALLS.active(this)) {
            call = null
            return
        }
        val number = if (Feature.CALLER.active(this))
            intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty().take(80) else ""
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (pendingDial != null) releaseCallSetupWakeLock()
                pendingDial = null
                stopSimulation(TestCallReport.Stage.PHONE_BUSY)
                if (number.isNotBlank() || !Feature.CALLER.active(this)) {
                    cancelAnonymousRing()
                    sendCall("ringing", number)
                } else if (call?.state != "ringing" && anonymousRing == null) {
                    val delayed = Runnable {
                        anonymousRing = null
                        if (Feature.CALLS.active(this) && !stopped) sendCall("ringing", "")
                    }
                    anonymousRing = delayed
                    handler.postDelayed(delayed, 250)
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                stopSimulation(TestCallReport.Stage.PHONE_BUSY)
                cancelAnonymousRing()
                val dial = pendingDial?.takeIf { call == null && SystemClock.elapsedRealtime() < it.deadline }
                pendingDial = null
                if (answeringCall != null) {
                    answeringCall = null
                    Log.i("EzcallRelay", "Telecom confirmed the cellular answer")
                }
                if (dial != null) {
                    relayedCall = dial.id
                    sendCall("active", dial.address, dial.id)
                } else {
                    sendCall("active", number)
                }
                call?.takeIf { it.id == relayedCall }?.let { startAudio(it.id) }
                if (dial != null && audioCall != dial.id) releaseCallSetupWakeLock()
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (call != null) Log.i("EzcallRelay", "Cellular call returned to idle")
                cancelAnonymousRing()
                answeringCall = null
                stopAudio()
                if (recoveringCall?.id == relayedCall) recoveringCall = null
                relayedCall = null
                handsetAudioCall = null
                retriedAudioCall = null
                if (call != null) sendCall("ended", number)
                call = null
            }
        }
    }

    private fun cancelAnonymousRing() {
        anonymousRing?.let(handler::removeCallbacks)
        anonymousRing = null
    }

    private fun sendCall(state: String, address: String, id: String? = null) {
        val previous = call
        val number = address.ifBlank { previous?.address.orEmpty() }
        val update = Call(previous?.id ?: id ?: UUID.randomUUID().toString(), number,
            previous?.date ?: System.currentTimeMillis(), state, previous?.fromReceiver ?: (id != null))
            .withCallerSharing(Feature.CALLER.active(this))
        if (update == previous) return
        call = update
        if (update.id != handsetAudioCall) transport?.send(update.packet())
        startVisible(RelayState.current.connected)
    }

    private fun startAudioTest(): Boolean {
        if (stopped || simulatedCall != null || call != null || !RelayState.current.connected) return false
        val id = UUID.randomUUID().toString()
        RelayState.startTest(id, this)
        val log = RelayState.testLog
        if (log != null) transport?.describeTestLink(log)
        val test = Call(id, "ezcall test call", System.currentTimeMillis(), "ringing")
        if (transport?.send(test.packet(simulated = true), log) != true) {
            RelayState.testStage(TestCallReport.Stage.SIGNAL_FAILED)
            return true
        }
        simulatedCall = test
        RelayState.testStage(TestCallReport.Stage.RINGING)
        handler.postDelayed({
            if (simulatedCall === test) stopSimulation(TestCallReport.Stage.NO_ANSWER)
        }, 25_000)
        return true
    }

    private fun answerSimulation(test: Call) {
        val log = RelayState.testLog
        log?.add("Sender handling call_answer: state=${test.state}")
        val channel = transport ?: run {
            stopSimulation(TestCallReport.Stage.LINK_LOST)
            return
        }
        val payloadLimit = channel.audioPayloadLimit()
        if (log != null) channel.describeTestLink(log)
        log?.add("Bluetooth audio payload limit: $payloadLimit bytes; ready=${channel.audioReady()}")
        if (!channel.audioReady() || payloadLimit <= 0) {
            sendCallError(test.id, "Test audio needs the Bluetooth audio channel. Reconnect and try again.")
            stopSimulation(TestCallReport.Stage.NO_AUDIO)
            return
        }
        val active = test.copy(state = "active")
        simulatedCall = active
        val key = PairKey.random()
        val cipher = Wire.AudioSeal(key, Wire.AudioSeal.ANDROID_TO_IPHONE, Wire.AudioSeal.IPHONE_TO_ANDROID)
        simulatedAudio = cipher
        simulationEchoStarted = false
        val start = PhoneData.packet("audio_start", test.id)
            .put("key", Base64.encodeToString(key, Base64.NO_WRAP)).put("uplink", true)
            .put("test_echo_sequence", TEST_ECHO_SEQUENCE)
        key.fill(0)
        channel.setAudioActive(true)
        if (!channel.send(active.packet(simulated = true)) || !channel.send(start)) {
            stopSimulation(TestCallReport.Stage.SIGNAL_FAILED)
            return
        }
        RelayState.testCall?.let {
            RelayState.reportTest(it.copy(stage = TestCallReport.Stage.AUDIO, audioStarted = true))
        }
        if (log != null) watchTestMicrophone(cipher, log.remoteWaveform)
        playSimulationCue(cipher, payloadLimit)
        handler.postDelayed({
            if (simulatedCall === active) {
                log?.add("Sender active test timer expired (45000ms)")
                stopSimulation()
            }
        }, 45_000)
    }

    private fun playSimulationCue(cipher: Wire.AudioSeal, payloadLimit: Int) {
        val log = RelayState.testLog
        val firstFrameAt = SystemClock.elapsedRealtime() + 500
        simulationCueUntil = firstFrameAt + SimulationTone.INTRO_FRAMES * 20L
        log?.add("Test cue scheduled: first frame in 500ms, microphone echo opens after ${500 + SimulationTone.INTRO_FRAMES * 20}ms")
        thread(name = "ezcall-test-audio", isDaemon = true) {
            runCatching {
                val encoder = Opus.Encoder(payloadLimit)
                try {
                    repeat(SimulationTone.INTRO_FRAMES) { index ->
                        val target = firstFrameAt + index * 20L
                        SystemClock.sleep((target - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        if (simulatedAudio !== cipher) return@thread
                        val expires = target + AudioBridge.LIVE_QUEUE_MS
                        val pcm = SimulationTone.frame(index)
                        log?.localWaveform?.add(PcmLevel.read(pcm))
                        val packets = encoder.encode(pcm)
                        if (packets.isNotEmpty()) handler.post {
                            if (simulatedAudio !== cipher || simulationEchoStarted) return@post
                            log?.count(AudioTestMetric.CUE_ENCODED, packets.size)
                            if (SystemClock.elapsedRealtime() >= expires) {
                                log?.count(AudioTestMetric.CUE_STALE, packets.size)
                                return@post
                            }
                            for (packet in packets) {
                                val sealed = cipher.seal(packet)
                                if (transport?.sendAudio(sealed, expires) == true) {
                                    log?.count(AudioTestMetric.CUE_QUEUED)
                                    log?.track(AudioTestMetric.CUE_QUEUED, Wire.AudioSeal.sequence(sealed))
                                    val report = RelayState.testCall
                                    if (report?.cue == TestCallReport.Cue.WAITING) {
                                        log?.add("Sender first test cue packet queued")
                                        RelayState.reportTest(report.copy(cue = TestCallReport.Cue.QUEUED))
                                    }
                                } else if (log?.count(AudioTestMetric.CUE_QUEUE_REJECTED) == 1) {
                                    log.add("Sender could not queue a test cue packet")
                                }
                            }
                        }
                    }
                } finally {
                    encoder.release()
                }
            }.onFailure { error ->
                Log.w("EzcallRelay", "Could not encode test audio", error)
                handler.post {
                    if (simulatedAudio === cipher) {
                        simulationCueUntil = 0
                        log?.add("Sender cue encoding exception: ${error.javaClass.simpleName}")
                        RelayState.testCall?.let { RelayState.reportTest(it.copy(cue = TestCallReport.Cue.FAILED)) }
                    }
                }
            }
        }
    }

    private fun watchTestMicrophone(cipher: Wire.AudioSeal, waveform: AudioWaveform) {
        val frames = ArrayBlockingQueue<ByteArray>(5)
        simulationMicFrames = frames
        thread(name = "ezcall-test-mic-waveform", isDaemon = true) {
            runCatching {
                val decoder = Opus.Decoder()
                try {
                    while (simulatedAudio === cipher) {
                        val frame = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        decoder.decode(frame)?.let { waveform.add(PcmLevel.read(it)) }
                    }
                } finally {
                    decoder.release()
                }
            }.onFailure { Log.w("EzcallTestCall", "Could not decode the microphone waveform", it) }
        }
    }

    private fun receiveAudio(packet: ByteArray) {
        val loopback = simulatedAudio
        if (loopback == null) {
            callAudio?.receive(packet)
        } else {
            val receivedAt = SystemClock.elapsedRealtime()
            handler.post {
                if (simulatedAudio !== loopback) return@post
                val log = RelayState.testLog
                val now = SystemClock.elapsedRealtime()
                if (log?.count(AudioTestMetric.UPLINK_RECEIVED) == 1) {
                    log.add("Sender first microphone packet reached GATT: bytes=${packet.size} handler=${now - receivedAt}ms")
                }
                val frame = loopback.open(packet) ?: run {
                    log?.count(AudioTestMetric.UPLINK_REJECTED)
                    log?.record(ReceiverTestStep.UPLINK_REJECTED)
                    return@post
                }
                if (log?.count(AudioTestMetric.UPLINK_AUTHENTICATED) == 1) log.add("Sender first microphone packet authenticated")
                log?.track(AudioTestMetric.UPLINK_AUTHENTICATED, Wire.AudioSeal.sequence(packet))
                simulationMicFrames?.let { frames ->
                    if (!frames.offer(frame)) {
                        frames.poll()
                        frames.offer(frame)
                    }
                }
                if (now < simulationCueUntil) {
                    if (log?.count(AudioTestMetric.UPLINK_BEFORE_ECHO_WINDOW) == 1) log.add("Sender is playing the test cue; microphone echo is held until the cue finishes")
                    return@post
                }
                if (!simulationEchoStarted) {
                    // The authenticated sequence distinguishes test tones from returned microphone audio.
                    loopback.advanceOutgoingSequence(TEST_ECHO_SEQUENCE)
                    simulationEchoStarted = true
                }
                val echo = loopback.seal(frame)
                val queued = transport?.sendAudio(echo) == true
                if (queued) log?.track(AudioTestMetric.ECHO_QUEUED, Wire.AudioSeal.sequence(echo))
                val metric = if (queued) AudioTestMetric.ECHO_QUEUED else AudioTestMetric.ECHO_QUEUE_REJECTED
                if (log?.count(metric) == 1) log.add(if (queued) "Sender first microphone echo queued" else "Sender could not queue a microphone echo")
                RelayState.testCall?.let { RelayState.reportTest(it.receivedMicrophone(queued)) }
            }
        }
    }

    private fun stopSimulation(stage: TestCallReport.Stage = TestCallReport.Stage.FINISHED) {
        val test = simulatedCall ?: return
        val log = RelayState.testLog
        log?.add("Sender stopping simulated call: stage=$stage previousState=${test.state}")
        if (recoveringCall?.id == test.id) recoveringCall = null
        if (simulatedAudio != null) transport?.setAudioActive(false)
        simulatedAudio = null
        simulationMicFrames = null
        simulatedCall = null
        transport?.send(test.copy(state = "ended").packet(simulated = true), log)
        if (stage != TestCallReport.Stage.FINISHED || RelayState.testCall?.stage != TestCallReport.Stage.NO_AUDIO) {
            RelayState.testStage(stage)
        }
    }

    private fun forgetPhone() {
        if (forgetID != null) return
        if (runCatching { PairKey(this).forget() }.isFailure) {
            stopWith("Couldn’t remove the saved pairing. Try again.")
            return
        }
        val id = UUID.randomUUID().toString()
        forgetID = id
        if (transport?.sendForget(PhoneData.packet("unpair", id)) != true) {
            finishForget(confirmed = false)
        } else {
            RelayState.set(RelayState(running = true, detail = "Removing the link from the receiving phone…"))
            handler.postDelayed({ if (forgetID == id) finishForget(confirmed = false) }, 5_000)
        }
    }

    private fun finishForget(confirmed: Boolean) {
        forgetID = null
        stopWith(if (confirmed) null else "If the receiving phone still shows the old link, forget this phone there before scanning.")
    }

    private fun stopWith(message: String?) {
        if (stopped) return
        stopped = true
        failure = message
        handler.removeCallbacks(retryTransport)
        closeTransport()
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        showCallAudioOverlay(null)
        RelayState.cancelPairing = null
        RelayState.forget = null
        RelayState.simulateCall = null
        RelayState.refreshAudio = null
        RelayState.refreshSharing = null
        RelayState.setAudio(null)
        callAudio?.close()
        callAudio = null
        releaseCallSetupWakeLock()
        closeTransport()
        secret?.fill(0)
        secret = null
        if (registered) unregisterReceiver(events)
        if (smsReceiverRegistered) unregisterReceiver(smsSent)
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        RelayState.set(RelayState(detail = failure))
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.ezcall.android.START_RELAY"
        private const val ACTION_SMS_SENT = "app.ezcall.android.SMS_SENT"
        private const val ACTION_HANDSET_AUDIO = "app.ezcall.android.HANDSET_AUDIO"
        private const val HANDSET_AUDIO_MESSAGE = "Call audio is on the sending phone."
        private const val INITIAL_RETRY_MS = 1_000L
        private const val DIAL_TIMEOUT_MS = 15_000L
        private const val AUDIO_SETUP_TIMEOUT_MS = 30_000L
        private val DIALABLE = Regex("[+]?[0-9*#][0-9*#,;]{0,63}")
        private val SMS_ADDRESS = Regex("[+]?[0-9]{7,15}")

        internal fun smsAddress(address: String) = dialable(address)?.takeIf(SMS_ADDRESS::matches)

        internal fun dialable(address: String): String? {
            val number = StringBuilder()
            for (char in address.trim()) {
                val digit = Character.digit(char, 10)
                when {
                    digit >= 0 -> number.append('0' + digit)
                    char in "+*#,;" -> number.append(char)
                    char == '-' || char == '(' || char == ')' || char == '.' || Character.isSpaceChar(char) -> Unit
                    else -> return null
                }
            }
            return number.toString().takeIf(DIALABLE::matches)
        }

        fun hasBluetoothPermissions(context: Context) = context.hasPermission(permission.BLUETOOTH_ADVERTISE) &&
            context.hasPermission(permission.BLUETOOTH_CONNECT)

        internal fun nextRetryDelay(current: Long) = (current * 2).coerceAtMost(30_000L)
    }
}

internal class RelayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED) ||
            !RelayService.hasBluetoothPermissions(context) || !PairKey(context).exists()) return
        runCatching { context.startForegroundService(Intent(context, RelayService::class.java)) }
    }
}
