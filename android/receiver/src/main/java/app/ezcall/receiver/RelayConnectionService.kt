package app.ezcall.receiver

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

internal class RelayConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(manager: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val id = request.extras?.getString(CALL_ID)
        val call = ReceiverState.inCall
        val relay = ReceiverService.current
        if (request.accountHandle != handle(this) || relay == null || call == null ||
            !ReceiverState.canPresentIncoming(id) || relay.awaitingSystemCall != id || relay.systemCall?.id == id) return failed()
        return RelayConnection(relay, call.id, outgoing = false).apply { display(call); setRinging() }
    }

    override fun onCreateIncomingConnectionFailed(manager: PhoneAccountHandle?, request: ConnectionRequest) {
        request.extras?.getString(CALL_ID)?.let { ReceiverService.current?.ringFallback(it) }
    }

    override fun onCreateOutgoingConnection(manager: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val relay = ReceiverService.current ?: return failed()
        if (request.accountHandle != handle(this) || request.address?.scheme != PhoneAccount.SCHEME_TEL) return failed()
        val id = relay.dial(request.address.schemeSpecificPart, ReceiverState.audioMode == "two_way") ?: return failed()
        return RelayConnection(relay, id, outgoing = true).apply {
            setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
            setDialing()
        }
    }

    companion object {
        private const val CALL_ID = "app.ezcall.receiver.CALL_ID"
        private fun handle(context: Context) = PhoneAccountHandle(
            ComponentName(context, RelayConnectionService::class.java), "relay")
        private fun failed() = Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))

        fun register(context: Context) = runCatching {
            val account = PhoneAccount.builder(handle(context), context.getString(R.string.r_receiver_name))
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL)).build()
            context.getSystemService(TelecomManager::class.java).registerPhoneAccount(account)
        }.isSuccess

        fun unregister(context: Context) {
            runCatching { context.getSystemService(TelecomManager::class.java).unregisterPhoneAccount(handle(context)) }
        }

        fun report(context: Context, call: ReceivedCall): Boolean = runCatching {
            context.getSystemService(TelecomManager::class.java).addNewIncomingCall(handle(context), Bundle().apply {
                putString(CALL_ID, call.id)
                if (call.address.isNotBlank()) putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                    Uri.fromParts("tel", call.address, null))
            })
        }.isSuccess
    }
}

internal class RelayConnection(private val relay: ReceiverService, val id: String, private val outgoing: Boolean) : Connection() {
    private var localEnd: Int? = null
    private var displayedAddress: String? = null
    private var displayedName: String? = null

    init {
        audioModeIsVoip = true
        relay.systemCall = this
        ReceiverState.listeners.add(::refresh)
    }

    fun display(call: ReceivedCall) {
        val name = ReceiverState.data.name(call.address, "")
        if (displayedAddress == call.address && displayedName == name) return
        displayedAddress = call.address
        displayedName = name
        val allowed = call.address.isNotBlank()
        val presentation = if (allowed) TelecomManager.PRESENTATION_ALLOWED else TelecomManager.PRESENTATION_UNKNOWN
        setAddress(if (allowed) Uri.fromParts("tel", call.address, null) else null, presentation)
        setCallerDisplayName(name.takeIf { it != call.address }, presentation)
    }

    private fun refresh() {
        val call = ReceiverState.inCall?.takeIf { it.id == id }
        when {
            call == null && outgoing && relay.isDialing(id) -> return
            call == null || !ReceiverState.paired -> close(localEnd ?: when (state) {
                STATE_RINGING -> DisconnectCause.MISSED
                STATE_DIALING -> DisconnectCause.ERROR
                else -> DisconnectCause.REMOTE
            })
            else -> {
                display(call)
                if (call.state == "active" && state != STATE_ACTIVE) {
                    if (outgoing || relay.answeredHere == id) setActive() else close(DisconnectCause.ANSWERED_ELSEWHERE)
                }
            }
        }
    }

    override fun onAnswer() = relay.answer(id, ReceiverState.audioMode == "two_way" || ReceiverState.inCall?.simulated == true)
    override fun onAnswer(videoState: Int) = onAnswer()
    override fun onReject() = end(DisconnectCause.REJECTED)
    override fun onReject(reason: Int) = onReject()
    override fun onReject(replyMessage: String?) = onReject()
    override fun onAbort() = end(DisconnectCause.CANCELED)
    override fun onDisconnect() = end(DisconnectCause.LOCAL)

    private fun end(cause: Int) {
        localEnd = cause
        relay.end(id)
        if (!ReceiverState.connected || !ReceiverState.canControlCurrentCall && !relay.isDialing(id)) close(cause)
    }

    @Deprecated("Telecom audio callback for Android 12 and 13")
    override fun onCallAudioStateChanged(audioState: android.telecom.CallAudioState?) {
        audioState?.let { relay.setSystemMuted(id, it.isMuted) }
    }

    override fun onMuteStateChanged(isMuted: Boolean) = relay.setSystemMuted(id, isMuted)

    private fun close(cause: Int) {
        ReceiverState.listeners.remove(::refresh)
        if (relay.systemCall === this) relay.systemCall = null
        setDisconnected(DisconnectCause(cause))
        destroy()
    }
}
