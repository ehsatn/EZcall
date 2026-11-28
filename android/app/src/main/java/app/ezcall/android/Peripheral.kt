package app.ezcall.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.ArrayDeque

internal fun audioBeforeControl(controlIsSms: Boolean?, afterAudio: Boolean, queuedAudio: Int) =
    controlIsSms == null || (controlIsSms && (!afterAudio || queuedAudio > 1))

internal fun initialLinkMtu(negotiated: Int?, saved: Int?, carried: Boolean) =
    negotiated ?: saved?.takeIf { carried } ?: 23

@SuppressLint("MissingPermission")
internal class Peripheral(
    context: Context,
    private val handler: Handler,
    private val key: () -> ByteArray,
    /** Receives the rotated key when the receiver opted in to key rotation. */
    private val onReady: (ByteArray?) -> Boolean,
    private val onRequest: (JSONObject) -> Unit,
    private val onAudio: (ByteArray) -> Unit,
    private val onDisconnect: () -> Unit,
    private val onAdvertising: () -> Unit,
    private val onError: (String) -> Unit,
    private val testLog: () -> AudioTestLog?
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val mtuStore = context.getSharedPreferences("ble_link", Context.MODE_PRIVATE)
    private val linkMtu = mutableMapOf<BluetoothDevice, Int>()
    private val carriedLinks = mutableSetOf<BluetoothDevice>()
    private val advertiser = manager.adapter?.bluetoothLeAdvertiser
    private val debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var openedAt = 0L
    @Volatile private var peer: Peer? = null
    @Volatile private var audioActive = false
    private var audioDelivery: AudioDelivery? = null
    private val audioWakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:call-audio")
    private val discoveryDeadline = Runnable {
        if (server != null && peer == null) onError("Retrying Bluetooth discovery.")
    }
    private val transmit = BluetoothGattCharacteristic(Wire.transmit, BluetoothGattCharacteristic.PROPERTY_INDICATE, 0)
    private val receive = BluetoothGattCharacteristic(
        Wire.receive, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE
    )
    // Live call audio uses unacknowledged notifications and writes, one sealed payload each.
    private val audioOut = BluetoothGattCharacteristic(Wire.audioOut, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
    private val audioIn = BluetoothGattCharacteristic(
        Wire.audioIn, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE
    )

    private class AudioDelivery {
        private var firstAt = 0L
        private var lastAt = 0L
        private var largestGap = 0L
        private var largestPacket = 0
        var offered = 0
            private set
        var sent = 0
        var evicted = 0
        var failed = 0

        fun offer(bytes: Int) {
            val now = SystemClock.elapsedRealtime()
            if (offered++ == 0) firstAt = now
            else largestGap = maxOf(largestGap, now - lastAt)
            lastAt = now
            largestPacket = maxOf(largestPacket, bytes)
        }

        override fun toString() = "Audio BLE: offered=$offered sent=$sent evicted=$evicted failed=$failed " +
            "span=${lastAt - firstAt}ms maxOfferGap=${largestGap}ms maxPacket=$largestPacket"
    }

    private data class PendingAudio(val packet: ByteArray, val expires: Long)
    private data class ControlTrace(val log: AudioTestLog, val label: String, val queuedAt: Long)
    private data class ControlFrame(val bytes: ByteArray, val trace: ControlTrace?, val isSms: Boolean = false)

    private class Peer(val device: BluetoothDevice, var mtu: Int) {
        val input = Wire.Reader()
        var subscribed = false
        var audioSubscribed = false
        val capacity get() = (mtu - 3).coerceIn(20, 180)
        var session: Wire.Session? = null
        @Volatile var ready = false
        @Volatile var closing = false
        var canForget = false
        var audioPairs = false
        val urgent = ArrayDeque<ControlFrame>()
        val normal = ArrayDeque<ControlFrame>()
        val audio = ArrayDeque<PendingAudio>()
        var queuedBytes = 0
        var output: ByteArray? = null
        var outputTrace: ControlTrace? = null
        var outputIsSms = false
        var submittedAt = 0L
        var offset = 0
        // Android allows one outstanding notification or indication, so audio and control share this slot.
        var inFlight = false
        var audioInFlight = false
        var audioTrace: AudioTestLog? = null
        var audioSequence = -1L
        var retryingSend = false
        var busyRetries = 0
        var serial = 0
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            handler.post {
                if (server == null || !advertising || peer != null) return@post
                handler.removeCallbacks(discoveryDeadline)
                onAdvertising()
            }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
                if (server == null || !advertising || peer != null) return@post
                advertising = false
                onError("Bluetooth discovery failed ($errorCode).")
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (server == null) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) advertise()
                else onError("The Bluetooth service did not open.")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                val active = server ?: return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (peer != null) {
                        if (peer?.device != device) active.cancelConnection(device)
                        return@post
                    }
                    val sinceOpen = SystemClock.elapsedRealtime() - openedAt
                    if (device in carriedLinks || (!advertising && sinceOpen < CARRIED_LINK_MS)) {
                        // The Bluetooth link outlived the previous server (other services keep it up), so Android
                        // attaches it at once. The iPhone keeps its old session until it talks again over this link
                        // or the link drops; restarting the server would only attach it again.
                        trace("Existing Bluetooth link attached ${sinceOpen}ms after server open; waiting for the receiver")
                        return@post
                    }
                    adopt(device, "Gatt connected ${sinceOpen}ms after server open")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    carriedLinks.remove(device)
                    // Keep the MTU: this callback also follows our own cancelConnection while the receiver keeps the
                    // link up, and it won't renegotiate on that link. A new link negotiates and replaces it.
                    if (peer?.device != device) return@post
                    trace("Gatt closed by stack, status=$status")
                    testLog()?.add("BLE disconnected: status=$status")
                    peer?.let { clearAudio(it) }
                    peer = null
                    setAudioActive(false)
                    onDisconnect()
                    advertise()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                if (server == null || mtu !in 23..517) return@post
                linkMtu[device] = mtu
                peer?.takeIf { it.device == device }?.let {
                    it.mtu = mtu
                    if (it.ready) rememberMtu(it)
                    testLog()?.add("BLE MTU changed: mtu=${it.mtu} controlChunk=${it.capacity}")
                }
            }
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            handler.post {
                if (peer?.device == device) testLog()?.add("BLE PHY update: tx=$txPhy rx=$rxPhy status=$status")
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                             descriptor: BluetoothGattDescriptor) {
            handler.post {
                val connection = peer
                val characteristic = descriptor.characteristic?.uuid
                val audio = characteristic == Wire.audioOut
                val value = when {
                    connection?.device == device && audio && connection.audioSubscribed -> byteArrayOf(1, 0)
                    connection?.device == device && !audio && connection.subscribed -> byteArrayOf(2, 0)
                    else -> byteArrayOf(0, 0)
                }
                val valid = (connection == null || connection.device == device) &&
                    (audio || characteristic == Wire.transmit) && descriptor.uuid == Wire.cccd && offset in 0..value.size
                server?.sendResponse(device, requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset, if (valid) value.copyOfRange(offset, value.size) else null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val input = value.copyOf()
            handler.post {
                val characteristic = descriptor.characteristic?.uuid
                val audio = characteristic == Wire.audioOut
                val enabled = input.contentEquals(if (audio) byteArrayOf(1, 0) else byteArrayOf(2, 0))
                val subscription = (audio || characteristic == Wire.transmit) &&
                    !preparedWrite && offset == 0 && descriptor.uuid == Wire.cccd
                val connection = peer?.takeIf { it.device == device }
                    ?: if (peer == null && server != null && subscription && enabled) {
                        adopt(device, "Gatt subscription on an existing Bluetooth link")
                    } else null
                val valid = connection != null && subscription &&
                    (enabled || input.contentEquals(byteArrayOf(0, 0)))
                val acknowledged = !responseNeeded || server?.sendResponse(device, requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null) == true
                if (valid) {
                    if (!acknowledged) {
                        disconnect(connection, "Gatt subscription acknowledgement failed")
                        return@post
                    }
                    if (audio) {
                        connection.audioSubscribed = enabled
                        if (!enabled) clearAudio(connection)
                        trace("Gatt audio subscription enabled=$enabled")
                        testLog()?.add("BLE audio subscription: enabled=$enabled")
                        return@post
                    }
                    connection.subscribed = enabled
                    trace("Gatt subscription enabled=$enabled")
                    if (!enabled && connection.session != null) disconnect(connection, "Peer unsubscribed")
                }
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
                characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray) {
            val callbackAt = SystemClock.elapsedRealtime()
            val input = value.copyOf()
            if (characteristic.uuid == Wire.audioIn) {
                // Audio skips the main thread. Packets that arrive before authentication are ignored.
                val connection = peer
                if (audioActive && connection?.device == device && connection.ready && !connection.closing &&
                    !preparedWrite && offset == 0) onAudio(input)
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                return
            }
            handler.post {
                val connection = peer?.takeIf { it.device == device }
                val valid = connection?.subscribed == true && !preparedWrite && responseNeeded &&
                    offset == 0 && characteristic.uuid == Wire.receive && input.size in 1..180
                if (!valid) {
                    if (connection != null && !connection.ready) {
                        trace("Gatt handshake write rejected, subscribed=${connection.subscribed}, prepared=$preparedWrite, bytes=${input.size}")
                    }
                    if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    return@post
                }
                val acknowledged = server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                if (acknowledged != true) {
                    disconnect(connection, "Gatt write acknowledgement failed")
                    return@post
                }
                try {
                    connection.input.feed(input) { body ->
                        require(peer === connection)
                        receiveFrame(connection, body, callbackAt)
                    }
                } catch (error: Exception) {
                    disconnect(connection, "Incoming frame failed: ${error.javaClass.simpleName}")
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            handler.post { server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null) }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val callbackAt = SystemClock.elapsedRealtime()
            handler.post {
                val connection = peer?.takeIf { it.device == device && it.inFlight } ?: return@post
                connection.inFlight = false
                if (connection.audioInFlight) {
                    connection.audioInFlight = false
                    audioDelivery?.let { if (status == BluetoothGatt.GATT_SUCCESS) it.sent++ else it.failed++ }
                    val log = connection.audioTrace
                    connection.audioTrace = null
                    val metric = if (status == BluetoothGatt.GATT_SUCCESS) AudioTestMetric.DOWNLINK_NOTIFY_COMPLETED
                        else AudioTestMetric.DOWNLINK_NOTIFY_FAILED
                    log?.track(metric, connection.audioSequence)
                    if (log?.count(metric) == 1) {
                        log.add("BLE first audio notification callback: status=$status gatt=${callbackAt - connection.submittedAt}ms handler=${SystemClock.elapsedRealtime() - callbackAt}ms")
                    }
                    pump(connection, afterAudio = true)
                    return@post
                }
                connection.outputTrace?.let { (log, label) ->
                    log.add("BLE TX $label indication callback: offset=${connection.offset}/${connection.output?.size} status=$status gatt=${callbackAt - connection.submittedAt}ms handler=${SystemClock.elapsedRealtime() - callbackAt}ms")
                }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (connection.offset == connection.output?.size) {
                        connection.output = null
                        connection.outputTrace = null
                    }
                    pump(connection)
                } else disconnect(connection, "Gatt indication completion failed, status=$status")
            }
        }
    }

    fun start(context: Context) {
        check(advertiser != null)
        val service = BluetoothGattService(Wire.service, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        transmit.addDescriptor(BluetoothGattDescriptor(Wire.cccd,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        audioOut.addDescriptor(BluetoothGattDescriptor(Wire.cccd,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(receive)
        service.addCharacteristic(transmit)
        service.addCharacteristic(audioIn)
        service.addCharacteristic(audioOut)
        carriedLinks.addAll(runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrDefault(emptyList()))
        openedAt = SystemClock.elapsedRealtime()
        server = manager.openGattServer(context, callback)
        check(server?.addService(service) == true)
        handler.postDelayed(discoveryDeadline, DISCOVERY_TIMEOUT_MS)
    }

    fun stop() {
        stopAdvertising()
        val old = peer
        old?.let { clearAudio(it) }
        peer = null
        setAudioActive(false)
        old?.let { runCatching { server?.cancelConnection(it.device) } }
        runCatching { server?.close() }
        server = null
    }

    fun send(message: JSONObject, log: AudioTestLog? = testLog()): Boolean =
        enqueue(listOf(message), highPriority = true, log = log)

    fun describeTestLink(log: AudioTestLog) {
        val connection = peer ?: run {
            log.add("BLE link: no peer")
            return
        }
        log.add("BLE link: ready=${connection.ready} MTU=${connection.mtu} controlChunk=${connection.capacity} bytes audioSubscribed=${connection.audioSubscribed} queuedHigh=${connection.urgent.size} queuedBulk=${connection.normal.size}")
    }

    fun audioReady() = peer?.let { it.ready && it.audioSubscribed } == true

    fun supportsAudioPairs() = peer?.audioPairs == true

    fun audioPayloadLimit() = peer?.mtu?.let { (minOf(it - 3, 512) - Wire.AudioSeal.OVERHEAD).coerceAtLeast(0) } ?: 0

    fun sendForget(message: JSONObject): Boolean {
        val connection = peer?.takeIf { it.ready && it.canForget } ?: return false
        connection.closing = true
        connection.urgent.clear()
        connection.normal.clear()
        clearAudio(connection)
        val encoded = message.toString().toByteArray()
        connection.urgent.add(ControlFrame(encoded, null))
        connection.queuedBytes = encoded.size
        pump(connection)
        return peer === connection
    }

    fun clearBulk() {
        val connection = peer ?: return
        connection.queuedBytes -= connection.normal.sumOf { it.bytes.size }
        connection.normal.clear()
    }

    /** Queues one sealed audio packet. Stale packets are dropped rather than delayed. */
    fun sendAudio(packet: ByteArray, expires: Long = SystemClock.elapsedRealtime() + AudioBridge.LIVE_QUEUE_MS): Boolean {
        if (!audioActive) return false
        val connection = peer?.takeIf { it.ready && !it.closing && it.audioSubscribed } ?: return false
        audioDelivery?.offer(packet.size)
        if (packet.size > connection.mtu - 3) {
            audioDelivery?.let { it.failed++ }
            return false
        }
        while (connection.audio.size >= AudioBridge.LIVE_QUEUE_FRAMES) {
            connection.audio.pollFirst()
            audioDelivery?.let { it.evicted++ }
            testLog()?.count(AudioTestMetric.DOWNLINK_QUEUE_DROPPED)
        }
        connection.audio.addLast(PendingAudio(packet, expires))
        pump(connection)
        return true
    }

    /** Holds back bulk sync frames while a call streams audio, since each indication blocks the shared slot. */
    @SuppressLint("WakelockTimeout")
    fun setAudioActive(active: Boolean) {
        if (debug && active != audioActive) {
            if (!active) audioDelivery?.let { trace("$it") }
            audioDelivery = if (active) AudioDelivery() else null
        }
        audioActive = active
        if (active && !audioWakeLock.isHeld) audioWakeLock.acquire()
        if (!active && audioWakeLock.isHeld) audioWakeLock.release()
        peer?.let {
            if (!active) clearAudio(it)
            pump(it)
        }
    }

    private fun clearAudio(connection: Peer) {
        testLog()?.count(AudioTestMetric.DOWNLINK_QUEUE_DROPPED, connection.audio.size)
        connection.audio.clear()
    }

    fun enqueue(messages: List<JSONObject>, highPriority: Boolean = false, log: AudioTestLog? = null): Boolean {
        val encoded = messages.map { message ->
            val trace = log?.controlLabel(message)?.let { ControlTrace(log, it, SystemClock.elapsedRealtime()) }
            ControlFrame(message.toString().toByteArray(), trace, message.optString("type") == "sms")
        }
        val connection = peer?.takeIf { it.ready && !it.closing }
        if (connection == null) {
            encoded.forEach { it.trace?.let { (traceLog, label) -> traceLog.add("BLE TX $label rejected: no ready peer") } }
            return false
        }
        val bytes = encoded.sumOf { it.bytes.size }
        if (connection.queuedBytes + bytes > MAX_QUEUE || encoded.any { it.bytes.size > Wire.MAX_FRAME - 25 }) {
            encoded.forEach { it.trace?.let { (traceLog, label) -> traceLog.add("BLE TX $label rejected: frame or queue limit") } }
            return false
        }
        val queue = if (highPriority) connection.urgent else connection.normal
        if (!highPriority) trace("Queueing sync: ${encoded.size} frames, $bytes bytes")
        encoded.forEach { frame -> frame.trace?.let { (traceLog, label) ->
            val inFlight = when {
                connection.audioInFlight -> "audio"
                connection.output != null -> connection.outputTrace?.label ?: "other control"
                else -> "none"
            }
            traceLog.add("BLE TX $label queued: ahead=${queue.size} inFlight=$inFlight controlChunk=${connection.capacity}")
        } }
        queue.addAll(encoded)
        connection.queuedBytes += bytes
        pump(connection)
        return peer === connection
    }

    private fun receiveFrame(connection: Peer, body: ByteArray, callbackAt: Long) {
        val session = connection.session
        if (session == null) {
            trace("Received encrypted-link setup frame")
            val (reply, derived) = Wire.makeHello(key(), body)
            connection.session = derived
            connection.output = Wire.frame(reply)
            connection.offset = 0
            pump(connection)
            return
        }

        val message = JSONObject(Wire.decode(session.open(body)))
        if (!connection.ready) {
            connection.canForget = message.optString("unpair") == "1"
            connection.audioPairs = message.optString("audio_pairs") == "1"
            val next = session.next.takeIf { message.optString("rekey") == "1" }
            require(message.optString("type") == "ready" && onReady(next))
            connection.ready = true
            rememberMtu(connection)
            trace("Secure link authenticated")
            if (manager.adapter?.isLe2MPhySupported == true) {
                // A faster PHY reduces airtime for the same audio packet when both phones support it.
                runCatching {
                    server?.setPreferredPhy(connection.device, BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                }
            }
            check(send(JSONObject().put("type", "ready")))
            return
        }
        val log = testLog()
        log?.controlLabel(message)?.let {
            log.add("BLE RX $it authenticated: final-fragment handler/parse=${SystemClock.elapsedRealtime() - callbackAt}ms")
        }
        onRequest(message)
    }

    private fun adopt(device: BluetoothDevice, how: String): Peer {
        stopAdvertising()
        val connection = Peer(device, initialLinkMtu(linkMtu[device], savedMtu(device), device in carriedLinks))
        peer = connection
        trace("$how, link MTU=${connection.mtu}")
        testLog()?.add("BLE GATT connected")
        handler.postDelayed({
            if (peer === connection && !connection.ready) handshakeTimedOut(connection)
        }, HANDSHAKE_TIMEOUT_MS)
        return connection
    }

    private fun rememberMtu(connection: Peer) {
        // Only save negotiated MTUs from authenticated devices, for a link that survives the relay process.
        val mtu = linkMtu[connection.device] ?: return
        if (savedMtu(connection.device) != mtu) mtuStore.edit().putInt(connection.device.address, mtu).apply()
    }

    private fun savedMtu(device: BluetoothDevice) = mtuStore.getInt(device.address, 0).takeIf { it in 23..517 }

    private fun handshakeTimedOut(connection: Peer) {
        if (connection.session != null) {
            disconnect(connection, "Handshake timed out, subscribed=${connection.subscribed}, hello=${connection.session != null}")
            return
        }
        // A subscription without a hello can be left over from the previous server.
        trace("Handshake timed out with no activity; keeping the Bluetooth server open")
        testLog()?.add("BLE idle link released")
        clearAudio(connection)
        peer = null
        runCatching { server?.cancelConnection(connection.device) }
        advertise()
    }

    private fun advertise() {
        if (advertising || server == null || peer != null) return
        advertising = true
        handler.removeCallbacks(discoveryDeadline)
        handler.postDelayed(discoveryDeadline, DISCOVERY_TIMEOUT_MS)
        try {
            advertiser?.startAdvertising(
                AdvertiseSettings.Builder().setConnectable(true)
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build(),
                AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(Wire.service)).build(),
                advertiseCallback
            )
        } catch (_: SecurityException) {
            advertising = false
            onError("Bluetooth permission is unavailable.")
        }
    }

    private fun stopAdvertising() {
        handler.removeCallbacks(discoveryDeadline)
        if (!advertising) return
        advertising = false
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
    }

    private fun disconnect(connection: Peer, reason: String) {
        if (peer !== connection) return
        trace(reason)
        (connection.outputTrace?.log ?: testLog())?.add("BLE reconnect: $reason")
        // A fresh GATT server fences callbacks from the failed connection.
        onError("Reconnecting Bluetooth.")
    }

    private fun pump(connection: Peer, retryAudio: Boolean = true, afterAudio: Boolean = false) {
        if (peer !== connection || connection.inFlight || connection.retryingSend) return
        val controlIsSms = if (connection.output != null) connection.outputIsSms else connection.urgent.peekFirst()?.isSms
        // Audio uses a separate characteristic and can go between SMS fragments.
        if (connection.audioSubscribed && audioBeforeControl(controlIsSms, afterAudio, connection.audio.size)) {
            while (true) {
                val audio = connection.audio.pollFirst() ?: break
                if (SystemClock.elapsedRealtime() >= audio.expires) {
                    audioDelivery?.let { it.evicted++ }
                    testLog()?.count(AudioTestMetric.DOWNLINK_QUEUE_DROPPED)
                    continue
                }
                connection.submittedAt = SystemClock.elapsedRealtime()
                val status = notify(connection.device, audioOut, audio.packet, confirm = false)
                if (status == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && retryAudio) {
                    connection.audio.addFirst(audio)
                    connection.retryingSend = true
                    handler.postDelayed({
                        connection.retryingSend = false
                        pump(connection, retryAudio = false)
                    }, 8)
                    return
                }
                if (status != BluetoothStatusCodes.SUCCESS) {
                    audioDelivery?.let { it.failed++ }
                    val log = testLog()
                    if (log?.count(AudioTestMetric.DOWNLINK_NOTIFY_FAILED) == 1) {
                        log.add("BLE audio notification submit failed: status=$status")
                    }
                    continue
                }
                connection.inFlight = true
                connection.audioInFlight = true
                val log = testLog()
                connection.audioTrace = log
                if (log != null) {
                    connection.audioSequence = Wire.AudioSeal.sequence(audio.packet)
                    log.track(AudioTestMetric.DOWNLINK_NOTIFY_SUBMITTED, connection.audioSequence)
                }
                if (log?.count(AudioTestMetric.DOWNLINK_NOTIFY_SUBMITTED) == 1) {
                    log.add("BLE first audio notification submitted (${audio.packet.size} bytes)")
                }
                watch(connection, "Gatt audio notification timed out")
                return
            }
        }
        if (!connection.subscribed) return
        try {
            if (connection.output == null) {
                val next = connection.urgent.pollFirst() ?:
                    (if (audioActive) null else connection.normal.pollFirst()) ?: return
                connection.queuedBytes -= next.bytes.size
                connection.outputTrace = next.trace
                connection.outputIsSms = next.isSms
                connection.output = Wire.frame(checkNotNull(connection.session).seal(next.bytes))
                connection.offset = 0
            }
            val packet = checkNotNull(connection.output)
            val end = minOf(packet.size, connection.offset + connection.capacity)
            val payload = packet.copyOfRange(connection.offset, end)
            connection.submittedAt = SystemClock.elapsedRealtime()
            val status = notify(connection.device, transmit, payload, confirm = true)
            connection.outputTrace?.let { (log, label, queuedAt) ->
                log.add("BLE TX $label indication submit: offset=${connection.offset}..$end/${packet.size} status=$status queued=${connection.submittedAt - queuedAt}ms")
            }
            if (status == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && connection.busyRetries < 4) {
                connection.busyRetries++
                connection.retryingSend = true
                handler.postDelayed({
                    connection.retryingSend = false
                    pump(connection, afterAudio = afterAudio)
                }, 40)
                return
            }
            if (status != BluetoothStatusCodes.SUCCESS) {
                disconnect(connection, "Gatt indication submit failed at offset ${connection.offset}")
                return
            }
            connection.busyRetries = 0
            connection.offset = end
            connection.inFlight = true
            watch(connection, "Gatt indication timed out at offset $end")
        } catch (error: Exception) {
            disconnect(connection, "Outgoing frame failed: ${error.javaClass.simpleName}")
        }
    }

    private fun watch(connection: Peer, reason: String) {
        val serial = ++connection.serial
        handler.postDelayed({
            if (peer === connection && connection.inFlight && connection.serial == serial) disconnect(connection, reason)
        }, 5_000)
    }

    @Suppress("DEPRECATION")
    private fun notify(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray,
                       confirm: Boolean): Int {
        val active = server ?: return BluetoothStatusCodes.ERROR_UNKNOWN
        if (Build.VERSION.SDK_INT >= 33) {
            return active.notifyCharacteristicChanged(device, characteristic, confirm, value)
        }
        characteristic.value = value
        return if (active.notifyCharacteristicChanged(device, characteristic, confirm)) BluetoothStatusCodes.SUCCESS
        else BluetoothStatusCodes.ERROR_UNKNOWN
    }

    // Connection-level events only, so release builds log them too for audio reports and device diagnostics.
    private fun trace(message: String) {
        Log.i("EzcallRelay", message)
    }

    private companion object {
        const val MAX_QUEUE = 5 * 1024 * 1024
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
        const val CARRIED_LINK_MS = 1_000L
    }
}
