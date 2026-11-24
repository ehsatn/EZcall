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
import android.util.Log
import org.json.JSONObject
import java.util.ArrayDeque

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
    private val onError: (String) -> Unit
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val advertiser = manager.adapter?.bluetoothLeAdvertiser
    private val debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var server: BluetoothGattServer? = null
    private var advertising = false
    @Volatile private var peer: Peer? = null
    @Volatile private var audioActive = false
    private val audioWakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:call-audio")
    private val discoveryDeadline = Runnable {
        if (server != null && peer == null) onError("Retrying Bluetooth discovery.")
    }
    private val transmit = BluetoothGattCharacteristic(Wire.transmit, BluetoothGattCharacteristic.PROPERTY_INDICATE, 0)
    private val receive = BluetoothGattCharacteristic(
        Wire.receive, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE
    )
    // Live call audio uses unacknowledged notifications and writes, one sealed Opus packet each.
    private val audioOut = BluetoothGattCharacteristic(Wire.audioOut, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
    private val audioIn = BluetoothGattCharacteristic(
        Wire.audioIn, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE
    )

    private class Peer(val device: BluetoothDevice) {
        val input = Wire.Reader()
        var subscribed = false
        var audioSubscribed = false
        var capacity = 20
        var mtu = 23
        var session: Wire.Session? = null
        @Volatile var ready = false
        @Volatile var closing = false
        var canForget = false
        val urgent = ArrayDeque<ByteArray>()
        val normal = ArrayDeque<ByteArray>()
        val audio = ArrayDeque<ByteArray>()
        var queuedBytes = 0
        var output: ByteArray? = null
        var offset = 0
        // Android allows one outstanding notification or indication, so audio and control share this slot.
        var inFlight = false
        var audioInFlight = false
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
                    stopAdvertising()
                    val connection = Peer(device)
                    peer = connection
                    trace("Gatt connected")
                    handler.postDelayed({
                        if (peer === connection && !connection.ready) {
                            disconnect(connection,
                                "Handshake timed out, subscribed=${connection.subscribed}, hello=${connection.session != null}")
                        }
                    }, HANDSHAKE_TIMEOUT_MS)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && peer?.device == device) {
                    trace("Gatt closed by stack, status=$status")
                    peer = null
                    setAudioActive(false)
                    onDisconnect()
                    advertise()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                peer?.takeIf { it.device == device }?.let {
                    it.capacity = (mtu - 3).coerceIn(20, 180)
                    it.mtu = mtu.coerceIn(23, 517)
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                             descriptor: BluetoothGattDescriptor) {
            handler.post {
                val connection = peer?.takeIf { it.device == device }
                val audio = descriptor.characteristic?.uuid == Wire.audioOut
                val value = when {
                    audio && connection?.audioSubscribed == true -> byteArrayOf(1, 0)
                    !audio && connection?.subscribed == true -> byteArrayOf(2, 0)
                    else -> byteArrayOf(0, 0)
                }
                val valid = connection != null && descriptor.uuid == Wire.cccd && offset in 0..value.size
                server?.sendResponse(device, requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset, if (valid) value.copyOfRange(offset, value.size) else null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val input = value.copyOf()
            handler.post {
                val connection = peer?.takeIf { it.device == device }
                val audio = descriptor.characteristic?.uuid == Wire.audioOut
                val enabled = input.contentEquals(if (audio) byteArrayOf(1, 0) else byteArrayOf(2, 0))
                val valid = connection != null && !preparedWrite && offset == 0 && descriptor.uuid == Wire.cccd &&
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
                        if (!enabled) connection.audio.clear()
                        trace("Gatt audio subscription enabled=$enabled")
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
                        receiveFrame(connection, body)
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
            handler.post {
                val connection = peer?.takeIf { it.device == device && it.inFlight } ?: return@post
                connection.inFlight = false
                if (connection.audioInFlight) {
                    connection.audioInFlight = false
                    pump(connection)
                    return@post
                }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (connection.offset == connection.output?.size) connection.output = null
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
        server = manager.openGattServer(context, callback)
        check(server?.addService(service) == true)
        handler.postDelayed(discoveryDeadline, DISCOVERY_TIMEOUT_MS)
    }

    fun stop() {
        stopAdvertising()
        val old = peer
        peer = null
        setAudioActive(false)
        old?.let { runCatching { server?.cancelConnection(it.device) } }
        runCatching { server?.close() }
        server = null
    }

    fun send(message: JSONObject): Boolean = enqueue(listOf(message), highPriority = true)

    fun audioReady() = peer?.let { it.ready && it.audioSubscribed } == true

    fun sendForget(message: JSONObject): Boolean {
        val connection = peer?.takeIf { it.ready && it.canForget } ?: return false
        connection.closing = true
        connection.urgent.clear()
        connection.normal.clear()
        connection.audio.clear()
        val encoded = message.toString().toByteArray()
        connection.urgent.add(encoded)
        connection.queuedBytes = encoded.size
        pump(connection)
        return peer === connection
    }

    fun clearBulk() {
        val connection = peer ?: return
        connection.queuedBytes -= connection.normal.sumOf { it.size }
        connection.normal.clear()
    }

    /** Queues one sealed audio packet. Stale packets are dropped rather than delayed. */
    fun sendAudio(packet: ByteArray): Boolean {
        if (!audioActive) return false
        val connection = peer?.takeIf { it.ready && !it.closing && it.audioSubscribed } ?: return false
        if (packet.size > connection.mtu - 3) return false
        while (connection.audio.size >= AudioBridge.LIVE_QUEUE_FRAMES) connection.audio.pollFirst()
        connection.audio.addLast(packet)
        pump(connection)
        return true
    }

    /** Holds back bulk sync frames while a call streams audio, since each indication blocks the shared slot. */
    @SuppressLint("WakelockTimeout")
    fun setAudioActive(active: Boolean) {
        audioActive = active
        if (active && !audioWakeLock.isHeld) audioWakeLock.acquire()
        if (!active && audioWakeLock.isHeld) audioWakeLock.release()
        peer?.let {
            if (!active) it.audio.clear()
            pump(it)
        }
    }

    fun enqueue(messages: List<JSONObject>, highPriority: Boolean = false): Boolean {
        val connection = peer?.takeIf { it.ready && !it.closing } ?: return false
        val encoded = messages.map { it.toString().toByteArray() }
        val bytes = encoded.sumOf { it.size }
        if (connection.queuedBytes + bytes > MAX_QUEUE || encoded.any { it.size > Wire.MAX_FRAME - 25 }) return false
        val queue = if (highPriority) connection.urgent else connection.normal
        if (!highPriority) trace("Queueing sync: ${encoded.size} frames, $bytes bytes")
        queue.addAll(encoded)
        connection.queuedBytes += bytes
        pump(connection)
        return peer === connection
    }

    private fun receiveFrame(connection: Peer, body: ByteArray) {
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
            val next = session.next.takeIf { message.optString("rekey") == "1" }
            require(message.optString("type") == "ready" && onReady(next))
            connection.ready = true
            trace("Secure link authenticated")
            check(send(JSONObject().put("type", "ready")))
            return
        }
        onRequest(message)
    }

    private fun advertise() {
        if (advertising || server == null) return
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
        // A fresh GATT server fences callbacks from the failed connection.
        onError("Reconnecting Bluetooth.")
    }

    private fun pump(connection: Peer, retryAudio: Boolean = true) {
        if (peer !== connection || connection.inFlight || connection.retryingSend) return
        if (connection.audioSubscribed && connection.urgent.isEmpty()) {
            while (true) {
                val packet = connection.audio.pollFirst() ?: break
                val status = notify(connection.device, audioOut, packet, confirm = false)
                if (status == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && retryAudio) {
                    connection.audio.addFirst(packet)
                    connection.retryingSend = true
                    handler.postDelayed({
                        connection.retryingSend = false
                        pump(connection, retryAudio = false)
                    }, 8)
                    return
                }
                if (status != BluetoothStatusCodes.SUCCESS) continue
                connection.inFlight = true
                connection.audioInFlight = true
                watch(connection, "Gatt audio notification timed out")
                return
            }
        }
        if (!connection.subscribed) return
        try {
            if (connection.output == null) {
                val next = connection.urgent.pollFirst() ?:
                    (if (audioActive) null else connection.normal.pollFirst()) ?: return
                connection.queuedBytes -= next.size
                connection.output = Wire.frame(checkNotNull(connection.session).seal(next))
                connection.offset = 0
            }
            val packet = checkNotNull(connection.output)
            val end = minOf(packet.size, connection.offset + connection.capacity)
            val payload = packet.copyOfRange(connection.offset, end)
            val status = notify(connection.device, transmit, payload, confirm = true)
            if (status == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && connection.busyRetries < 4) {
                connection.busyRetries++
                connection.retryingSend = true
                handler.postDelayed({
                    connection.retryingSend = false
                    pump(connection)
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

    private fun trace(message: String) {
        if (debug) Log.i("EzcallRelay", message)
    }

    private companion object {
        const val MAX_QUEUE = 5 * 1024 * 1024
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
    }
}
