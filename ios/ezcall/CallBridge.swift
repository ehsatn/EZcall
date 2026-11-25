import AVFoundation
import CallKit
import UIKit
import os

/// Shows Android SIM calls in the iOS call interface. Answer, end and dial actions go to Android, which owns the
/// call. Android reports the real call state back.
@MainActor
final class CallBridge: NSObject {
    private struct Entry {
        let outgoing: Bool
        var answered = false
        var connected = false
    }

    weak var relay: RelayController?
    private(set) var audioActive = false
    private let provider: CXProvider
    private let controller = CXCallController()
    private let logger = Logger(subsystem: "app.xcall.relay", category: "CallKit")
    private var entries = [UUID: Entry]()
    private var outgoingNames = [UUID: String]()
    private var answering: CXAnswerCallAction?

    override init() {
        let configuration = CXProviderConfiguration()
        configuration.supportsVideo = false
        configuration.maximumCallGroups = 1
        configuration.maximumCallsPerCallGroup = 1
        configuration.supportedHandleTypes = [.phoneNumber, .generic]
        configuration.includesCallsInRecents = true
        configuration.iconTemplateImageData = UIImage(named: "CallProviderIcon")?.pngData()
        provider = CXProvider(configuration: configuration)
        super.init()
        provider.setDelegate(self, queue: nil)
        controller.callObserver.setDelegate(self, queue: nil)
    }

    var hasOngoingCall: Bool {
        audioActive || answering != nil || entries.values.contains { $0.outgoing || $0.connected }
    }

    func has(_ id: UUID) -> Bool { entries[id] != nil }
    func isAnswering(_ id: UUID) -> Bool { answering?.callUUID == id }

    @discardableResult
    private func finishAnswer(_ id: UUID, connectedAt: Date? = nil) -> Bool {
        guard let action = answering, action.callUUID == id else { return false }
        answering = nil
        guard !action.isComplete, action.timeoutDate > Date() else { return false }
        if let connectedAt {
            action.fulfill(withDateConnected: connectedAt)
            return true
        }
        action.fail()
        return false
    }

    func setReconnecting(_ id: UUID, name: String, reconnecting: Bool) {
        guard entries[id] != nil else { return }
        let details = CXCallUpdate()
        details.localizedCallerName = reconnecting ? ezText("%@ · Reconnecting…", name) : name
        provider.reportCall(with: id, updated: details)
    }

    func reportIncoming(_ id: UUID, address: String, name: String, simulated: Bool,
                        fallback: @escaping @MainActor () -> Void) {
        let details = update(address: address, name: name)
        if simulated { details.remoteHandle = CXHandle(type: .generic, value: name) }
        if entries[id] != nil {
            provider.reportCall(with: id, updated: details)
            return
        }
        entries[id] = Entry(outgoing: false)
        provider.reportNewIncomingCall(with: id, update: details) { [weak self] error in
            guard error != nil else { return }
            Task { @MainActor in
                self?.entries[id] = nil
                fallback()
            }
        }
    }

    func dial(_ number: String, name: String) {
        let id = UUID()
        outgoingNames[id] = name
        let action = CXStartCallAction(call: id, handle: CXHandle(type: .phoneNumber, value: number))
        controller.request(CXTransaction(action: action)) { [weak self] error in
            guard error != nil else { return }
            Task { @MainActor in
                self?.outgoingNames[id] = nil
                self?.relay?.callFailed("iPhone couldn’t start the call.")
            }
        }
    }

    /// Android reports that the SIM call is connected.
    func connected(_ id: UUID) {
        guard var entry = entries[id], !entry.connected else { return }
        let connectedAt = Date()
        if isAnswering(id) {
            entry.answered = finishAnswer(id, connectedAt: connectedAt)
        }
        if entry.outgoing || entry.answered {
            entry.connected = true
            entries[id] = entry
            relay?.phoneCallConnected(id, at: connectedAt)
            if entry.outgoing { provider.reportOutgoingCall(with: id, connectedAt: connectedAt) }
        } else {
            // Answered on the Android phone itself. Its audio stays there.
            entries[id] = nil
            relay?.phoneCallEnded(id)
            provider.reportCall(with: id, endedAt: nil, reason: .answeredElsewhere)
        }
    }

    func ended(_ id: UUID, reason: CXCallEndedReason) {
        let entry = entries.removeValue(forKey: id)
        finishAnswer(id)
        relay?.phoneCallEnded(id)
        guard let entry else { return }
        let missed = reason == .remoteEnded && !entry.outgoing && !entry.answered
        provider.reportCall(with: id, endedAt: nil, reason: missed ? .unanswered : reason)
    }

    func endAll(reason: CXCallEndedReason) {
        for id in Array(entries.keys) { ended(id, reason: reason) }
        outgoingNames.removeAll()
    }

    func requestMute(_ id: UUID, muted: Bool) {
        guard has(id) else { return }
        controller.request(CXTransaction(action: CXSetMutedCallAction(call: id, muted: muted))) { [weak self] error in
            guard error != nil else { return }
            Task { @MainActor in
                guard self?.has(id) == true else { return }
                self?.relay?.callFailed("Couldn’t change the microphone state.")
            }
        }
    }

    func requestEnd(_ id: UUID) {
        guard has(id) else { return }
        controller.request(CXTransaction(action: CXEndCallAction(call: id))) { [weak self] error in
            guard error != nil else { return }
            Task { @MainActor in self?.relay?.phoneCallEndFailed(id) }
        }
    }

    private func update(address: String, name: String) -> CXCallUpdate {
        let update = CXCallUpdate()
        update.remoteHandle = address.isEmpty ? CXHandle(type: .generic, value: name) : CXHandle(type: .phoneNumber, value: address)
        update.localizedCallerName = name
        update.hasVideo = false
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        update.supportsDTMF = false
        return update
    }

    private func prepareAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
        try? session.setPreferredSampleRate(CallAudio.sampleRate)
        try? session.setPreferredIOBufferDuration(0.01)
    }
}

extension CallBridge: @preconcurrency CXCallObserverDelegate {
    func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        guard entries[call.uuid] != nil else { return }
        if call.hasEnded {
            entries[call.uuid] = nil
            finishAnswer(call.uuid)
            relay?.callHangUp(call.uuid)
            relay?.phoneCallEnded(call.uuid)
        } else if call.hasConnected {
            logger.info("iOS reports an active ezcall call")
        }
    }
}

extension CallBridge: @preconcurrency CXProviderDelegate {
    func providerDidReset(_ provider: CXProvider) {
        answering = nil
        entries.removeAll()
        outgoingNames.removeAll()
        audioActive = false
        relay?.callsReset()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        guard entries[action.callUUID] != nil, answering == nil else {
            action.fail()
            return
        }
        relay?.callMuted(false)
        prepareAudioSession()
        answering = action
        if relay?.callAnswer(action.callUUID) != true { finishAnswer(action.callUUID) }
    }

    func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
        guard let answer = answering, answer === action else { return }
        answering = nil
        // Otherwise Android may still pick up the call after iOS has dropped it.
        relay?.callHangUp(answer.callUUID)
        ended(answer.callUUID, reason: .failed)
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        if entries.removeValue(forKey: action.callUUID) != nil {
            finishAnswer(action.callUUID)
            relay?.callHangUp(action.callUUID)
            relay?.phoneCallEnded(action.callUUID)
        }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        let id = action.callUUID
        let name = outgoingNames.removeValue(forKey: id) ?? ""
        entries[id] = Entry(outgoing: true)
        guard relay?.callDial(id, number: action.handle.value) == true else {
            entries[id] = nil
            action.fail()
            return
        }
        relay?.callMuted(false)
        prepareAudioSession()
        provider.reportOutgoingCall(with: id, startedConnectingAt: nil)
        if !name.isEmpty { provider.reportCall(with: id, updated: update(address: action.handle.value, name: name)) }
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        relay?.callMuted(action.isMuted)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        audioActive = true
        relay?.callAudioSession(true)
    }

    func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        audioActive = false
        relay?.callAudioSession(false)
    }
}
