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
import android.util.Log
import app.ezcall.android.AudioTestLog
import app.ezcall.android.AudioTestMetric
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
    private val disconnected: () -> Unit,
    private val testLog: () -> AudioTestLog?
) {
    private data class Frame(val bytes: ByteArray, val diagnostic: String?, val queuedAt: Long)
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
    private var requestedPriority: Int? = null
    private var maxWrite = 20
    private var maxAudioWrite = 20
    private val frames = ArrayDeque<Frame>()
    private val microphonePackets = ArrayDeque<ByteArray>()
    private var frameOffset = 0
    private var writing: BluetoothGattCharacteristic? = null
    private var writeStarted = 0L
    private var microphoneSequence = -1L
    private var lastScan = -6_500L
    private val tried = mutableMapOf<String, Long>()
    private val search = Runnable { startScan() }
    private val scanTimeout = Runnable {
        stopScan()
        startScan()
    }
    private val setupTimeout = Runnable { retry("The relay did not respond.") }
    private val writeTimeout = Runnable {
        val channel = if (writing?.uuid == Wire.audioIn) "audio" else "control"
        val detail = "GATT $channel write timed out; frame=${frames.firstOrNull()?.diagnostic ?: "other"}, queuedControl=${frames.size}"
        Log.w("EzcallReceiver", detail)
        retry("The Bluetooth write timed out.", detail)
    }

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
        val log = testLog()
        val label = log?.controlLabel(body)
        if (!ready || frames.size >= 24) {
            label?.let { log?.add("BLE TX $it rejected: ready=$ready queuedControl=${frames.size}") }
            return false
        }
        label?.let { log?.add("BLE TX $it queued: ahead=${frames.size} inFlight=${writeChannel()} controlChunk=$maxWrite") }
        return guarded {
            enqueue(checkNotNull(session).seal(body.toString().toByteArray()), label)
        }
    }

    fun sendAudio(packet: ByteArray): Boolean {
        if (!ready || audioRx == null || packet.size > maxAudioWrite) return false
        // Keep up to 60 ms of speech across bursts of GATT write callbacks.
        if (microphonePackets.size == 3) {
            microphonePackets.removeFirst()
            testLog()?.count(AudioTestMetric.UPLINK_QUEUE_DROPPED)
        }
        microphonePackets.addLast(packet)
        if (writing == null && frames.isEmpty()) writeNextMicrophonePacket()
        return ready
    }

    fun clearMicrophonePackets() {
        val log = testLog()
        repeat(microphonePackets.size) { log?.count(AudioTestMetric.UPLINK_QUEUE_DROPPED) }
        microphonePackets.clear()
    }

    fun describeTestLink(log: AudioTestLog) {
        log.add("BLE link: ready=$ready ATT payload=$maxAudioWrite bytes controlChunk=$maxWrite bytes audioWrite=${audioRx != null} inFlight=${writeChannel()}")
    }

    private fun writeChannel() = when (writing?.uuid) {
        Wire.audioIn -> "audio"
        Wire.receive -> "control"
        else -> "none"
    }

    fun setCallPriority(active: Boolean) {
        val gatt = peer ?: return
        val priority = if (active) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        if (priority == requestedPriority) return
        val accepted = runCatching { gatt.requestConnectionPriority(priority) }.getOrDefault(false)
        testLog()?.add("BLE connection priority: requested=${if (active) "high" else "low-power"} accepted=$accepted")
        if (accepted) {
            requestedPriority = priority
            Log.i("EzcallReceiver", if (active) "Requested low-latency Bluetooth for calls" else "Requested low-power Bluetooth")
        }
    }

    fun audioPayloadLimit() = maxAudioWrite - Wire.AudioSeal.OVERHEAD

    private fun writeNextMicrophonePacket() {
        val packet = microphonePackets.removeFirstOrNull() ?: return
        val target = audioRx ?: return
        val gatt = peer ?: return
        val log = testLog()
        writeStarted = SystemClock.elapsedRealtime()
        val status = runCatching { write(gatt, target, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }
            .getOrDefault(BluetoothStatusCodes.ERROR_UNKNOWN)
        if (status == BluetoothStatusCodes.SUCCESS) {
            writing = target
            microphoneSequence = if (log != null) Wire.AudioSeal.sequence(packet) else -1L
            log?.track(AudioTestMetric.UPLINK_WRITE_SUBMITTED, microphoneSequence)
            if (log?.count(AudioTestMetric.UPLINK_WRITE_SUBMITTED) == 1) log.add("BLE first microphone write submitted (${packet.size} bytes)")
            handler.postDelayed(writeTimeout, 5_000)
        } else if (log?.count(AudioTestMetric.UPLINK_WRITE_FAILED) == 1) {
            log.add("BLE microphone write submit failed: status=$status")
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
            testLog()?.add("BLE connection callback: state=$newState status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                retry("Bluetooth disconnected. Reconnecting…")
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                setCallPriority(true)
                if (!gatt.requestMtu(517)) discover(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = dispatch(gatt) {
            testLog()?.add("BLE MTU callback: mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                maxAudioWrite = (mtu - 3).coerceIn(20, 512)
                maxWrite = minOf(maxAudioWrite, 180)
            }
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

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val callbackAt = SystemClock.elapsedRealtime()
            dispatch(gatt) {
                if (writing?.uuid != characteristic.uuid) return@dispatch
                handler.removeCallbacks(writeTimeout)
                writing = null
                val elapsed = callbackAt - writeStarted
                val handlerDelay = SystemClock.elapsedRealtime() - callbackAt
                val log = testLog()
                if (characteristic.uuid == Wire.receive) {
                    val frame = frames.first()
                    frame.diagnostic?.let {
                        log?.add("BLE TX $it write callback: offset=$frameOffset/${frame.bytes.size} status=$status gatt=${elapsed}ms handler=${handlerDelay}ms")
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS && frame.diagnostic == null) {
                        log?.add("BLE untracked control write callback failed: status=$status")
                    }
                    check(status == BluetoothGatt.GATT_SUCCESS) { "GATT control write callback status=$status" }
                    if (frameOffset == frame.bytes.size) {
                        frames.removeFirst()
                        frameOffset = 0
                    }
                } else if (status == BluetoothGatt.GATT_SUCCESS) {
                    log?.track(AudioTestMetric.UPLINK_WRITE_COMPLETED, microphoneSequence)
                    if (log?.count(AudioTestMetric.UPLINK_WRITE_COMPLETED) == 1) {
                        log.add("BLE first microphone write callback: gatt=${elapsed}ms handler=${handlerDelay}ms")
                    }
                } else if (log?.count(AudioTestMetric.UPLINK_WRITE_FAILED) == 1) {
                    log.add("BLE microphone write callback failed: status=$status")
                }
                pump()
            }
        }

        @Deprecated("Required for Android 12")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.copyOf()?.let { onCharacteristicChanged(gatt, characteristic, it) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val callbackAt = SystemClock.elapsedRealtime()
            dispatch(gatt) {
                when (characteristic.uuid) {
                    Wire.transmit -> reader.feed(value) { receive(it, callbackAt) }
                    Wire.audioOut -> if (ready) {
                        val log = testLog()
                        if (log?.count(AudioTestMetric.DOWNLINK_RECEIVED) == 1) {
                            log.add("BLE first audio notification: bytes=${value.size} handler=${SystemClock.elapsedRealtime() - callbackAt}ms")
                        }
                        audio(value)
                    }
                }
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
    private fun write(gatt: BluetoothGatt, target: BluetoothGattCharacteristic, value: ByteArray, type: Int): Int =
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(target, value, type)
        } else {
            target.writeType = type
            target.value = value
            if (gatt.writeCharacteristic(target)) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
        }

    private fun enqueue(body: ByteArray, diagnostic: String? = null) {
        frames.addLast(Frame(Wire.frame(body), diagnostic, SystemClock.elapsedRealtime()))
        pump()
    }

    private fun pump() {
        if (writing != null) return
        if (frames.isEmpty()) return writeNextMicrophonePacket()
        val frame = frames.first()
        val target = checkNotNull(rx)
        val end = minOf(frame.bytes.size, frameOffset + maxWrite)
        writeStarted = SystemClock.elapsedRealtime()
        val status = write(checkNotNull(peer), target, frame.bytes.copyOfRange(frameOffset, end), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        frame.diagnostic?.let {
            testLog()?.add("BLE TX $it write submit: offset=$frameOffset..$end/${frame.bytes.size} status=$status queued=${writeStarted - frame.queuedAt}ms")
        }
        if (status != BluetoothStatusCodes.SUCCESS && frame.diagnostic == null) {
            testLog()?.add("BLE untracked control write submit failed: status=$status")
        }
        check(status == BluetoothStatusCodes.SUCCESS) { "GATT control write submit status=$status" }
        frameOffset = end
        writing = target
        handler.postDelayed(writeTimeout, 5_000)
    }

    private fun receive(body: ByteArray, callbackAt: Long) {
        val handlerDelay = SystemClock.elapsedRealtime() - callbackAt
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
            val ready = if (rotated) """{"type":"ready","unpair":"1","rekey":"1","audio_pairs":"1"}"""
                else """{"type":"ready","unpair":"1","audio_pairs":"1"}"""
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
        val log = testLog()
        val label = log?.controlLabel(envelope)
        if (label != null) log?.add("BLE RX $label authenticated: final-fragment handler=${handlerDelay}ms")
        message(envelope)
        if (label == null) testLog()?.let { newLog ->
            if (newLog.controlLabel(envelope) == "call/ringing") {
                newLog.add("BLE RX call/ringing authenticated: final-fragment handler=${handlerDelay}ms")
            }
        }
    }

    private fun retry(detail: String, diagnostic: String = detail) {
        testLog()?.add("BLE reconnect: $diagnostic; inFlight=${writeChannel()} queuedControl=${frames.size}")
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
        requestedPriority = null
        maxWrite = 20
        maxAudioWrite = 20
        frames.clear()
        clearMicrophonePackets()
        frameOffset = 0
        writing = null
    }

    private fun guarded(action: () -> Unit): Boolean = try {
        action()
        true
    } catch (error: Exception) {
        retry("Could not establish a secure Bluetooth link. Check the pairing code and try again.", error.javaClass.simpleName)
        false
    }
}
