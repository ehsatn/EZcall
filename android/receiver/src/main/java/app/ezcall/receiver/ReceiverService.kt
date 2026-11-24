package app.ezcall.receiver

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
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.ezcall.android.PairKey
import androidx.core.content.ContextCompat
import app.ezcall.android.Wire
import org.json.JSONObject
import java.util.UUID

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
    val canControlCurrentCall get() = connected && (canControl || inCall?.simulated == true)
    fun canPresentIncoming(id: String?) =
        id != null && paired && canControlCurrentCall && inCall?.id == id && inCall?.state == "ringing"
    var pendingCall = false
    var pendingSms = false
    var attempts = emptyList<SmsAttempt>()
    fun visibleMessages() = (data.messages + attempts.filter { it.syncedID == null }.map { it.sms })
        .sortedByDescending { it.date }
    var syncing = false
    var microphoneAvailable = false
    var microphoneLive = false
    fun changed() { listeners.toList().forEach { it() } }
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
    private var audio: ReceiverAudio? = null
    private var seal: Wire.AudioSeal? = null
    private var audioID: String? = null
    private var previousAudioMode: Int? = null
    private var pendingSmsID: String? = null
    private var pendingCallID: String? = null
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
        val locale = ContextCompat.getContextForLanguage(this)
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
            link = BleReceiver(this, handler, credential, next, ::rekey, ::show, ::receive, ::playPacket, ::disconnected)
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
        if (!state.canControlCurrentCall || state.inCall?.takeIf { it.state == "ringing" }?.id != callID || state.pendingCall) return
        prepareMicrophone(allowMic)
        if (send("call_answer", callID)) {
            answeredHere = callID
            waitForCall(callID, "Answering on the sending phone…")
        } else releaseMicrophone()
    }

    fun end(callID: String) {
        if ((!state.canControlCurrentCall || state.inCall?.id != callID) && !isDialing(callID)) return
        if (send("call_end", callID)) {
            stopAudio()
            waitForCall(callID, "Ending the call…")
        }
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
            waitForCall(request, "Dialing from the sending phone…")
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
        if (audio != null && microphone) {
            muted = !muted
            if (muted) link?.clearMicrophonePackets()
            state.microphoneLive = !muted
            state.changed()
        }
    }

    fun setSystemMuted(id: String, value: Boolean) {
        if (systemCall?.id != id) return
        muted = value
        if (muted) link?.clearMicrophonePackets()
        state.microphoneLive = microphone && audio != null && !value
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
                if (answeredHere == id && state.inCall?.state == "ringing") answeredHere = null
                pendingCallID = null
                state.pendingCall = false
                if (recovery?.id == id) finishRecovery()
                else if (audio == null) releaseMicrophone()
                show(message.optString("message", "The sending phone could not complete the call action.").take(250))
            }
            "call_resumed" -> if (recovery?.id == id) {
                recovery = null
                if (message.optBoolean("audio") && audio != null && supportsAudioRoute && microphone) {
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
            "audio_stop" -> if (id == audioID) {
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
        recovery?.let {
            if (it.id == call.id) it.confirmedActive = call.state == "active"
            else if (call.state != "ended") finishRecovery()
        }
        when (call.state) {
            "ringing", "active" -> state.inCall = call
            "ended" -> if (previous?.id == call.id) state.inCall = null
        }
        val completedAction = pendingCallID == call.id && call.state != "ringing"
        if (completedAction) {
            pendingCallID = null
            state.pendingCall = false
        }
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
        updateData(state.data.copy(calls = (listOf(call) + state.data.calls.filterNot { it.id == call.id })
            .sortedByDescending { it.date }.take(100)))
        if (completedAction) show(if (call.state == "ended") "Call ended." else "Call connected.")
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

    private fun waitForCall(id: String, detail: String) {
        pendingCallID = id
        state.pendingCall = true
        show(detail)
        handler.postDelayed({
            if (pendingCallID == id) {
                pendingCallID = null
                state.pendingCall = false
                if (audio == null) releaseMicrophone()
                show("Call status was not confirmed. Check the sending phone.")
            }
        }, 16_000)
    }

    private fun prepareMicrophone(requested: Boolean) {
        if (!requested || checkSelfPermission(permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        microphone = true
        if (runCatching { updateForeground() }.isFailure) microphone = false
    }

    private fun releaseMicrophone() {
        val wasEnabled = microphone
        microphone = false
        muted = false
        state.microphoneAvailable = false
        state.microphoneLive = false
        if (wasEnabled && !closing) runCatching { updateForeground() }
    }

    private fun startAudio(id: String, message: JSONObject) {
        if (state.inCall?.id != id && pendingCallID != id) return
        val keyBytes = runCatching { java.util.Base64.getDecoder().decode(message.getString("key")) }.getOrNull()
        if (keyBytes?.size != 32) return
        stopPlayback()
        val cipher = Wire.AudioSeal(keyBytes, Wire.AudioSeal.IPHONE_TO_ANDROID, Wire.AudioSeal.ANDROID_TO_IPHONE)
        keyBytes.fill(0)
        seal = cipher
        audioID = id
        armSyncTimeout()
        val uplink = microphone && message.optBoolean("uplink")
        if (!uplink) releaseMicrophone()
        val manager = getSystemService(AudioManager::class.java)
        if (systemCall?.id != id) runCatching {
            previousAudioMode = manager.mode
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        state.microphoneAvailable = uplink
        state.microphoneLive = uplink && !muted
        audio = ReceiverAudio(uplink, { bytes ->
            handler.post {
                if (seal === cipher && !muted && state.connected) runCatching { link?.sendAudio(cipher.seal(bytes)) }
            }
        }, {
            handler.post {
                if (seal === cipher) {
                    if (uplink && supportsAudioRoute) send("audio_ready", id)
                    show(if (uplink) "Call audio is on this phone." else "Listening on this phone. Speak into the sending phone.")
                }
            }
        }, {
            handler.post {
                if (seal === cipher) {
                    stopAudio()
                    show("Audio stopped. Continue the call on the sending phone.")
                }
            }
        })
    }

    private fun playPacket(packet: ByteArray) {
        if (recovery != null) return
        seal?.open(packet)?.let { audio?.offer(it) }
    }

    private fun stopPlayback() {
        link?.clearMicrophonePackets()
        if (supportsAudioRoute) audioID?.let { send("audio_paused", it) }
        audio?.close()
        audio = null
        seal = null
        audioID = null
        armSyncTimeout()
        previousAudioMode?.let { old -> runCatching { getSystemService(AudioManager::class.java).mode = old } }
        previousAudioMode = null
    }

    private fun stopAudio() {
        stopPlayback()
        releaseMicrophone()
    }

    private fun disconnected() {
        val hadPendingSms = pendingSmsID != null
        if (recovery == null && state.connected && resumeSupported && !leaving && !closing && audioID != null && state.inCall?.state == "active") {
            val interrupted = Recovery(checkNotNull(audioID), SystemClock.elapsedRealtime() + 18_000)
            recovery = interrupted
            handler.postDelayed({ if (recovery === interrupted) finishRecovery() }, 18_000)
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
        pendingCallID = null
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
            .setContentTitle(ContextCompat.getContextForLanguage(this).getString(R.string.r_receiver_name))
            .setContentText(ContextCompat.getContextForLanguage(this).getString(if (state.connected) R.string.r_bt_connected else R.string.r_wait_sender))
            .setContentIntent(open())
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(LINK_ALERT, notification, types)
    }

    fun ringFallback(callID: String) {
        if (awaitingSystemCall != callID || state.inCall?.id != callID ||
            state.inCall?.state != "ringing" || systemCall?.id == callID) return
        awaitingSystemCall = null
        if (!alerts.areNotificationsEnabled()) return
        val screen = PendingIntent.getActivity(this, 0, Intent(this, IncomingCallActivity::class.java)
            .setIdentifier(callID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val locale = ContextCompat.getContextForLanguage(this)
        val notification = Notification.Builder(this, callChannel)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(locale.getString(R.string.r_call_incoming))
            .setContentText(locale.getString(R.string.r_call_from_sender))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .setOngoing(true)
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        alerts.notify(CALL_ALERT, notification)
    }

    private fun alertSms(sms: ReceivedSms) {
        if (!alerts.areNotificationsEnabled()) return
        val locale = ContextCompat.getContextForLanguage(this)
        val body = sms.body.ifBlank { locale.getString(R.string.r_open_message_alert) }
        val redacted = Notification.Builder(this, "messages")
            .setSmallIcon(R.drawable.ic_ezcall_notification)
            .setContentTitle(locale.getString(R.string.r_new_relayed))
            .build()
        alerts.notify(SMS_ALERT, Notification.Builder(this, "messages")
            .setSmallIcon(R.drawable.ic_ezcall_notification)
            .setContentTitle(state.data.name(sms.address, locale.getString(R.string.r_unknown)))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(redacted)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(PendingIntent.getActivities(this, SMS_ALERT, arrayOf(
                Intent(this, ReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                Intent(this, ConversationActivity::class.java).putExtra("address", sms.address)
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
