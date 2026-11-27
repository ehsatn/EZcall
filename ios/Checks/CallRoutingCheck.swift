import Foundation

// Check call-routing source on hosts without CallKit.
func read(_ file: String) throws -> String {
    try String(contentsOfFile: "ios/ezcall/\(file).swift", encoding: .utf8)
        .replacingOccurrences(of: "\r\n", with: "\n")
}

func method(_ source: String, _ signature: String) -> String {
    guard let start = source.range(of: signature),
          let end = source.range(of: "\n    }", range: start.upperBound..<source.endIndex) else {
        fatalError("Missing method body for \(signature)")
    }
    return String(source[start.upperBound..<end.lowerBound])
}

func expect(_ source: String, _ text: String, line: UInt = #line) {
    precondition(source.contains(text), "Missing \(text)", line: line)
}

func expectOrder(_ source: String, _ first: String, _ second: String, line: UInt = #line) {
    guard let firstRange = source.range(of: first), let secondRange = source.range(of: second) else {
        fatalError("Missing \(first) or \(second)", line: line)
    }
    precondition(firstRange.lowerBound < secondRange.lowerBound, "\(first) must precede \(second)", line: line)
}

let bridge = try read("CallBridge")
let relay = try read("RelayController")
let app = try read("EzcallApp")
let view = try read("InCallView")

let end = method(bridge, "perform action: CXEndCallAction)")
precondition(bridge.components(separatedBy: ".callHangUp(").count == 2)
let answerExpiry = end.components(separatedBy: "if answerExpired && !fromApp {")
precondition(answerExpiry.count == 2)
let paths = answerExpiry[1].components(separatedBy: "} else {")
precondition(paths.count >= 2)
expect(paths[0], "relay?.callPresentationEnded(id)")
expect(paths[1], "relay?.callHangUp(id)")

let observer = method(bridge, "func callObserver(")
expect(observer, "guard entries[call.uuid] != nil else { return }")
expect(end, "let id = action.callUUID")
expect(end, "if let entry = entries.removeValue(forKey: id)")

let start = method(bridge, "perform action: CXStartCallAction)")
expect(start, "\n        provider.reportCall(with: id, updated: update(")
expect(method(bridge, "func update("), "update.supportsHolding = false")
expect(method(bridge, "perform action: CXSetHeldCallAction)"), "action.fail()")

for signature in ["func callObserver(", "timedOutPerforming action: CXAction)"] {
    expect(method(bridge, signature), "relay?.callPresentationEnded(")
}
let hangup = method(relay, "func callHangUp(")
let queueAssignment = "pendingHangUp = (id, recoveryDeadline)"
expect(hangup, queueAssignment)
let assignmentPattern = try NSRegularExpression(pattern: #"pendingHangUp = (?!nil\b)[^\r\n]*"#)
let assignments = assignmentPattern.matches(in: relay, range: NSRange(relay.startIndex..., in: relay))
    .map { (relay as NSString).substring(with: $0.range) }
precondition(assignments == [queueAssignment])
expect(hangup, #"send(type: "call_end", id: id.uuidString.lowercased())"#)
precondition(relay.components(separatedBy: #""call_end""#).count == 3)
expect(relay, "if ContinuousClock.now < pendingHangUp.deadline {")
expect(relay, #"send(type: "call_end", id: pendingHangUp.id.uuidString.lowercased())"#)

let dial = method(relay, "func dial(_ number: String)")
let prepare = method(relay, "func prepareOutgoingDial(")
let request = method(relay, "func requestOutgoingCall(")
let callDial = method(relay, "func callDial(")
expect(dial, "phoneCall == nil")
expectOrder(dial, "prepareOutgoingDial(id", "requestOutgoingCall(id")
expect(prepare, "phoneCall = PhoneCall(id: id")
expect(request, "CXStartCallAction(call: id,")
expect(callDial, "outgoingDial?.id == id")
expect(callDial, "outgoingDial?.sent == false")
expect(callDial, #""id": id.uuidString.lowercased()"#)

expect(dial, "outgoingCallIDs.insert(id)")
expect(callDial, "outgoingDial == nil, phoneCall == nil")
expectOrder(callDial, "outgoingCallIDs.insert(id).inserted", "prepareOutgoingDial(id")

expect(method(relay, "func phoneCallEnded("), "outgoingDial?.id != id")
let endPhoneCall = method(relay, "func endPhoneCall(")
expect(endPhoneCall, "call.id == id")
expect(endPhoneCall, "callHangUp(id)")
let route = method(relay, "func route(").components(separatedBy: "case .active:")
precondition(route.count == 2)
let callStates = route[1].components(separatedBy: "case .ended:")
precondition(callStates.count == 2)
expect(callStates[0], "phoneCallConnected(id")
expect(callStates[1], "closeOutgoingDial(id)")
expect(method(relay, "func closeOutgoingDial("), "outgoingDial?.id == id")

expect(method(relay, "func beginCallRecovery("), "if let outgoing = sentOutgoingCall")
let events = method(relay, "func handle(_ header:")
expect(events, "if matchesOutgoing, callReconnecting, call.state == .active")
expect(events, "!outgoingCallIDs.contains(id) || sentOutgoingCall?.id == id")
expect(events, "sentOutgoingCall?.id == id || queuedAnswer == id || calls.has(id) else { return }")

precondition(app.components(separatedBy: "presentVisibleCall()").count >= 4)
expect(method(app, "func updateCallWindow()"), "relay.phoneCall?.id == id")
expect(app, "call.id == minimizedCall")
expect(view, "call.id == callID")
expect(view, "relay.endPhoneCall(call.id)")

print("Call routing checks passed.")
