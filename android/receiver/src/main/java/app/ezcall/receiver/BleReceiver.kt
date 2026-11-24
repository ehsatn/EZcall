package app.ezcall.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock
import app.ezcall.android.Wire
import org.json.JSONObject

@SuppressLint("MissingPermission") // The service checks Nearby devices permission before starting.
internal class BleReceiver(
    private val context: Context,
    private val handler: Handler,
    private var secret: ByteArray,
    private var candidate: ByteArray?,
    /** Stores the key that worked and the next one. Returns false to keep the current key. */
    private val rekey: (used: ByteArray, next: ByteArray) -> Boolean,
    private val status: (String) -> Unit,
    private val message: (JSONObject) -> Unit,
    private val audio: (ByteArray) -> Unit,
    private val disconnected: () -> Unit
) {
    private var active = false
    private var scanner: BluetoothLeScanner? = null
    private var scan: ScanCallback? = null
    private var peer: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var audioRx: BluetoothGattCharacteristic? = null
    private var reader = Wire.Reader()
    private var hello: Wire.CentralHello? = null
    private var session: Wire.Session? = null
    private var ready = false
    private var discovering = false
    private var maxWrite = 20
    private val frames = ArrayDeque<ByteArray>()
    private val microphonePackets = ArrayDeque<ByteArray>()
    private var frameOffset = 0
    private var writing: BluetoothGattCharacteristic? = null
    private var lastScan = -6_500L
    private val tried = mutableMapOf<String, Long>()
    private val search = Runnable { startScan() }
    private val scanTimeout = Runnable {
        stopScan()
        startScan()
    }
    private val setupTimeout = Runnable { retry("The relay did not respond.") }
    private val writeTimeout = Runnable { retry("The Bluetooth write timed out.") }

    fun start() {
        active = true
        startScan()
    }

    fun stop() {
        active = false
        handler.removeCallbacks(search)
        stopScan()
        closePeer()
    }

    fun send(body: JSONObject): Boolean {
        if (!ready || frames.size >= 24) return false
        return guarded {
            enqueue(checkNotNull(session).seal(body.toString().toByteArray()))
        }
    }

    fun sendAudio(packet: ByteArray) {
        if (!ready || audioRx == null || packet.size > maxWrite) return
        // Keep up to 60 ms of speech across bursts of GATT write callbacks.
        if (microphonePackets.size == 3) microphonePackets.removeFirst()
        microphonePackets.addLast(packet)
        if (writing == null && frames.isEmpty()) writeNextMicrophonePacket()
    }

    fun clearMicrophonePackets() = microphonePackets.clear()

    private fun writeNextMicrophonePacket() {
        val packet = microphonePackets.removeFirstOrNull() ?: return
        val target = audioRx ?: return
        val gatt = peer ?: return
        if (runCatching { write(gatt, target, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }.getOrDefault(false)) {
            writing = target
            handler.postDelayed(writeTimeout, 5_000)
        }
    }

    private fun startScan() {
        if (!active) return
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val current = runCatching { adapter?.bluetoothLeScanner }.getOrNull()
        if (current == null) {
            status("Turn on Bluetooth to receive calls and messages.")
            handler.postDelayed(search, 6_500)
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post {
                    if (scan !== this || peer != null) return@post
                    val now = SystemClock.elapsedRealtime()
                    tried.entries.removeAll { now - it.value >= 18_000 }
                    if (result.device.address in tried) return@post
                    tried[result.device.address] = now
                    connect(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post { if (scan === this) retry("Bluetooth scan failed. Trying again.") }
            }
        }
        scanner = current
        scan = callback
        lastScan = SystemClock.elapsedRealtime()
        status("Looking for the sending phone…")
        guarded {
            current.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Wire.service)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                callback
            )
            handler.postDelayed(scanTimeout, 45_000)
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        scan?.let { runCatching { scanner?.stopScan(it) } }
        scanner = null
        scan = null
    }

    private fun connect(device: BluetoothDevice) {
        stopScan()
        status("Connecting to the sending phone…")
        guarded {
            peer = checkNotNull(device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE))
            handler.postDelayed(setupTimeout, 15_000)
        }
    }

    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = dispatch(gatt) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                retry("Bluetooth disconnected. Reconnecting…")
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!gatt.requestMtu(183)) discover(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = dispatch(gatt) {
            if (status == BluetoothGatt.GATT_SUCCESS) maxWrite = (mtu - 3).coerceIn(20, 180)
            discover(gatt)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = dispatch(gatt) {
            check(status == BluetoothGatt.GATT_SUCCESS)
            val service = checkNotNull(gatt.getService(Wire.service))
            rx = checkNotNull(service.getCharacteristic(Wire.receive))
            val audioOut = service.getCharacteristic(Wire.audioOut)
            if (maxWrite >= 100 && audioOut != null) {
                audioRx = service.getCharacteristic(Wire.audioIn)
                subscribe(gatt, audioOut, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                subscribeControl(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = dispatch(gatt) {
            if (descriptor.characteristic.uuid == Wire.audioOut) {
                if (status != BluetoothGatt.GATT_SUCCESS) audioRx = null
                subscribeControl(gatt)
            } else {
                check(status == BluetoothGatt.GATT_SUCCESS && descriptor.characteristic.uuid == Wire.transmit)
                this@BleReceiver.status("Securing the Bluetooth link…")
                val handshake = Wire.CentralHello()
                hello = handshake
                enqueue(handshake.request)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            dispatch(gatt) {
                if (writing?.uuid != characteristic.uuid) return@dispatch
                handler.removeCallbacks(writeTimeout)
                writing = null
                if (characteristic.uuid == Wire.receive) {
                    check(status == BluetoothGatt.GATT_SUCCESS)
                    if (frameOffset == frames.first().size) {
                        frames.removeFirst()
                        frameOffset = 0
                    }
                }
                pump()
            }

        @Deprecated("Required for Android 12")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.copyOf()?.let { onCharacteristicChanged(gatt, characteristic, it) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            dispatch(gatt) {
                when (characteristic.uuid) {
                    Wire.transmit -> reader.feed(value, ::receive)
                    Wire.audioOut -> if (ready) audio(value)
                }
            }
    }

    private fun discover(gatt: BluetoothGatt) {
        if (discovering) return
        discovering = true
        check(gatt.discoverServices())
    }

    private fun dispatch(gatt: BluetoothGatt, action: () -> Unit) {
        handler.post { if (peer === gatt) guarded(action) }
    }

    private fun subscribeControl(gatt: BluetoothGatt) {
        val tx = checkNotNull(gatt.getService(Wire.service)?.getCharacteristic(Wire.transmit))
        subscribe(gatt, tx, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
    }

    @Suppress("DEPRECATION")
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val descriptor = checkNotNull(characteristic.getDescriptor(Wire.cccd))
        check(gatt.setCharacteristicNotification(characteristic, true))
        check(if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        })
    }

    @Suppress("DEPRECATION")
    private fun write(gatt: BluetoothGatt, target: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(target, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            target.writeType = type
            target.value = value
            gatt.writeCharacteristic(target)
        }

    private fun enqueue(body: ByteArray) {
        frames.addLast(Wire.frame(body))
        pump()
    }

    private fun pump() {
        if (writing != null) return
        if (frames.isEmpty()) return writeNextMicrophonePacket()
        val frame = frames.first()
        val target = checkNotNull(rx)
        val end = minOf(frame.size, frameOffset + maxWrite)
        check(write(checkNotNull(peer), target, frame.copyOfRange(frameOffset, end), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
        frameOffset = end
        writing = target
        handler.postDelayed(writeTimeout, 5_000)
    }

    private fun receive(body: ByteArray) {
        val handshake = hello
        if (handshake != null) {
            // Android may still use the old key if it missed the last `ready`, so try both.
            val (used, keys) = listOfNotNull(secret, candidate).firstNotNullOfOrNull { psk ->
                runCatching { psk to handshake.accept(psk, body) }.getOrNull()
            } ?: throw IllegalArgumentException("Android proof failed")
            hello = null
            session = keys
            val rotated = rekey(used, keys.next)
            if (rotated) {
                secret = used
                candidate = keys.next
            }
            val ready = if (rotated) """{"type":"ready","unpair":"1","rekey":"1"}""" else """{"type":"ready","unpair":"1"}"""
            enqueue(keys.seal(ready.toByteArray()))
            return
        }
        val envelope = JSONObject(Wire.decode(checkNotNull(session).open(body)))
        if (!ready) {
            require(envelope.getString("type") == "ready")
            handler.removeCallbacks(setupTimeout)
            // A verified sender can reconnect immediately inside the call recovery window.
            tried.remove(peer?.device?.address)
            ready = true
        }
        message(envelope)
    }

    private fun retry(detail: String) {
        stopScan()
        closePeer()
        disconnected()
        status(detail)
        if (active) {
            handler.removeCallbacks(search)
            handler.postDelayed(search, maxOf(2_000L, lastScan + 6_500 - SystemClock.elapsedRealtime()))
        }
    }

    private fun closePeer() {
        handler.removeCallbacks(setupTimeout)
        handler.removeCallbacks(writeTimeout)
        val old = peer
        peer = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
        rx = null
        audioRx = null
        reader = Wire.Reader()
        hello = null
        session = null
        ready = false
        discovering = false
        maxWrite = 20
        frames.clear()
        clearMicrophonePackets()
        frameOffset = 0
        writing = null
    }

    private fun guarded(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (_: Exception) {
        retry("Could not establish a secure Bluetooth link. Check the pairing code and try again.")
        false
    }
}
