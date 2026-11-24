import AVFoundation
import CallKit
import Combine
import CoreBluetooth
import CryptoKit
import Foundation
import OSLog
import UIKit
import UserNotifications

@MainActor
final class RelayController: NSObject, ObservableObject {
    static let shared = RelayController()

    enum Status: Equatable {
        case unlinked, waiting, bluetoothOff, denied, unsupported
        case searching, connecting, authenticating, connected, unavailable

        var title: String {
            switch self {
            case .unlinked: "No phone linked"
            case .waiting: "Starting Bluetooth"
            case .bluetoothOff: "Bluetooth is off"
            case .denied: "Bluetooth access is off"
            case .unsupported: "Bluetooth LE unavailable"
            case .searching: "Looking for Android"
            case .connecting: "Connecting to Android"
            case .authenticating: "Securing the link"
            case .connected: "Connected"
            case .unavailable: "Link unavailable"
            }
        }

        var inProgress: Bool {
            [.waiting, .searching, .connecting, .authenticating].contains(self)
        }
    }

    enum AudioMode: String {
        case off = "none", listen, twoWay = "two_way"

        var title: String {
            switch self {
            case .off: "Not set up on Android"
            case .listen: "Listen only"
            case .twoWay: "Two-way"
            }
        }
    }

    private enum Handshake: Equatable { case idle, serverHello, ready, authenticated }

    private struct Capabilities: Decodable {
        let control: Bool
        let dial: Bool
        let audio: String
        let resume_call: Bool?
        let audio_route: Bool?
        let texts: Bool?
        let contacts: Bool?
        let send_sms: Bool?
    }

    private struct AudioStart: Decodable {
        let id: String
        let key: String
        let uplink: Bool
    }

    private struct CallResumed: Decodable {
        let id: UUID
        let audio: Bool
    }

    private struct Notice: Decodable {
        let id: String
        let message: String?
    }

    private struct SMSResult: Decodable {
        let id: String
        let sent: Bool
        let message: String?
    }

    private struct Header: Decodable {
        let type: String
        let id: String?
    }

    private struct Batch<Row: Decodable>: Decodable {
        let items: [Row]
    }

    private struct StagedSync {
        let id: String
        var started = false
        var bytes = 0
        var messages: [RelaySMS] = []
        var contacts: [RelayContact] = []
        var liveMessages: [RelaySMS] = []
    }

    private let service = CBUUID(string: "1C79EA10-64D7-4F93-94EE-881ACB0D5E74")
    private let rx = CBUUID(string: "1C79EA11-64D7-4F93-94EE-881ACB0D5E74")
    private let tx = CBUUID(string: "1C79EA12-64D7-4F93-94EE-881ACB0D5E74")
    private let audioRx = CBUUID(string: "1C79EA13-64D7-4F93-94EE-881ACB0D5E74")
    private let audioTx = CBUUID(string: "1C79EA14-64D7-4F93-94EE-881ACB0D5E74")
    private let decoder = JSONDecoder()
    private let logger = Logger(subsystem: "app.xcall.relay", category: "Relay")

    @Published private(set) var status = Status.unlinked
    @Published private(set) var isPaired = false
    @Published private(set) var isPairing = false
    @Published private(set) var data = RelayData()
    struct PhoneCall {
        let id: UUID
        let address: String
        var connectedAt: Date?
        var endFailed = false
    }

    @Published private(set) var phoneCall: PhoneCall?
    @Published private(set) var activeCall: RelayCall?
    @Published private(set) var feedback: String?
    @Published private(set) var testResult: String?
    @Published private(set) var syncResult: String?
    @Published private(set) var testing = false
    @Published private(set) var syncing = false
    @Published private(set) var alerts = UNAuthorizationStatus.notDetermined
    @Published private(set) var callControl = false
    @Published private(set) var canDial = false
    @Published private(set) var audioMode = AudioMode.off
    @Published private(set) var callAudioActive = false
    @Published private(set) var callReconnecting = false
    @Published private(set) var microphone = AVAudioApplication.shared.recordPermission
    @Published private(set) var canSendSMS = false
    @Published private(set) var sendingSMS = false
    @Published private(set) var smsStatus: (id: String, sent: Bool, message: String)?
    /// The SMS most recently handed to Android, shown in its conversation until sync returns the real record.
    @Published private(set) var outgoingSMS: OutgoingSMS?
    /// A one-off problem the person should see now, such as a call that couldn't start.
    @Published var notice: String?

    struct OutgoingSMS: Equatable {
        let id: String
        let address: String
        let body: String
        let date: Date
    }

    private var pairing: PairingRecord?
    private var pendingSecret: Data?
    private var namedContacts = [String: String]()
    private var namedSuffixes = [String: String]()
    private var manager: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var restoredPeripheral: CBPeripheral?
    private var resettingSubscriptions = Set<CBUUID>()
    private var writeChannel: CBCharacteristic?
    private var handshake = Handshake.idle
    private var clientKey: P256.KeyAgreement.PrivateKey?
    private var link: SecureLink?
    private var reader = FrameReader()
    private var writes = [Data]()
    private var writing = false
    private var pendingPairDeadline: Timer?
    private var handshakeDeadline: Timer?
    private var retryTimer: Timer?
    private var rejected = [UUID: Date]()
    private var ping: (id: String, started: TimeInterval)?
    private var testDeadline: Timer?
    private var sync: StagedSync?
    private var syncDeadline: Timer?
    private var needsSync = false
    private var initialized = false
    private var vaultRead = false
    private lazy var calls = CallBridge()
    private var audioChannel: CBCharacteristic?
    private var pendingAudioWrites: [(packet: Data, expires: ContinuousClock.Instant)] = []
    private var callAudio: CallAudio?
    private var audioCallID: UUID?
    @Published private(set) var muted = false
    private var canResumeCall = false
    private var supportsAudioRedirect = false
    private var queuedAnswer: UUID?
    private var resumeRequested = false
    private var recoveryDeadline: ContinuousClock.Instant?
    private var recoveryTimer: Timer?
    private var recoveryTask = UIBackgroundTaskIdentifier.invalid
    private var pendingHangUp: (id: UUID, deadline: ContinuousClock.Instant)?
    private var dialDeadlines = [UUID: Timer]()
    private var smsRequest: String?
    private var smsDeadline: Timer?
    private var sharing: (texts: Bool, contacts: Bool)?
    private var acknowledgingUnpair = false

    func start() {
        guard !initialized else { return }
        initialized = true
        calls.relay = self
        restoreVault()
        refreshAlerts()
    }

    func becameActive() {
        start()
        if !vaultRead { restoreVault() }
        refreshAlerts()
        microphone = AVAudioApplication.shared.recordPermission
        if let recoveryDeadline, ContinuousClock.now >= recoveryDeadline { expireCallRecovery() }
        if peripheral?.state == .disconnected {
            peripheral = nil
            clearSession()
        }
        tryConnect()
        if status == .connected && needsSync { syncNow() }
    }

    func pair(with code: String) -> Bool {
        guard vaultRead else {
            feedback = "Unlock this iPhone to read the saved link."
            return false
        }
        guard !isPaired, !isPairing else { return false }
        guard let secret = RelayWire.pairingSecret(code) else {
            feedback = "That code does not belong to ezcall."
            return false
        }
        pendingSecret = secret
        isPairing = true
        rejected.removeAll()
        feedback = nil
        status = .waiting
        pendingPairDeadline = after(65) { relay in
            relay.cancelPairing()
            relay.feedback = "Link timed out. Tap Start pairing on Android and try again."
        }
        prepareBluetooth()
        return true
    }

    func cancelPairing() {
        guard isPairing else { return }
        pendingPairDeadline?.invalidate()
        pendingSecret = nil
        isPairing = false
        rejected.removeAll()
        stopBluetooth()
        feedback = nil
        status = .unlinked
    }

    func forgetPhone(acknowledging id: String? = nil) {
        if id == nil { stopBluetooth() }
        do {
            try LocalVault.removePairing()
            pairing = nil
            isPaired = false
            data = RelayData()
            namedContacts.removeAll()
            namedSuffixes.removeAll()
            outgoingSMS = nil
            let notifications = UNUserNotificationCenter.current()
            notifications.removeAllPendingNotificationRequests()
            notifications.removeAllDeliveredNotifications()
            status = .unlinked
            feedback = nil
            testResult = nil
            syncResult = nil
            if let id {
                acknowledgingUnpair = true
                send(type: "unpair_ack", id: id)
                if acknowledgingUnpair {
                    handshakeDeadline = after(4) { $0.stopBluetooth() }
                }
            }
        } catch {
            stopBluetooth()
            status = .unavailable
            feedback = "Couldn’t remove the link. Unlock this iPhone and try again."
        }
    }

    func testConnection() {
        guard status == .connected, !testing else { return }
        testing = true
        testResult = nil
        let id = UUID().uuidString
        ping = (id, ProcessInfo.processInfo.systemUptime)
        send(type: "ping", id: id)
        guard testing else { return }
        testDeadline = after(10) { relay in
            relay.finishTest("Android didn’t respond. Move the phones closer and try again.")
        }
    }

    func syncNow() {
        guard status == .connected, !callReconnecting, !syncing else { return }
        syncing = true
        syncResult = nil
        let id = UUID().uuidString
        sync = StagedSync(id: id)
        needsSync = false
        send(type: "sync", id: id)
        armSyncDeadline()
    }

    func enableAlerts() {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.refreshAlerts() }
        }
    }

    /// Places a call on Android's SIM through the iOS call interface.
    func dial(_ number: String) {
        let trimmed = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard status == .connected, canDial else {
            report(status == .connected ? "Turn on Call control in ezcall on Android to call from iPhone."
                : "Connect to your Android to make calls.")
            return
        }
        if audioMode == .twoWay, AVAudioApplication.shared.recordPermission == .undetermined {
            requestMicrophone { [weak self] in self?.dial(trimmed) }
            return
        }
        let contact = name(for: trimmed)
        calls.dial(trimmed, name: contact == trimmed ? "" : contact)
    }

    func requestMicrophone(completion: (@MainActor () -> Void)? = nil) {
        microphone = AVAudioApplication.shared.recordPermission
        guard microphone == .undetermined, UIApplication.shared.applicationState == .active else { return }
        AVAudioApplication.requestRecordPermission { _ in
            DispatchQueue.main.async {
                RelayController.shared.microphone = AVAudioApplication.shared.recordPermission
                completion?()
            }
        }
    }

    func sendSMS(to address: String, body: String) -> String? {
        guard status == .connected, canSendSMS, smsRequest == nil else { return nil }
        let id = UUID().uuidString.lowercased()
        smsStatus = nil
        smsRequest = id
        outgoingSMS = OutgoingSMS(id: id, address: address, body: body, date: Date())
        sendingSMS = true
        send(["type": "sms_send", "id": id, "address": address, "body": body])
        if smsRequest != nil {
            smsDeadline = after(35) { relay in
                relay.finishSMS(id, sent: false, message: "Android didn’t confirm the SMS. Check Android before retrying.")
            }
        }
        return id
    }

    private func finishSMS(_ id: String, sent: Bool, message: String) {
        guard id == smsRequest else { return }
        smsDeadline?.invalidate()
        smsDeadline = nil
        smsRequest = nil
        sendingSMS = false
        smsStatus = (id, sent, message)
        if sent {
            needsSync = true
            syncNow()
        }
    }

    // MARK: CallKit actions

    func callAnswer(_ id: UUID) -> Bool {
        guard let call = activeCall, call.state == .ringing, UUID(uuidString: call.id) == id,
              (handshake == .authenticated && (callControl || call.simulated == true)) || callReconnecting else { return false }
        callMuted(false)
        queuedAnswer = id
        if !callReconnecting { sendQueuedAnswer(id) }
        guard handshake == .authenticated || callReconnecting else { return false }
        feedback = nil
        phoneCall = PhoneCall(id: id, address: call.address)
        return true
    }

    private func sendQueuedAnswer(_ id: UUID) {
        guard queuedAnswer == id, calls.isAnswering(id), handshake == .authenticated else { return }
        send(type: "call_answer", id: id.uuidString.lowercased())
        if handshake == .authenticated {
            queuedAnswer = nil
            logger.info("Sent answer request to Android")
        }
    }

    func callHangUp(_ id: UUID) {
        if queuedAnswer == id { queuedAnswer = nil }
        dialDeadlines.removeValue(forKey: id)?.invalidate()
        if callReconnecting, activeCall.flatMap({ UUID(uuidString: $0.id) }) == id {
            if handshake != .authenticated, let recoveryDeadline {
                pendingHangUp = (id, recoveryDeadline)
            }
            finishCallRecovery()
            activeCall = nil
        }
        if audioCallID == id { stopAudio() }
        guard handshake == .authenticated else { return }
        send(["type": "call_end", "id": id.uuidString.lowercased()])
    }

    func callDial(_ id: UUID, number: String) -> Bool {
        guard handshake == .authenticated, canDial else { return false }
        send(["type": "call_dial", "id": id.uuidString.lowercased(), "address": number])
        guard handshake == .authenticated else { return false }
        phoneCall = PhoneCall(id: id, address: number)
        dialDeadlines[id] = after(25) { relay in
            relay.dialDeadlines[id] = nil
            relay.calls.ended(id, reason: .failed)
            relay.report("Android didn’t start the call.")
        }
        return true
    }

    func phoneCallConnected(_ id: UUID, at date: Date) {
        guard phoneCall?.id == id else { return }
        phoneCall?.connectedAt = date
    }

    func phoneCallEnded(_ id: UUID) {
        guard phoneCall?.id == id else { return }
        phoneCall = nil
    }

    func endPhoneCall(_ id: UUID) {
        guard phoneCall?.id == id else { return }
        phoneCall?.endFailed = false
        calls.requestEnd(id)
    }

    func phoneCallEndFailed(_ id: UUID) {
        guard phoneCall?.id == id else { return }
        phoneCall?.endFailed = true
    }

    var canPlayKeypadTones: Bool { !calls.hasOngoingCall }

    var canMutePhoneCall: Bool { callAudioActive && callAudio?.uplink == true }

    var canSendCallTones: Bool {
        guard let call = phoneCall, call.id == audioCallID, call.connectedAt != nil else { return false }
        return canMutePhoneCall && !muted && !callReconnecting && activeCall?.simulated != true
    }

    func sendCallTone(_ digit: Character, for id: UUID) -> Bool {
        guard phoneCall?.id == id, canSendCallTones else { return false }
        return callAudio?.enqueueTone(digit) == true
    }

    func togglePhoneCallMute(_ id: UUID) {
        guard phoneCall?.id == id, canMutePhoneCall else { return }
        calls.requestMute(id, muted: !muted)
    }

    func callMuted(_ muted: Bool) {
        self.muted = muted
        callAudio?.muted = muted
        if muted { pendingAudioWrites.removeAll(keepingCapacity: true) }
    }

    func callAudioSession(_ active: Bool) {
        guard !callReconnecting, let callAudio else { return }
        if active {
            do {
                try callAudio.start()
                callAudioActive = true
                if callAudio.uplink { notifyPhoneAudio(ready: true) }
            } catch {
                callAudioActive = false
                notifyPhoneAudio(ready: false)
                logger.error("Call audio engine failed: \(error.localizedDescription, privacy: .public)")
                feedback = "Couldn’t start call audio on this iPhone. Use your Android for audio."
            }
        } else {
            callAudio.stop()
            callAudioActive = false
            pendingAudioWrites.removeAll(keepingCapacity: true)
            notifyPhoneAudio(ready: false)
        }
    }

    private func notifyPhoneAudio(ready: Bool) {
        guard supportsAudioRedirect, handshake == .authenticated, let id = audioCallID,
              activeCall?.simulated != true else { return }
        send(type: ready ? "audio_ready" : "audio_paused", id: id.uuidString.lowercased())
    }

    func callsReset() {
        phoneCall = nil
        queuedAnswer = nil
        if callReconnecting { activeCall = nil }
        finishCallRecovery()
        stopAudio()
        dialDeadlines.values.forEach { $0.invalidate() }
        dialDeadlines.removeAll()
    }

    func callFailed(_ message: String) {
        report(message)
    }

    private func report(_ message: String) {
        feedback = message
        notice = message
    }

    /// Removes a failed SMS from its conversation.
    func discardOutgoingSMS(_ id: String) {
        guard outgoingSMS?.id == id, smsRequest != id else { return }
        outgoingSMS = nil
    }

    func name(for address: String) -> String {
        if address.isEmpty { return ezText("Unknown") }
        return contactName(for: address) ?? address
    }

    /// The synced contact name for a phone number, or nil when the number isn't a contact.
    func contactName(for address: String) -> String? {
        let key = Self.phoneKey(address)
        if let name = namedContacts[key] { return name }
        return Self.suffixKey(key).flatMap { namedSuffixes[$0] }
    }

    /// The address of an existing SMS thread with this number, so contacts open the right conversation.
    func threadAddress(for number: String) -> String {
        data.messages.first { Self.samePhone($0.address, number) }?.address ?? number
    }

    /// The synced contact that owns a phone number.
    func contact(for address: String) -> RelayContact? {
        guard !address.isEmpty else { return nil }
        return data.contacts.first { contact in contact.numbers.contains { Self.samePhone($0, address) } }
    }

    private static func samePhone(_ left: String, _ right: String) -> Bool {
        let leftKey = phoneKey(left), rightKey = phoneKey(right)
        if leftKey == rightKey { return true }
        guard let suffix = suffixKey(leftKey) else { return false }
        return suffixKey(rightKey) == suffix
    }

    private func restoreVault() {
        do {
            pairing = try LocalVault.loadPairing()
            vaultRead = true
            isPaired = pairing != nil
            guard let pairing else { return }
            do {
                data = try LocalVault.loadData(using: pairing.cacheKey)
                indexContacts()
            } catch {
                feedback = "Couldn’t read the saved inbox. Sync to refresh it."
            }
            status = .waiting
            prepareBluetooth()
        } catch {
            status = .unavailable
            feedback = "Unlock this iPhone to read the saved link."
        }
    }

    private func refreshAlerts() {
        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async { self?.alerts = settings.authorizationStatus }
        }
    }

    private var shouldConnect: Bool {
        isPairing || pairing != nil
    }

    private func prepareBluetooth() {
        if let manager {
            centralManagerDidUpdateState(manager)
        } else {
            manager = CBCentralManager(delegate: self, queue: nil, options: [
                CBCentralManagerOptionRestoreIdentifierKey: "app.xcall.ble.central",
                CBCentralManagerOptionShowPowerAlertKey: false
            ])
        }
    }

    private func tryConnect(preferSavedPeer: Bool = true) {
        guard let manager, manager.state == .poweredOn, shouldConnect,
              peripheral == nil, retryTimer == nil else { return }
        if preferSavedPeer, let id = pairing?.peripheralID,
           let saved = manager.retrievePeripherals(withIdentifiers: [id]).first, saved.state != .disconnecting {
            connect(saved)
        } else {
            status = .searching
            scanForRelay(using: manager)
        }
    }

    private func scanForRelay(using manager: CBCentralManager) {
        // The Android advertiser can change address while the pairing secret stays valid.
        if !manager.isScanning { manager.scanForPeripherals(withServices: [service]) }
    }

    private func connect(_ candidate: CBPeripheral) {
        guard let manager, peripheral == nil, shouldConnect else { return }
        manager.stopScan()
        peripheral = candidate
        candidate.delegate = self
        status = .connecting
        if candidate.state == .connected {
            beginDiscovery(candidate)
        } else if candidate.state == .disconnected {
            manager.connect(candidate, options: [CBConnectPeripheralOptionEnableAutoReconnect: true])
        }
        if status == .connecting {
            if candidate.identifier == pairing?.peripheralID {
                // A system connection request survives suspension. Scan as well if Android changes its address.
                scanForRelay(using: manager)
            } else {
                armHandshakeDeadline()
            }
        }
    }

    private func beginDiscovery(_ candidate: CBPeripheral) {
        manager?.stopScan()
        candidate.delegate = self
        status = .authenticating
        logger.info("GATT connected, discovering relay service")
        armHandshakeDeadline()
        candidate.discoverServices([service])
    }

    private func armHandshakeDeadline() {
        handshakeDeadline?.invalidate()
        handshakeDeadline = after(status == .connecting ? 12 : 16) { relay in
            let message = relay.status == .connecting
                ? "Android didn’t respond. Looking for it again."
                : "Couldn’t verify the Bluetooth link. Keep the phones nearby."
            relay.dropPeer(message)
        }
    }

    private func stopBluetooth(allowCallRecovery: Bool = false) {
        retryTimer?.invalidate()
        retryTimer = nil
        manager?.stopScan()
        if let peripheral { manager?.cancelPeripheralConnection(peripheral) }
        peripheral = nil
        if !allowCallRecovery { pendingHangUp = nil }
        clearSession(allowCallRecovery: allowCallRecovery)
    }

    private func clearSession(allowCallRecovery: Bool = true) {
        handshakeDeadline?.invalidate()
        handshakeDeadline = nil
        handshake = .idle
        acknowledgingUnpair = false
        clientKey = nil
        writeChannel = nil
        audioChannel = nil
        pendingAudioWrites.removeAll(keepingCapacity: true)
        resettingSubscriptions.removeAll()
        reader = FrameReader()
        link = nil
        writes.removeAll()
        writing = false
        resumeRequested = false
        supportsAudioRedirect = false
        if !allowCallRecovery || !beginCallRecovery() {
            queuedAnswer = nil
            finishCallRecovery()
            activeCall = nil
            stopAudio()
            calls.endAll(reason: .failed)
        }
        dialDeadlines.values.forEach { $0.invalidate() }
        dialDeadlines.removeAll()
        callControl = false
        canDial = false
        canSendSMS = false
        sharing = nil
        if let id = smsRequest {
            finishSMS(id, sent: false, message: "The link dropped. Check Android before sending again.")
        }
        audioMode = .off
        if testing { finishTest("The Bluetooth link was interrupted.") }
        if syncing { finishSync("Sync was interrupted. Previous items were kept.") }
    }

    private func beginCallRecovery() -> Bool {
        guard let call = activeCall, let id = UUID(uuidString: call.id), calls.has(id),
              canResumeCall || callReconnecting,
              call.state == .active || (call.state == .ringing && call.simulated != true && (callControl || callReconnecting))
        else { return false }
        if let recoveryDeadline { return ContinuousClock.now < recoveryDeadline }
        recoveryDeadline = ContinuousClock.now.advanced(by: .seconds(18))
        callReconnecting = true
        callAudio?.stop()
        callAudioActive = false
        calls.setReconnecting(id, name: name(for: call.address), reconnecting: true)
        logger.info("Call waiting for Bluetooth recovery")
        recoveryTask = UIApplication.shared.beginBackgroundTask(withName: "Bluetooth call recovery") { [weak self] in
            Task { @MainActor in self?.expireCallRecovery() }
        }
        recoveryTimer = after(18) { $0.expireCallRecovery() }
        return true
    }

    private func finishCallRecovery() {
        recoveryTimer?.invalidate()
        recoveryTimer = nil
        recoveryDeadline = nil
        callReconnecting = false
        if recoveryTask != .invalid {
            UIApplication.shared.endBackgroundTask(recoveryTask)
            recoveryTask = .invalid
        }
    }

    private func expireCallRecovery() {
        guard callReconnecting else { return }
        queuedAnswer = nil
        finishCallRecovery()
        stopAudio()
        if let id = activeCall.flatMap({ UUID(uuidString: $0.id) }) {
            calls.ended(id, reason: .failed)
        }
        activeCall = nil
    }

    private func requestCallRecovery() {
        guard !resumeRequested, let recoveryDeadline, let call = activeCall, call.state == .active,
              let id = UUID(uuidString: call.id) else { return }
        guard canResumeCall, ContinuousClock.now < recoveryDeadline else {
            expireCallRecovery()
            return
        }
        resumeRequested = true
        send(["type": "call_resume", "id": id.uuidString.lowercased(), "audio": audioCallID == id ? "1" : "0"])
    }

    private func resumeCall(_ result: CallResumed) {
        guard resumeRequested, let recoveryDeadline, let call = activeCall,
              UUID(uuidString: call.id) == result.id else { return }
        guard ContinuousClock.now < recoveryDeadline else {
            expireCallRecovery()
            return
        }
        calls.setReconnecting(result.id, name: name(for: call.address), reconnecting: false)
        finishCallRecovery()
        if result.audio && audioCallID == result.id {
            callAudioSession(calls.audioActive)
        } else {
            stopAudio()
        }
        logger.info("Active call resumed, audio active=\(self.callAudioActive)")
        if UIApplication.shared.applicationState == .active, needsSync { syncNow() }
    }

    private func retry(preferSavedPeer: Bool = true) {
        guard shouldConnect, manager?.state == .poweredOn, retryTimer == nil else { return }
        // iOS can suspend the app before a timer starts the next request.
        if !isPairing {
            tryConnect(preferSavedPeer: preferSavedPeer)
            return
        }
        status = .searching
        retryTimer = after(1.5) { relay in
            relay.retryTimer = nil
            relay.tryConnect()
        }
    }

    private func dropPeer(_ reason: String) {
        feedback = reason
        guard let peripheral else { return }
        if isPairing {
            rejected = rejected.filter { $0.value > Date() }
            if rejected.count < 32 { rejected[peripheral.identifier] = Date().addingTimeInterval(8) }
        }
        self.peripheral = nil
        clearSession()
        if peripheral.state != .disconnected { manager?.cancelPeripheralConnection(peripheral) }
        retry()
    }

    private func enqueue(_ body: Data) throws {
        guard let peripheral, writeChannel != nil else { throw WireError.malformed }
        let packet = try RelayWire.frame(body)
        let size = min(peripheral.maximumWriteValueLength(for: .withoutResponse), 180)
        guard size > 0 else { throw WireError.malformed }
        for offset in stride(from: 0, to: packet.count, by: size) {
            writes.append(Data(packet.dropFirst(offset).prefix(size)))
        }
        nextWrite()
    }

    private func nextWrite() {
        guard !writing, let piece = writes.first, let peripheral, let writeChannel else { return }
        writing = true
        peripheral.writeValue(piece, for: writeChannel, type: .withResponse)
    }

    private func startHandshake() {
        guard handshake == .idle, writeChannel != nil else { return }
        let key = P256.KeyAgreement.PrivateKey()
        clientKey = key
        handshake = .serverHello
        do {
            try enqueue(Data([1]) + key.publicKey.x963Representation)
        } catch {
            dropPeer("Couldn’t start a secure link.")
        }
    }

    private func send(type: String, id: String? = nil) {
        var envelope = ["type": type]
        if let id { envelope["id"] = id }
        send(envelope)
    }

    private func send(_ envelope: [String: String]) {
        do {
            let payload = try JSONEncoder().encode(envelope)
            guard let body = try link?.seal(payload) else { throw WireError.malformed }
            try enqueue(body)
        } catch {
            dropPeer("The secure link was interrupted.")
        }
    }

    private func receive(_ frame: Data) throws {
        switch handshake {
        case .idle:
            throw WireError.malformed
        case .serverHello:
            guard let clientKey else { throw WireError.malformed }
            // Android may still use the old key if it missed the last `ready`, so try both.
            let keys = pendingSecret.map { [$0] } ?? [pairing?.secret, pairing?.next].compactMap { $0 }
            guard let match = keys.lazy.compactMap({ key in
                (try? SecureLink(secret: key, clientKey: clientKey, response: frame)).map { (key: key, link: $0) }
            }).first else { throw WireError.malformed }
            link = match.link
            logger.info("Android handshake proof verified")
            self.clientKey = nil
            handshake = .ready
            sendReady(used: match.key, next: match.link.next)
        case .ready, .authenticated:
            guard let payload = try link?.open(frame) else { throw WireError.malformed }
            let header = try decoder.decode(Header.self, from: payload)
            if handshake == .ready {
                guard header.type == "ready", pairing != nil, !isPairing else { throw WireError.malformed }
                completeHandshake()
            } else {
                try handle(header, payload: payload)
            }
        }
    }

    /// Saves the working key and the next one before `ready`, so Android can switch without locking this phone out.
    private func sendReady(used: Data, next: Data) {
        guard let peripheral else { return }
        let cacheKey = pairing?.cacheKey ?? SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        let record = PairingRecord(secret: used, next: next, cacheKey: cacheKey, peripheralID: peripheral.identifier)
        do {
            try LocalVault.savePairing(record)
        } catch {
            if isPairing {
                cancelPairing()
                feedback = "Couldn’t save the link securely. Unlock this iPhone and try again."
            } else {
                send(["type": "ready", "unpair": "1"])
            }
            return
        }
        pairing = record
        if isPairing {
            vaultRead = true
            isPaired = true
            isPairing = false
            pendingSecret = nil
            pendingPairDeadline?.invalidate()
            rejected.removeAll()
            data = RelayData()
            namedContacts.removeAll()
            namedSuffixes.removeAll()
            outgoingSMS = nil
        }
        send(["type": "ready", "unpair": "1", "rekey": "1"])
    }

    private func completeHandshake() {
        handshakeDeadline?.invalidate()
        handshakeDeadline = nil
        handshake = .authenticated
        status = .connected
        logger.info("Relay authenticated")
        feedback = nil
        needsSync = true
        if let pendingHangUp {
            self.pendingHangUp = nil
            if ContinuousClock.now < pendingHangUp.deadline {
                send(type: "call_end", id: pendingHangUp.id.uuidString.lowercased())
            }
        }
        if !callReconnecting, UIApplication.shared.applicationState == .active { syncNow() }
    }

    private func handle(_ header: Header, payload: Data) throws {
        guard !acknowledgingUnpair else { return }
        switch header.type {
        case "unpair":
            guard let id = header.id, UUID(uuidString: id) != nil else { throw WireError.malformed }
            forgetPhone(acknowledging: id)
        case "pong":
            guard let ping, ping.id == header.id else { return }
            let elapsed = (ProcessInfo.processInfo.systemUptime - ping.started) * 1_000
            finishTest(ezText("Round-trip verified · %lld ms", Int64(max(1, Int(elapsed.rounded())))))
        case "sms":
            let message = try decoder.decode(RelaySMS.self, from: payload)
            guard valid(message) else { throw WireError.malformed }
            let isNew = !data.messages.contains { $0.id == message.id }
            data.merge([message])
            if let count = sync?.liveMessages.count, count < 500 { sync?.liveMessages.append(message) }
            persist()
            if isNew && message.direction == "in" && UIApplication.shared.applicationState != .active {
                let body = message.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? ezText("New message on Android") : message.body
                alert(body, title: name(for: message.address), id: notificationID("message", message.id))
            }
        case "call":
            let call = try decoder.decode(RelayCall.self, from: payload)
            guard validID(call.id), call.address.utf8.count <= 256, validDate(call.date) else {
                throw WireError.malformed
            }
            let isNew = !data.calls.contains { $0.id == call.id }
            data.update(call)
            let wasRingingDuringRecovery = callReconnecting && activeCall?.id == call.id && activeCall?.state == .ringing
            if callReconnecting, activeCall?.id != call.id, call.state != .ended { expireCallRecovery() }
            if call.state == .ended {
                if activeCall?.id == call.id { activeCall = nil }
            } else {
                activeCall = call
            }
            persist()
            if wasRingingDuringRecovery, let id = UUID(uuidString: call.id) {
                if call.state == .ringing {
                    calls.setReconnecting(id, name: name(for: call.address), reconnecting: false)
                    finishCallRecovery()
                    if calls.isAnswering(id) { queuedAnswer = id }
                    sendQueuedAnswer(id)
                } else if call.state == .active, queuedAnswer == id || !calls.isAnswering(id) {
                    queuedAnswer = nil
                    finishCallRecovery()
                    calls.ended(id, reason: .answeredElsewhere)
                } else if call.state == .active {
                    requestCallRecovery()
                }
            }
            route(call, isNew: isNew)
        case "capabilities":
            let capabilities = try decoder.decode(Capabilities.self, from: payload)
            callControl = capabilities.control
            canDial = capabilities.dial
            canResumeCall = capabilities.resume_call == true
            supportsAudioRedirect = capabilities.audio_route == true
            if callReconnecting { requestCallRecovery() }
            audioMode = AudioMode(rawValue: capabilities.audio) ?? .off
            canSendSMS = capabilities.send_sms ?? false
            if let texts = capabilities.texts, let contacts = capabilities.contacts {
                if let previous = sharing, previous.texts != texts || previous.contacts != contacts {
                    needsSync = true
                    syncNow()
                }
                sharing = (texts, contacts)
            }
        case "sms_result":
            let result = try decoder.decode(SMSResult.self, from: payload)
            let message = result.message ?? (result.sent ? "Sent from Android" : "Android couldn’t send the SMS.")
            finishSMS(result.id, sent: result.sent, message: String(message.prefix(200)))
        case "audio_start":
            startAudio(try decoder.decode(AudioStart.self, from: payload))
        case "audio_stop":
            let notice = try decoder.decode(Notice.self, from: payload)
            guard let id = UUID(uuidString: notice.id), id == audioCallID || (audioCallID == nil && calls.has(id)) else { return }
            stopAudio()
            if let message = notice.message { feedback = String(message.prefix(200)) }
        case "call_resumed":
            resumeCall(try decoder.decode(CallResumed.self, from: payload))
        case "call_error":
            let notice = try decoder.decode(Notice.self, from: payload)
            if let id = UUID(uuidString: notice.id) {
                if queuedAnswer == id { queuedAnswer = nil }
                if activeCall.flatMap({ UUID(uuidString: $0.id) }) == id {
                    if callReconnecting { finishCallRecovery() }
                    activeCall = nil
                }
                dialDeadlines.removeValue(forKey: id)?.invalidate()
                if audioCallID == id { stopAudio() }
                calls.ended(id, reason: .failed)
            }
            report(String((notice.message ?? "Android couldn’t complete the call action.").prefix(200)))
        case "sync_begin", "sms_batch", "contacts_batch", "sync_end", "error":
            try handleSync(header, payload: payload)
        default:
            throw WireError.malformed
        }
    }

    private func route(_ call: RelayCall, isNew: Bool) {
        let id = UUID(uuidString: call.id)
        switch call.state {
        case .ringing:
            if isNew { logger.info("Authenticated ringing event received") }
            if isNew && (call.simulated == true || (callControl && audioMode == .twoWay)) {
                requestMicrophone()
            }
            if callControl || call.simulated == true, let id {
                let alertID = notificationID("call", call.id)
                calls.reportIncoming(id, address: call.address, name: name(for: call.address),
                                     simulated: call.simulated == true) { [weak self] in
                    self?.alert(ezText("Incoming call on Android"), id: alertID)
                }
            } else if isNew {
                alert(ezText("Incoming call on Android"), id: notificationID("call", call.id))
            }
        case .active:
            guard let id else { return }
            dialDeadlines.removeValue(forKey: id)?.invalidate()
            calls.connected(id)
        case .ended:
            guard let id else { return }
            if queuedAnswer == id { queuedAnswer = nil }
            if callReconnecting, activeCall == nil { finishCallRecovery() }
            dialDeadlines.removeValue(forKey: id)?.invalidate()
            if audioCallID == id { stopAudio() }
            calls.ended(id, reason: .remoteEnded)
        }
    }

    private func startAudio(_ start: AudioStart) {
        guard let id = UUID(uuidString: start.id), calls.has(id),
              let key = Data(base64Encoded: start.key), key.count == 32 else { return }
        stopAudio()
        audioCallID = id
        armSyncDeadline()
        let maxWrite = peripheral?.maximumWriteValueLength(for: .withoutResponse) ?? 0
        let payloadLimit = min(AudioSeal.maxPayload, maxWrite - AudioSeal.overhead)
        let uplink = start.uplink && audioChannel != nil && payloadLimit > 0 &&
            AVAudioApplication.shared.recordPermission == .granted
        do {
            let audio = try CallAudio(key: key, uplink: uplink, payloadLimit: payloadLimit, routeChanged: { [weak self] in
                DispatchQueue.main.async {
                    guard let self, self.audioCallID == id, self.calls.audioActive else { return }
                    self.callAudioSession(false)
                    self.callAudioSession(true)
                }
            }) { [weak self] packet in
                let deadline = ContinuousClock.now.advanced(by: .milliseconds(80))
                DispatchQueue.main.async {
                    guard let self, self.audioCallID == id, ContinuousClock.now < deadline else { return }
                    if self.pendingAudioWrites.count == 3 { self.pendingAudioWrites.removeFirst() }
                    self.pendingAudioWrites.append((packet, deadline))
                    self.flushAudioWrite()
                }
            }
            audio.muted = muted
            callAudio = audio
            if calls.audioActive { callAudioSession(true) }
            if start.uplink && !uplink {
                feedback = "Allow the microphone for ezcall in iPhone Settings to talk on calls."
            } else if !start.uplink {
                feedback = "Android couldn’t use the iPhone microphone for this call. Speak into Android."
            }
        } catch {
            logger.error("Call audio couldn't start")
            feedback = "Couldn’t start call audio on this iPhone."
        }
    }

    private func stopAudio() {
        let wasStreaming = audioCallID != nil
        callAudio?.stop()
        callAudio = nil
        audioCallID = nil
        callAudioActive = false
        pendingAudioWrites.removeAll(keepingCapacity: true)
        if wasStreaming { armSyncDeadline() }
    }

    private func flushAudioWrite() {
        guard !pendingAudioWrites.isEmpty else { return }
        guard handshake == .authenticated, !callReconnecting, callAudioActive, !muted,
              let peripheral, let audioChannel else {
            pendingAudioWrites.removeAll(keepingCapacity: true)
            return
        }
        let maxBytes = peripheral.maximumWriteValueLength(for: .withoutResponse)
        while peripheral.canSendWriteWithoutResponse, !pendingAudioWrites.isEmpty {
            let pending = pendingAudioWrites.removeFirst()
            if ContinuousClock.now < pending.expires, pending.packet.count <= maxBytes {
                peripheral.writeValue(pending.packet, for: audioChannel, type: .withoutResponse)
            }
        }
    }

    private func handleSync(_ header: Header, payload: Data) throws {
        guard var stage = sync, stage.id == header.id else { return }
        stage.bytes += payload.count
        guard stage.bytes <= 5_242_880 else { throw WireError.malformed }
        switch header.type {
        case "sync_begin":
            guard !stage.started else { throw WireError.malformed }
            stage.started = true
            sync = stage
        case "sms_batch":
            let rows = try decoder.decode(Batch<RelaySMS>.self, from: payload).items
            guard stage.started, stage.messages.count + rows.count <= 500,
                  rows.allSatisfy(valid) else { throw WireError.malformed }
            stage.messages.append(contentsOf: rows)
            sync = stage
        case "contacts_batch":
            let rows = try decoder.decode(Batch<RelayContact>.self, from: payload).items
            guard stage.started, stage.contacts.count + rows.count <= 5_000,
                  rows.allSatisfy({ validID($0.id) && $0.name.utf8.count <= 512 &&
                      $0.numbers.count <= 32 &&
                      $0.numbers.allSatisfy { $0.utf8.count <= 256 }
                  }) else { throw WireError.malformed }
            stage.contacts.append(contentsOf: rows)
            sync = stage
        case "sync_end":
            guard stage.started else { throw WireError.malformed }
            var newData = data
            newData.messages = []
            newData.merge(stage.messages + stage.liveMessages)
            newData.replaceContacts(stage.contacts)
            guard let pairing else { throw WireError.malformed }
            do {
                try LocalVault.saveData(newData, using: pairing.cacheKey)
                data = newData
                indexContacts()
                finishSync("Messages and contacts are up to date.")
            } catch {
                finishSync("Couldn’t save the update. Previous items were kept.")
            }
        case "error":
            finishSync("Android couldn’t complete sync. Check sharing on Android.")
        default:
            break
        }
        armSyncDeadline()
    }

    private func validID(_ id: String) -> Bool {
        !id.isEmpty && id.utf8.count <= 128
    }

    private func validDate(_ date: Double) -> Bool {
        date >= 0 && date < 4_102_444_800_000
    }

    private func valid(_ message: RelaySMS) -> Bool {
        validID(message.id) && message.address.utf8.count <= 256 && message.body.utf8.count <= 16_384 &&
            validDate(message.date) && ["in", "out"].contains(message.direction)
    }

    private func persist() {
        guard let pairing else { return }
        do {
            try LocalVault.saveData(data, using: pairing.cacheKey)
        } catch {
            feedback = "Couldn’t save recent activity on this iPhone."
        }
    }

    private func indexContacts() {
        namedContacts.removeAll(keepingCapacity: true)
        namedSuffixes.removeAll(keepingCapacity: true)
        for contact in data.contacts where !contact.name.isEmpty {
            for phone in contact.numbers where !phone.isEmpty {
                let key = Self.phoneKey(phone)
                namedContacts[key] = contact.name
                if let suffix = Self.suffixKey(key), namedSuffixes[suffix] == nil { namedSuffixes[suffix] = contact.name }
            }
        }
    }

    /// Matches national and international forms of a number, such as 0912… and +98912….
    private static func suffixKey(_ key: String) -> String? {
        guard key.count >= 9, key.allSatisfy(\.isNumber) else { return nil }
        return String(key.suffix(9))
    }

    private static func phoneKey(_ value: String) -> String {
        let validFormatting = CharacterSet(charactersIn: "+- ()")
        let digits = value.filter(\.isNumber)
        if !digits.isEmpty,
           value.unicodeScalars.allSatisfy({ validFormatting.contains($0) || CharacterSet.decimalDigits.contains($0) }) {
            return digits
        }
        return value.lowercased()
    }

    private func alert(_ body: String, title: String = "", id: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = "android-relay"
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        let logger = self.logger
        UNUserNotificationCenter.current().add(request) { error in
            if error != nil {
                logger.info("Could not schedule ezcall notification")
            } else if id.hasPrefix("call-") {
                logger.info("Call notification accepted by iOS")
            }
        }
    }

    private func notificationID(_ type: String, _ id: String) -> String {
        type + "-" + Data(SHA256.hash(data: Data(id.utf8))).base64EncodedString()
    }

    private func after(_ delay: TimeInterval, _ run: @escaping @MainActor (RelayController) -> Void) -> Timer {
        Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                if let self { run(self) }
            }
        }
    }

    private func armSyncDeadline() {
        syncDeadline?.invalidate()
        syncDeadline = nil
        // Android holds bulk sync frames until the call's audio stream stops.
        guard syncing, audioCallID == nil else { return }
        syncDeadline = after(25) { relay in
            relay.finishSync("Android didn’t finish sync. Previous items were kept.")
        }
    }

    private func finishSync(_ result: String) {
        syncDeadline?.invalidate()
        syncDeadline = nil
        sync = nil
        syncing = false
        syncResult = result
        if status == .connected, handshake == .authenticated, needsSync { syncNow() }
    }

    private func finishTest(_ result: String) {
        testDeadline?.invalidate()
        testDeadline = nil
        ping = nil
        testing = false
        testResult = result
    }
}

extension RelayController: @preconcurrency CBCentralManagerDelegate, @preconcurrency CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard shouldConnect else {
            if central.state == .poweredOn { central.stopScan() }
            return
        }
        switch central.state {
        case .poweredOn:
            let restored = restoredPeripheral
            restoredPeripheral = nil
            if let restored, peripheral == nil {
                switch restored.state {
                case .connected:
                    // The live GATT link still belongs to the old process's encryption session.
                    peripheral = restored
                    restored.delegate = self
                    status = .connecting
                    central.cancelPeripheralConnection(restored)
                    scanForRelay(using: central)
                case .connecting:
                    connect(restored)
                default:
                    tryConnect()
                }
            } else {
                tryConnect()
            }
        case .poweredOff, .unauthorized, .unsupported, .unknown, .resetting:
            stopBluetooth(allowCallRecovery: central.state != .unauthorized && central.state != .unsupported)
            switch central.state {
            case .poweredOff: status = .bluetoothOff
            case .unauthorized: status = .denied
            case .unsupported: status = .unsupported
            default: status = .waiting
            }
        @unknown default:
            stopBluetooth()
            status = .unavailable
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        for candidate in restored {
            if shouldConnect && candidate.identifier == pairing?.peripheralID {
                restoredPeripheral = candidate
            } else {
                central.cancelPeripheralConnection(candidate)
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover candidate: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard shouldConnect,
              rejected[candidate.identifier, default: .distantPast] <= Date(),
              candidate.state == .disconnected || candidate.state == .connected else { return }
        if let pending = peripheral {
            guard status == .connecting, handshakeDeadline == nil,
                  pending.state == .connecting || pending.state == .disconnecting,
                  pending.identifier != candidate.identifier else { return }
            peripheral = nil
            central.cancelPeripheralConnection(pending)
        }
        connect(candidate)
    }

    func centralManager(_ central: CBCentralManager, didConnect candidate: CBPeripheral) {
        guard candidate === peripheral, shouldConnect else {
            central.cancelPeripheralConnection(candidate)
            return
        }
        if status == .connecting { beginDiscovery(candidate) }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect candidate: CBPeripheral, error: Error?) {
        guard candidate === peripheral else { return }
        peripheral = nil
        clearSession()
        central.stopScan()
        retry(preferSavedPeer: false)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral candidate: CBPeripheral,
                        timestamp: CFAbsoluteTime, isReconnecting: Bool, error: Error?) {
        guard candidate === peripheral else {
            if candidate.identifier == pairing?.peripheralID { tryConnect() }
            return
        }
        clearSession()
        if isReconnecting && shouldConnect {
            status = .connecting
            // Core Bluetooth keeps its reconnect pending even while the app is suspended.
            scanForRelay(using: central)
            return
        }
        peripheral = nil
        // Keep a Bluetooth request pending before iOS can suspend the app again.
        tryConnect()
    }

    func peripheral(_ candidate: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard candidate === peripheral, invalidatedServices.contains(where: { $0.uuid == service }) else { return }
        dropPeer("Android’s Bluetooth service restarted. Reconnecting…")
    }

    func peripheral(_ candidate: CBPeripheral, didDiscoverServices error: Error?) {
        guard candidate === peripheral, status == .authenticating else { return }
        guard error == nil, let match = candidate.services?.first(where: { $0.uuid == service }) else {
            dropPeer("Android’s Bluetooth service wasn’t available.")
            return
        }
        candidate.discoverCharacteristics([rx, tx, audioRx, audioTx], for: match)
    }

    func peripheral(_ candidate: CBPeripheral, didDiscoverCharacteristicsFor found: CBService, error: Error?) {
        guard candidate === peripheral, found.uuid == service, status == .authenticating else { return }
        guard error == nil,
              let input = found.characteristics?.first(where: { $0.uuid == rx && $0.properties.contains(.write) }),
              let output = found.characteristics?.first(where: { $0.uuid == tx && $0.properties.contains(.indicate) })
        else {
            dropPeer("Android’s Bluetooth service didn’t match ezcall.")
            return
        }
        writeChannel = input
        // Older Android builds have no audio channel. Calls then keep their audio on Android.
        audioChannel = found.characteristics?.first { $0.uuid == audioRx && $0.properties.contains(.writeWithoutResponse) }
        subscribe(to: output, on: candidate)
        if let audio = found.characteristics?.first(where: { $0.uuid == audioTx && $0.properties.contains(.notify) }) {
            subscribe(to: audio, on: candidate)
        }
    }

    private func subscribe(to characteristic: CBCharacteristic, on candidate: CBPeripheral) {
        // Core Bluetooth can cache a subscription across an Android GATT server restart.
        if characteristic.isNotifying { resettingSubscriptions.insert(characteristic.uuid) }
        candidate.setNotifyValue(!characteristic.isNotifying, for: characteristic)
    }

    func peripheral(_ candidate: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard candidate === peripheral else { return }
        if resettingSubscriptions.remove(characteristic.uuid) != nil, error == nil, !characteristic.isNotifying {
            candidate.setNotifyValue(true, for: characteristic)
            return
        }
        guard characteristic.uuid == tx, status == .authenticating else { return }
        guard error == nil, characteristic.isNotifying else {
            dropPeer("Couldn’t subscribe to Android’s Bluetooth stream.")
            return
        }
        logger.info("GATT indication subscription enabled")
        startHandshake()
    }

    func peripheral(_ candidate: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == audioTx {
            if candidate === peripheral, handshake == .authenticated, !callReconnecting, error == nil,
               let value = characteristic.value {
                callAudio?.receive(value)
            }
            return
        }
        guard candidate === peripheral, characteristic.uuid == tx, handshake != .idle else { return }
        guard error == nil, let value = characteristic.value else {
            dropPeer("The Bluetooth stream was interrupted.")
            return
        }
        do {
            for frame in try reader.read(value) {
                try receive(frame)
                if handshake == .idle { break }
            }
        } catch {
            dropPeer("Couldn’t verify the secure link. Check the code on Android.")
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse candidate: CBPeripheral) {
        guard candidate === peripheral else { return }
        flushAudioWrite()
    }

    func peripheral(_ candidate: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard candidate === peripheral, characteristic.uuid == rx, writing else { return }
        guard error == nil else {
            dropPeer("A Bluetooth write failed.")
            return
        }
        writing = false
        writes.removeFirst()
        if acknowledgingUnpair && writes.isEmpty {
            stopBluetooth()
            return
        }
        nextWrite()
    }
}

