package app.ezcall.receiver

import app.ezcall.android.ezcallContext
import android.Manifest.permission
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import app.ezcall.android.AudioTestLog
import app.ezcall.android.AudioTestMetric
import app.ezcall.android.PairKey
import app.ezcall.android.ReceiverTestStep
import app.ezcall.android.TEST_ECHO_SEQUENCE
import app.ezcall.android.Wire
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

internal object ReceiverState {
    val listeners = mutableSetOf<() -> Unit>()
    var data = ReceiverData()
    var paired = false
    var connected = false
    var detail = ""
    var canControl = false
    var canDial = false
    var canSendSms = false
    var audioMode = "none"
    var inCall: ReceivedCall? = null
    var presentedCall: String? = null
    val canControlCurrentCall get() = connected && (canControl || inCall?.simulated == true)
    fun canPresentIncoming(id: String?) =
        id != null && paired && canControlCurrentCall && inCall?.id == id && inCall?.state == "ringing"
    var pendingCall = false
    var pendingSms = false
    var attempts = emptyList<SmsAttempt>()
    fun visibleMessages() = (data.messages + attempts.filter { it.syncedID == null }.map { it.sms })
        .sortedByDescending { it.date }
    var syncing = false
    var microphoneOffered = false
    var microphoneAvailable = false
    var microphoneLive = false
    var audioTest: ReceiverAudioTest? = null
    fun changed() { listeners.toList().forEach { it() } }
}

internal class ReceiverAudioTest(val id: String, context: Context) {
    val log = AudioTestLog("Receiver", id.takeIf {
        runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
    }, SystemClock::elapsedRealtime).apply { describeDevice(context) }
}

internal fun AudioTestLog.audioChecks() = listOf(
    ReceiverTestStep.CUE_PLAYED in this,
    ReceiverTestStep.MIC_SIGNAL in this,
    ReceiverTestStep.ECHO_PLAYED in this
)

internal class CallAction(val id: String, val command: String, val startedAt: Long) {
    fun accepts(callID: String, state: String) = id == callID &&
        (state == "ended" || (command != "call_end" && state == "active"))
}

internal class ReceiverService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var vault: ReceiverVault
    private lateinit var key: PairKey
    private var link: BleReceiver? = null
    private var secret: ByteArray? = null
    private var enrolling = false
    private var leaving = false
    private var microphone = false
    private var microphoneDeferred = false
    private var audio: ReceiverAudio? = null
    private var playbackReady = false
    private var seal: Wire.AudioSeal? = null
    private var audioID: String? = null
    private var pairedAudio = false
    private var audioModeRequested = false
    private var pendingSmsID: String? = null
    private var pendingCall: CallAction? = null
    private val pendingCallID get() = pendingCall?.id
    var systemCall: RelayConnection? = null
    var awaitingSystemCall: String? = null
        private set
    var answeredHere: String? = null
        private set
    fun isDialing(id: String) = pendingCallID == id && state.inCall == null && state.connected
    private var lastCallAlert: String? = null
    private var snapshot: Snapshot? = null
    private var syncAgain = false
    private var sharing: Pair<Boolean, Boolean>? = null
    private val syncTimeout = Runnable { finishSync("Sync timed out. Try again.") }
    private var recovery: Recovery? = null
    private var resumeSupported = false
    private var supportsAudioRoute = false
    private var closing = false
    private val state get() = ReceiverState
    private val alerts get() = getSystemService(NotificationManager::class.java)
    private var callChannel = "ringing_calls"

    private class Snapshot(val id: String) {
        var begun = false
        var country = ""
        val messages = linkedMapOf<String, ReceivedSms>()
        val contacts = linkedMapOf<String, ReceivedContact>()
        var live = emptyList<ReceivedSms>()
    }
    private data class Recovery(val id: String, val deadline: Long, var confirmedActive: Boolean = false)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        key = PairKey(this)
        vault = ReceiverVault(this)
        state.paired = key.exists()
        state.data = if (state.paired) vault.load() else ReceiverData()
        if (state.paired) RelayConnectionService.register(this)
        current = this
        updateChannels()
    }

    private fun updateChannels() {
        val locale = ezcallContext()
        // Android freezes channel sound on creation. Keep an existing user's explicit choice.
        val legacy = alerts.getNotificationChannel("calls")
        if (legacy != null && (legacy.hasUserSetImportance() || legacy.hasUserSetSound())) callChannel = legacy.id
        alerts.createNotificationChannels(listOf(
            NotificationChannel("link", locale.getString(R.string.r_channel_link), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(callChannel, locale.getString(R.string.r_channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(Settings.System.DEFAULT_RINGTONE_URI, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
            },
            NotificationChannel("messages", locale.getString(R.string.r_channel_messages), NotificationManager.IMPORTANCE_DEFAULT)
        ))
    }

    fun refreshLocale() {
        updateChannels()
        if (link != null && !leaving) updateForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasNearby(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (leaving) return START_NOT_STICKY
        updateForeground()
        if (link == null) {
            val pairing = intent?.getByteArrayExtra(PAIR)
            intent?.removeExtra(PAIR)
            val saved = runCatching { key.load() }.getOrElse {
                show("The saved pairing cannot be opened. Forget it and scan a fresh code.")
                stopSelf()
                return START_NOT_STICKY
            }
            val credential = saved ?: pairing
            if (credential == null || credential.size != 32) {
                stopSelf()
                return START_NOT_STICKY
            }
            if (saved != null) pairing?.fill(0)
            enrolling = saved == null
            secret = credential
            val next = if (saved != null) runCatching { key.load("next") }.getOrNull() else null
            link = BleReceiver(this, handler, credential, next, ::rekey, ::show, ::receive, ::playPacket, ::disconnected) {
                testLog(state.inCall?.id)
            }
                .also { it.start() }
            if (enrolling) handler.postDelayed({
                if (enrolling) {
                    show("Pairing timed out. Show a new QR code on the sending phone and scan again.")
                    stopSelf()
                }
            }, 120_000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        closing = true
        handler.removeCallbacksAndMessages(null)
        link?.stop()
        stopAudio()
        secret?.fill(0)
        if (current === this) current = null
        disconnected()
        super.onDestroy()
    }

    fun forget() {
        if (leaving) return
        if (!runCatching { key.forget(); vault.clear() }.isSuccess) {
            show("Could not remove the saved link. Try again.")
            return
        }
        leaving = true
        RelayConnectionService.unregister(this)
        enrolling = false
        state.paired = false
        state.audioTest = null
        state.data = ReceiverData()
        state.attempts = emptyList()
        alerts.cancel(SMS_ALERT)
        alerts.cancel(CALL_ALERT)
        show("Pairing removed.")
        stopAudio()
        stopSelf()
    }

    fun sync() {
        if (!state.connected || leaving || closing) return
        if (snapshot != null) {
            syncAgain = true
            return
        }
        val request = Snapshot(id())
        if (!send("sync", request.id, JSONObject().put("watch", true))) return
        snapshot = request
        state.syncing = true
        show("Syncing messages and contacts…")
        armSyncTimeout()
    }

    private fun armSyncTimeout() {
        handler.removeCallbacks(syncTimeout)
        if (snapshot != null && audioID == null) handler.postDelayed(syncTimeout, 60_000)
    }

    fun test() {
        if (!state.connected) return
        val request = id()
        if (send("ping", request)) {
            ping = request
            show("Testing the connection…")
            handler.postDelayed({
                if (ping == request) {
                    ping = null
                    show("The sending phone did not answer the test.")
                }
            }, 6_000)
        }
    }

    fun answer(callID: String, allowMic: Boolean) {
        testLog(callID)?.add("Receiver action: answer, microphoneRequested=$allowMic")
        if (!state.canControlCurrentCall || state.inCall?.takeIf { it.state == "ringing" }?.id != callID || state.pendingCall) return
        prepareMicrophone(allowMic)
        if (send("call_answer", callID)) {
            testStep(ReceiverTestStep.ANSWER_QUEUED, callID)
            answeredHere = callID
            waitForCall(callID, "call_answer", "Answering on the sending phone…")
        } else {
            testStep(ReceiverTestStep.ANSWER_FAILED, callID)
            releaseMicrophone()
        }
    }

    fun end(callID: String) {
        testLog(callID)?.add("Receiver action: end, state=${state.inCall?.state ?: "none"}")
        if ((!state.canControlCurrentCall || state.inCall?.id != callID) && !isDialing(callID)) return
        if (send("call_end", callID)) {
            testStep(ReceiverTestStep.END_QUEUED, callID)
            stopAudio()
            waitForCall(callID, "call_end", "Ending the call…")
        } else testStep(ReceiverTestStep.END_FAILED, callID)
    }

    fun dial(number: String, allowMic: Boolean): String? {
        if (!state.canDial || state.inCall != null || state.pendingCall) return null
        val address = number.trim()
        if (address.isEmpty() || address.length > 128 || address.none(Char::isDigit)) {
            show("Enter a valid phone number.")
            return null
        }
        prepareMicrophone(allowMic)
        val request = id()
        if (send("call_dial", request, JSONObject().put("address", address))) {
            waitForCall(request, "call_dial", "Dialing from the sending phone…")
            return request
        } else releaseMicrophone()
        return null
    }

    fun sendSms(number: String, body: String): Boolean {
        if (!state.canSendSms || pendingSmsID != null) return false
        val address = smsRecipient(number)
        if (address == null || !Wire.validSmsBody(body)) {
            show("Enter a phone number and a message of up to 1,024 UTF-8 bytes.")
            return false
        }
        val request = id()
        if (!send("sms_send", request, JSONObject().put("address", address).put("body", body))) return false
        pendingSmsID = request
        state.pendingSms = true
        val sms = ReceivedSms(request, address, body, System.currentTimeMillis(), "out")
        state.attempts = (state.attempts + SmsAttempt(sms, "Sending SMS…")).takeLast(100)
        show("Sending SMS…")
        handler.postDelayed({
            if (pendingSmsID == request) finishSms("No SMS confirmation received. Check the sending phone before retrying.")
        }, 30_000)
        return true
    }

    fun mute() {
        if (state.microphoneAvailable) {
            muted = !muted
            if (muted) link?.clearMicrophonePackets()
            state.microphoneLive = !muted
            testStep(if (muted) ReceiverTestStep.MIC_MUTED else ReceiverTestStep.MIC_UNMUTED)
            state.changed()
        }
    }

    fun enableMicrophone(id: String, allowed: Boolean) {
        if (!state.connected || state.inCall?.id != id || audioID != id || !state.microphoneOffered || microphone) return
        prepareMicrophone(allowed)
        if (microphone) startMicrophone()
    }

    fun retryDeferredMicrophone() {
        if (microphoneDeferred) audioID?.let { enableMicrophone(it, allowed = true) }
    }

    private fun testLog(id: String?) = state.audioTest?.takeIf { it.id == id }?.log

    private fun testStep(step: ReceiverTestStep, id: String? = state.inCall?.id) {
        val log = testLog(id) ?: return
        if (!log.record(step)) return
        val call = state.inCall
        if (call != null && call.id == id && call.simulated && call.state == "active" && call.testAudioDiagnostics &&
            step != ReceiverTestStep.LINK_LOST) {
            if (!send("test_audio_status", call.id, JSONObject().put("step", step.name).put("elapsed_ms", log.elapsedMs()))) {
                log.record(ReceiverTestStep.STATUS_NOT_QUEUED)
            }
        }
        state.changed()
    }

    fun setSystemMuted(id: String, value: Boolean) {
        if (systemCall?.id != id) return
        muted = value
        if (muted) link?.clearMicrophonePackets()
        state.microphoneLive = state.microphoneAvailable && !value
        testStep(if (muted) ReceiverTestStep.MIC_MUTED else ReceiverTestStep.MIC_UNMUTED, id)
        state.changed()
    }

    private var muted = false
    private var ping: String? = null

    private fun rekey(used: ByteArray, next: ByteArray): Boolean {
        check(!leaving && !closing)
        try {
            key.save(used, next)
        } catch (error: Exception) {
            if (enrolling) throw error
            return false
        }
        if (enrolling) RelayConnectionService.register(this)
        enrolling = false
        state.paired = true
        return true
    }

    private fun receive(message: JSONObject) {
        val type = message.getString("type")
        val id = message.optString("id")
        if (leaving) return
        when (type) {
            "ready" -> {
                if (state.connected) return
                check(state.paired && !enrolling)
                state.connected = true
                updateCallPriority()
                updateForeground()
                show("Connected over Bluetooth.")
            }
            "capabilities" -> {
                state.canControl = message.optBoolean("control")
                state.canDial = message.optBoolean("dial")
                state.canSendSms = message.optBoolean("send_sms")
                state.audioMode = message.optString("audio", "none")
                supportsAudioRoute = message.optBoolean("audio_route")
                resumeSupported = message.optBoolean("resume_call")
                val interrupted = recovery
                if (interrupted != null && resumeSupported && SystemClock.elapsedRealtime() < interrupted.deadline) {
                    send("call_resume", interrupted.id, JSONObject().put("audio", if (seal != null) "1" else "0"))
                }
                val grants = message.optBoolean("texts") to message.optBoolean("contacts")
                if (sharing != grants) {
                    if (sharing?.first == true && !grants.first) state.attempts = emptyList()
                    sharing = grants
                    sync()
                }
                state.changed()
            }
            "pong" -> if (id == ping) {
                ping = null
                show("Connection test passed.")
            }
            "call" -> message.call()?.let(::receiveCall)
            "call_error" -> if (id == pendingCallID || id == state.inCall?.id || id == recovery?.id) {
                testStep(ReceiverTestStep.CALL_ERROR, id)
                if (answeredHere == id && state.inCall?.state == "ringing") answeredHere = null
                pendingCall = null
                state.pendingCall = false
                if (recovery?.id == id) finishRecovery()
                else if (audio == null) releaseMicrophone()
                updateCallPriority()
                show(message.optString("message", "The sending phone could not complete the call action.").take(250))
            }
            "call_resumed" -> if (recovery?.id == id) {
                recovery = null
                testStep(if (message.optBoolean("audio")) ReceiverTestStep.LINK_RESUMED
                    else ReceiverTestStep.AUDIO_NOT_RESUMED, id)
                if (message.optBoolean("audio") && supportsAudioRoute && playbackReady && state.microphoneAvailable) {
                    send("audio_ready", id)
                } else if (!message.optBoolean("audio")) stopAudio()
                show("Call reconnected.")
            }
            "sms" -> message.sms()?.let(::receiveSms)
            "sync_changed" -> sync()
            "sms_result" -> if (id == pendingSmsID) {
                val sent = message.optBoolean("sent")
                finishSms(if (sent) "SMS sent." else message.optString("message", "The sending phone could not send this SMS.").take(250))
                if (sent) sync()
            }
            "audio_start" -> startAudio(id, message)
            "audio_stop" -> if (id == audioID || audioID == null && (id == state.inCall?.id || id == pendingCallID)) {
                testStep(ReceiverTestStep.AUDIO_STOPPED, id)
                stopAudio()
                show(message.optString("message", "Call audio is on the sending phone.").take(250))
            }
            "sync_begin", "sms_batch", "contacts_batch", "sync_end", "error" -> receiveSnapshot(type, id, message)
            "unpair" -> if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
                key.forget()
                vault.clear()
                leaving = true
                RelayConnectionService.unregister(this)
                state.paired = false
                state.audioTest = null
                state.data = ReceiverData()
                state.attempts = emptyList()
                stopAudio()
                alerts.cancel(SMS_ALERT)
                alerts.cancel(CALL_ALERT)
                send("unpair_ack", id)
                show("The sending phone removed this link.")
                handler.postDelayed({ stopSelf() }, 700)
            }
        }
    }

    private fun receiveCall(call: ReceivedCall) {
        val previous = state.inCall
        val existing = state.data.calls.find { it.id == call.id }
        val action = pendingCall?.takeIf { it.id == call.id }?.command
        val kind = when {
            call.state == "ringing" -> CallKind.INCOMING
            action == "call_dial" -> CallKind.OUTGOING
            call.state == "ended" && previous?.id == call.id && previous.state == "ringing" && action != "call_end" -> CallKind.MISSED
            else -> existing?.kind
        }
        if (call.simulated && call.state != "ended" && state.audioTest?.id != call.id) {
            state.audioTest = ReceiverAudioTest(call.id, this)
            testLog(call.id)?.let { link?.describeTestLink(it) }
        }
        recovery?.let {
            if (it.id == call.id) it.confirmedActive = call.state == "active"
            else if (call.state != "ended") finishRecovery()
        }
        when (call.state) {
            "ringing", "active" -> state.inCall = call
            "ended" -> if (previous?.id == call.id) state.inCall = null
        }
        val completedAction = pendingCall?.takeIf { it.accepts(call.id, call.state) }
        if (completedAction != null) {
            testLog(call.id)?.add("Receiver ${completedAction.command} confirmed: state=${call.state} after ${SystemClock.elapsedRealtime() - completedAction.startedAt}ms")
            pendingCall = null
            state.pendingCall = false
        }
        updateCallPriority()
        val ringingID = state.inCall?.takeIf { it.state == "ringing" }?.id
        if (lastCallAlert != ringingID) {
            alerts.cancel(CALL_ALERT)
            lastCallAlert = ringingID
            awaitingSystemCall = ringingID
            if (ringingID != null) {
                if (state.canControlCurrentCall && RelayConnectionService.report(this, call)) {
                    handler.postDelayed({ if (awaitingSystemCall == ringingID) ringFallback(ringingID) }, 5_000)
                } else ringFallback(ringingID)
            }
        }
        if (call.state == "ended") {
            if (recovery?.id == call.id) finishRecovery()
            if (audioID == call.id || previous?.id == call.id) stopAudio()
        }
        if (call.simulated) {
            val step = when (call.state) {
                "ringing" -> ReceiverTestStep.RINGING
                "active" -> ReceiverTestStep.ACTIVE
                else -> ReceiverTestStep.ENDED
            }
            testStep(step, call.id)
            state.changed()
        }
        else updateData(state.data.copy(calls = (listOf(call.copy(kind = kind)) + state.data.calls.filterNot { it.id == call.id })
            .sortedByDescending { it.date }.take(100)))
        if (completedAction != null) show(if (call.state == "ended") "Call ended." else "Call connected.")
    }

    private fun receiveSms(sms: ReceivedSms) {
        val known = state.data.messages.any { it.id == sms.id }
        snapshot?.let { it.live = mergeSms(listOf(sms), it.live) }
        updateData(state.data.copy(messages = mergeSms(listOf(sms), state.data.messages)))
        if (!known && sms.direction == "in") alertSms(sms)
    }

    private fun receiveSnapshot(type: String, id: String, packet: JSONObject) {
        val stage = snapshot?.takeIf { it.id == id } ?: return
        armSyncTimeout()
        when (type) {
            "sync_begin" -> {
                stage.begun = true
                stage.country = packet.optString("country").uppercase(java.util.Locale.ROOT)
                    .takeIf { it.length == 2 && it.all { letter -> letter in 'A'..'Z' } }.orEmpty()
            }
            "error" -> finishSync(packet.optString("message", "Sync failed. Try again.").take(250))
            "sms_batch" -> if (stage.begun) packet.getJSONArray("items").read(100, JSONObject::sms).forEach {
                if (stage.messages.size < 100) stage.messages[it.id] = it
            }
            "contacts_batch" -> if (stage.begun) packet.getJSONArray("items").read(5_000, JSONObject::contact).forEach {
                if (stage.contacts.size < 5_000) stage.contacts[it.id] = it
            }
            "sync_end" -> if (stage.begun) {
                val data = state.data.copy(
                    messages = mergeSms(stage.live, stage.messages.values),
                    contacts = stage.contacts.values.sortedBy { it.name.lowercase() }, country = stage.country
                )
                finishSync(if (updateData(data)) "Messages and contacts are up to date." else "Could not save the sync. Try again.")
            }
        }
    }

    private fun updateData(data: ReceiverData): Boolean {
        val saved = runCatching { vault.save(data) }.isSuccess
        if (saved) {
            if (data.messages !== state.data.messages) {
                state.attempts = reconcileAttempts(state.attempts, state.data.messages, data.messages, data.country)
            }
            state.data = data
            state.changed()
        } else show("Could not save relayed data on this phone.")
        return saved
    }

    private fun waitForCall(id: String, command: String, detail: String) {
        val action = CallAction(id, command, SystemClock.elapsedRealtime())
        pendingCall = action
        state.pendingCall = true
        updateCallPriority()
        show(detail)
        handler.postDelayed({
            if (pendingCall === action) {
                pendingCall = null
                state.pendingCall = false
                updateCallPriority()
                if (audio == null) releaseMicrophone()
                testLog(id)?.add("Receiver $command timed out after ${SystemClock.elapsedRealtime() - action.startedAt}ms")
                testStep(if (command == "call_end") ReceiverTestStep.END_TIMEOUT else ReceiverTestStep.ANSWER_TIMEOUT, id)
                show("Call status was not confirmed. Check the sending phone.")
            }
        }, 16_000)
    }

    private fun updateCallPriority() {
        link?.setCallPriority(state.inCall != null || pendingCallID != null)
    }

    private fun prepareMicrophone(requested: Boolean) {
        if (checkSelfPermission(permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            testStep(ReceiverTestStep.MIC_PERMISSION_MISSING)
            return
        }
        if (!requested) {
            testStep(ReceiverTestStep.MIC_NOT_REQUESTED)
            return
        }
        microphoneDeferred = false
        microphone = true
        runCatching { updateForeground() }.onFailure {
            microphone = false
            microphoneDeferred = true
            testStep(ReceiverTestStep.MIC_FOREGROUND_FAILED)
            Log.w("EzcallReceiver", "Could not enable microphone foreground service: ${it.javaClass.simpleName}")
        }
    }

    private fun releaseMicrophone() {
        val wasEnabled = microphone
        microphone = false
        state.microphoneAvailable = false
        state.microphoneLive = false
        if (wasEnabled && !closing) runCatching { updateForeground() }
    }

    private fun startAudio(id: String, message: JSONObject) {
        if (state.inCall?.id != id && pendingCallID != id) return
        testStep(ReceiverTestStep.AUDIO_START, id)
        val keyBytes = runCatching { java.util.Base64.getDecoder().decode(message.getString("key")) }.getOrNull()
        if (keyBytes?.size != 32) {
            keyBytes?.fill(0)
            testStep(ReceiverTestStep.INVALID_AUDIO_KEY, id)
            return
        }
        stopPlayback()
        val cipher = Wire.AudioSeal(keyBytes, Wire.AudioSeal.IPHONE_TO_ANDROID, Wire.AudioSeal.ANDROID_TO_IPHONE)
        keyBytes.fill(0)
        seal = cipher
        audioID = id
        pairedAudio = message.optBoolean("audio_pairs")
        armSyncTimeout()
        state.microphoneOffered = message.optBoolean("uplink")
        if (!state.microphoneOffered) {
            releaseMicrophone()
            testStep(ReceiverTestStep.MIC_NOT_OFFERED, id)
        }
        val manager = getSystemService(AudioManager::class.java)
        val log = testLog(id)
        val echoSequence = message.optLong("test_echo_sequence", -1L)
            .takeIf { log != null && it == TEST_ECHO_SEQUENCE }
        val usesSystemCall = systemCall?.id == id
        log?.add("Audio setup: uplink=${state.microphoneOffered} pairedPackets=$pairedAudio micEnabled=$microphone systemCall=$usesSystemCall")
        val detail: ((String) -> Unit)? = log?.let { test -> { message ->
            val at = SystemClock.elapsedRealtime()
            handler.post { test.add("$message (worker to handler=${SystemClock.elapsedRealtime() - at}ms)") }
            Unit
        } }
        audioModeRequested = !usesSystemCall
        val routing = audioRouting.submit {
            val startedAt = SystemClock.elapsedRealtime()
            if (!usesSystemCall) runCatching {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
            }.onFailure { detail?.invoke("Audio communication mode failed: ${it.javaClass.simpleName}") }
            // The test stays on the call route. On the loudspeaker the microphone would hear the cue and pass its own
            // check without anyone speaking.
            if (detail != null) {
                val output = runCatching {
                    val stream = AudioManager.STREAM_VOICE_CALL
                    "communication device type=${manager.communicationDevice?.type ?: "unknown"} volume=${manager.getStreamVolume(stream)}/${manager.getStreamMaxVolume(stream)}"
                }.getOrDefault("Android did not provide device and volume information")
                detail("Audio output: $output route setup=${SystemClock.elapsedRealtime() - startedAt}ms")
            }
        }
        audio = ReceiverAudio(routing, {
            handler.post {
                if (seal === cipher) {
                    playbackReady = true
                    testStep(ReceiverTestStep.PLAYBACK_READY, id)
                    updateAudioReadiness()
                }
            }
        }, { error ->
            handler.post {
                if (seal === cipher) {
                    log?.add("Audio playback exception: ${error.javaClass.simpleName}")
                    testStep(ReceiverTestStep.PLAYBACK_FAILED, id)
                    stopAudio()
                    show("Audio stopped. Continue the call on the sending phone.")
                }
            }
        }, onTestStep = if (state.audioTest?.id == id) { step ->
            handler.post { if (seal === cipher) testStep(step, id) }
        } else null, onTestDetail = detail,
            onTestPlayedPacket = log?.let { test -> { sequence -> test.track(AudioTestMetric.PCM_WRITTEN, sequence) } },
            testLog = log, echoSequence = echoSequence)
        if (microphone) startMicrophone()
    }

    private fun startMicrophone() {
        val cipher = seal ?: return
        val log = testLog(audioID)
        val payloadLimit = link?.audioPayloadLimit()?.takeIf { it > 0 } ?: run {
            testStep(ReceiverTestStep.MIC_NO_BLUETOOTH_PAYLOAD, audioID)
            return
        }
        log?.add("Microphone Opus payload limit: $payloadLimit bytes")
        audio?.startMicrophone(payloadLimit, { bytes ->
            val expires = SystemClock.elapsedRealtime() + 80
            handler.post {
                if (seal !== cipher) return@post
                log?.count(AudioTestMetric.UPLINK_ENCODED)
                if (!state.microphoneLive || !state.connected) return@post
                if (SystemClock.elapsedRealtime() >= expires) {
                    log?.count(AudioTestMetric.UPLINK_STALE)
                    return@post
                }
                val queued = runCatching {
                    val packet = cipher.seal(bytes)
                    val sent = link?.sendAudio(packet) == true
                    if (sent) log?.track(AudioTestMetric.UPLINK_QUEUED, Wire.AudioSeal.sequence(packet))
                    sent
                }.onFailure { error -> log?.add("Microphone packet could not be sealed or queued: ${error.javaClass.simpleName}") }.getOrDefault(false)
                log?.count(if (queued) AudioTestMetric.UPLINK_QUEUED else AudioTestMetric.UPLINK_QUEUE_REJECTED)
                testStep(if (queued) ReceiverTestStep.MIC_QUEUED else ReceiverTestStep.MIC_QUEUE_REJECTED, audioID)
            }
        }, {
            handler.post {
                if (seal === cipher) {
                    state.microphoneAvailable = true
                    state.microphoneLive = !muted
                    testStep(ReceiverTestStep.MIC_CAPTURE_STARTED, audioID)
                    Log.i("EzcallReceiver", "Microphone capture started")
                    updateAudioReadiness()
                }
            }
        }, { error ->
            handler.post {
                if (seal === cipher) {
                    Log.w("EzcallReceiver", "Microphone capture failed", error)
                    log?.add("Microphone capture exception: ${error.javaClass.simpleName}")
                    testStep(ReceiverTestStep.MIC_CAPTURE_FAILED, audioID)
                    link?.clearMicrophonePackets()
                    releaseMicrophone()
                    microphoneDeferred = true
                    if (supportsAudioRoute) audioID?.let { send("audio_paused", it) }
                    updateAudioReadiness()
                }
            }
        })
    }

    private fun updateAudioReadiness() {
        val id = audioID ?: return
        if (!playbackReady || !state.connected || recovery != null) return
        if (state.inCall?.simulated == true) {
            state.changed()
            return
        }
        if (supportsAudioRoute && state.microphoneAvailable) send("audio_ready", id)
        if (audioID == id && state.connected) {
            show(when {
                state.microphoneAvailable -> "Call audio is on this phone."
                state.microphoneOffered -> "Call audio is on the sending phone."
                else -> "Listening on this phone. Speak into the sending phone."
            })
        }
    }

    private fun playPacket(packet: ByteArray) {
        if (recovery != null) return
        val log = testLog(state.inCall?.id)
        val cipher = seal ?: run {
            if (log?.count(AudioTestMetric.DOWNLINK_BEFORE_SETUP) == 1) log.add("Audio arrived before Receiver had an active audio setup")
            return
        }
        val frame = cipher.open(packet)
        if (frame == null) {
            log?.count(AudioTestMetric.DOWNLINK_REJECTED)
            testStep(ReceiverTestStep.PACKET_REJECTED, audioID)
        }
        else {
            log?.count(AudioTestMetric.DOWNLINK_AUTHENTICATED)
            val sequence = log?.let { Wire.AudioSeal.sequence(packet) }
            if (sequence != null) log.track(AudioTestMetric.DOWNLINK_AUTHENTICATED, sequence)
            if (pairedAudio) {
                val (first, second) = Wire.AudioSeal.splitPair(frame) ?: return
                if (audio?.offer(first, sequence) == true) log?.count(AudioTestMetric.PLAYBACK_QUEUE_DROPPED)
                if (audio?.offer(second, sequence) == true) log?.count(AudioTestMetric.PLAYBACK_QUEUE_DROPPED)
            } else if (audio?.offer(frame, sequence) == true) log?.count(AudioTestMetric.PLAYBACK_QUEUE_DROPPED)
            testStep(ReceiverTestStep.PACKET_AUTHENTICATED, audioID)
        }
    }

    private fun stopPlayback() {
        testLog(audioID)?.add("Receiver closing the audio path")
        link?.clearMicrophonePackets()
        if (supportsAudioRoute) audioID?.let { send("audio_paused", it) }
        audio?.close()
        audio = null
        seal = null
        audioID = null
        pairedAudio = false
        playbackReady = false
        state.microphoneOffered = false
        state.microphoneAvailable = false
        state.microphoneLive = false
        armSyncTimeout()
        if (audioModeRequested) {
            audioModeRequested = false
            val manager = getSystemService(AudioManager::class.java)
            audioRouting.execute { runCatching { manager.mode = AudioManager.MODE_NORMAL } }
        }
    }

    private fun stopAudio() {
        stopPlayback()
        releaseMicrophone()
        microphoneDeferred = false
        muted = false
    }

    private fun disconnected() {
        testStep(ReceiverTestStep.LINK_LOST)
        val hadPendingSms = pendingSmsID != null
        if (recovery == null && state.connected && resumeSupported && !leaving && !closing && audioID != null && state.inCall?.state == "active") {
            val interrupted = Recovery(checkNotNull(audioID), SystemClock.elapsedRealtime() + 18_000)
            recovery = interrupted
            handler.postDelayed({
                if (recovery === interrupted) {
                    testStep(ReceiverTestStep.RECOVERY_TIMEOUT, interrupted.id)
                    finishRecovery()
                }
            }, 18_000)
        } else if (recovery == null || leaving || closing) {
            state.inCall = null
            stopAudio()
        } else recovery?.confirmedActive = false
        state.connected = false
        state.canControl = false
        state.canDial = false
        state.canSendSms = false
        state.audioMode = "none"
        state.pendingCall = false
        if (hadPendingSms) setSmsStatus("No SMS confirmation received. Check the sending phone before retrying.")
        state.pendingSms = false
        state.syncing = false
        snapshot = null
        syncAgain = false
        sharing = null
        handler.removeCallbacks(syncTimeout)
        lastCallAlert = null
        awaitingSystemCall = null
        ping = null
        pendingSmsID = null
        pendingCall = null
        if (hadPendingSms) smsUnconfirmed = true
        alerts.cancel(CALL_ALERT)
        if (!closing) updateForeground()
        state.changed()
    }

    private var smsUnconfirmed = false

    private fun finishRecovery() {
        val interrupted = recovery ?: return
        recovery = null
        stopAudio()
        if (state.inCall?.id == interrupted.id && !interrupted.confirmedActive) state.inCall = null
        updateCallPriority()
        show("Call audio returned to the sending phone.")
    }

    private fun finishSync(detail: String) {
        snapshot = null
        state.syncing = false
        handler.removeCallbacks(syncTimeout)
        show(detail)
        if (syncAgain) {
            syncAgain = false
            sync()
        }
    }

    private fun finishSms(detail: String) {
        setSmsStatus(detail)
        pendingSmsID = null
        state.pendingSms = false
        show(detail)
    }

    private fun setSmsStatus(detail: String) {
        state.attempts = state.attempts.map { if (it.sms.id == pendingSmsID) it.copy(detail = detail) else it }
    }

    private fun send(type: String, id: String, body: JSONObject = JSONObject()): Boolean =
        link?.send(body.put("type", type).put("id", id)) == true

    private fun show(detail: String) {
        state.detail = if (smsUnconfirmed) {
            smsUnconfirmed = false
            "$detail Check the sending phone before retrying the unconfirmed SMS."
        } else detail
        state.changed()
    }

    private fun updateForeground() {
        val notification = Notification.Builder(this, "link")
            .setSmallIcon(R.drawable.ic_ezcall_notification)
            .setContentTitle(ezcallContext().getString(R.string.r_receiver_name))
            .setContentText(ezcallContext().getString(if (state.connected) R.string.r_bt_connected else R.string.r_wait_sender))
            .setContentIntent(open())
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(LINK_ALERT, notification, types)
    }

    fun ringFallback(callID: String) {
        val call = state.inCall
        if (awaitingSystemCall != callID || call?.id != callID ||
            call.state != "ringing" || systemCall?.id == callID) return
        awaitingSystemCall = null
        if (!alerts.areNotificationsEnabled()) return
        val screen = PendingIntent.getActivity(this, 0, receiverCallScreen(callID), PendingIntent.FLAG_IMMUTABLE)
        val locale = ezcallContext()
        val incoming = locale.getString(R.string.r_call_incoming)
        val description = locale.getString(R.string.r_call_from_sender)
        val public = Notification.Builder(this, callChannel)
            .setSmallIcon(R.drawable.ic_ezcall_notification)
            .setContentTitle(incoming)
            .setContentText(description)
            .build()
        val notification = Notification.Builder(this, callChannel)
            .setSmallIcon(R.drawable.ic_ezcall_notification)
            .setContentTitle(incoming)
            .setContentText(if (call.address.isBlank()) description else
                state.data.name(call.address, description).let { if (it == call.address) locale.receiverNumberDisplay(it) else it })
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(screen)
            .apply {
                if (Build.VERSION.SDK_INT < 34 || alerts.canUseFullScreenIntent()) setFullScreenIntent(screen, true)
            }
            .setOngoing(true)
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        alerts.notify(CALL_ALERT, notification)
    }

    private fun alertSms(sms: ReceivedSms) {
        if (!alerts.areNotificationsEnabled()) return
        val locale = ezcallContext()
        val body = sms.body.ifBlank { locale.getString(R.string.r_open_message_alert) }
        val redacted = Notification.Builder(this, "messages")
            .setSmallIcon(R.drawable.ic_ezcall_notification)
            .setContentTitle(locale.getString(R.string.r_new_relayed))
            .build()
        alerts.notify(SMS_ALERT, NotificationCompat.Builder(this, "messages")
            .setSmallIcon(R.drawable.ic_ezcall_notification)
            .setContentTitle(state.data.name(sms.address, locale.getString(R.string.r_unknown)))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSilent(state.inCall?.state == "active")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redacted)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(PendingIntent.getActivities(this, SMS_ALERT, arrayOf(
                Intent(this, ReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(this, ConversationActivity::class.java).setIdentifier(sms.id)
                    .putExtra("address", sms.address).putExtra("messageID", sms.id)
            ), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build())
    }

    private fun open() = PendingIntent.getActivity(this, 0,
        Intent(this, ReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        private const val PAIR = "pairing"
        private const val LINK_ALERT = 20
        private const val CALL_ALERT = 21
        private const val SMS_ALERT = 22
        private val audioRouting = Executors.newSingleThreadExecutor { Thread(it, "ezcall-audio-route") }
        var current: ReceiverService? = null
            private set

        fun hasNearby(context: Context) = arrayOf(permission.BLUETOOTH_SCAN, permission.BLUETOOTH_CONNECT)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        fun start(context: Context, secret: ByteArray? = null) {
            context.startForegroundService(Intent(context, ReceiverService::class.java).apply {
                if (secret != null) putExtra(PAIR, secret)
            })
        }

        private fun id() = UUID.randomUUID().toString()
    }
}

internal class ReceiverBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED) ||
            !ReceiverService.hasNearby(context) || !PairKey(context).exists()) return
        runCatching { ReceiverService.start(context) }
    }
}
