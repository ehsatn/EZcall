import AppIntents
import Foundation

struct EzcallSiriShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: CallThroughAndroidIntent(),
            phrases: ["Make a call with \(.applicationName)"],
            shortTitle: "Call Through Android",
            systemImageName: "phone.fill"
        )
        AppShortcut(
            intent: SendAndroidSMSIntent(),
            phrases: ["Send a text with \(.applicationName)", "Send an SMS with \(.applicationName)"],
            shortTitle: "Send Android SMS",
            systemImageName: "message.fill"
        )
    }
}

struct CallThroughAndroidIntent: AppIntent {
    static let title: LocalizedStringResource = "Call Through Android"
    static let openAppWhenRun = true
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication
    @available(iOS 26, *) static var supportedModes: IntentModes { .foreground }

    @Parameter(title: "Recipient", mode: .phone, requestValueDialog: "Who would you like to call?")
    var recipient: IntentPerson

    static var parameterSummary: some ParameterSummary {
        Summary("Call \(\.$recipient) through Android")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let number = siriPhoneNumber(recipient, minimumDigits: 1) else {
            return .result(dialog: "Choose a phone number to call.")
        }
        let relay = try await connectedSiriRelay()
        guard relay.canDial else {
            return .result(dialog: "Turn on Call control in EasyCall on Android.")
        }
        guard relay.activeCall == nil, relay.phoneCall == nil else {
            return .result(dialog: "Finish your current call first.")
        }
        try Task.checkCancellation()
        relay.dial(number)
        return .result(dialog: "Call requested through Android.")
    }
}

struct SendAndroidSMSIntent: AppIntent {
    static let title: LocalizedStringResource = "Send Android SMS"
    static let openAppWhenRun = true
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication
    @available(iOS 26, *) static var supportedModes: IntentModes { .foreground }

    @Parameter(title: "Recipient", mode: .phone, requestValueDialog: "Who would you like to text?")
    var recipient: IntentPerson

    @Parameter(title: "Message", requestValueDialog: "What should the message say?")
    var message: String

    static var parameterSummary: some ParameterSummary {
        Summary("Send \(\.$message) to \(\.$recipient) through Android")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        guard let number = siriPhoneNumber(recipient, minimumDigits: 7) else {
            return .result(dialog: "Choose a valid phone number for the SMS.")
        }
        guard !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, message.utf8.count <= 1_024 else {
            return .result(dialog: "Enter a message of up to 1,024 bytes.")
        }
        guard try await $message.requestConfirmation(for: message, dialog: "Send this SMS to \(number) from Android? \(message)") else {
            return .result(dialog: "SMS canceled.")
        }
        let relay = try await connectedSiriRelay()
        guard relay.canSendSMS else {
            return .result(dialog: "Turn on Send SMS from the receiving phone in EasyCall on Android.")
        }
        try Task.checkCancellation()
        let result = await withCheckedContinuation { (continuation: CheckedContinuation<(Bool, String)?, Never>) in
            let request = relay.sendSMS(to: number, body: message) { sent, detail in
                continuation.resume(returning: (sent, detail))
            }
            if request == nil { continuation.resume(returning: nil) }
        }
        guard let (sent, detail) = result else {
            return .result(dialog: "EasyCall is already sending an SMS. Try again when it finishes.")
        }
        return sent ? .result(dialog: "SMS sent from Android.") : .result(dialog: "\(detail)")
    }
}

private func siriPhoneNumber(_ person: IntentPerson, minimumDigits: Int) -> String? {
    guard let handle = person.handle, case let .phoneNumber(raw) = handle.value else { return nil }
    let formatting = CharacterSet(charactersIn: "-().").union(.whitespaces)
    let number = raw.components(separatedBy: formatting).joined()
    let digits = number.hasPrefix("+") ? number.dropFirst() : number[...]
    guard (minimumDigits...15).contains(digits.count),
          digits.unicodeScalars.allSatisfy(CharacterSet.decimalDigits.contains) else { return nil }
    return number
}

private struct SiriRelayError: Error, CustomLocalizedStringResourceConvertible {
    let localizedStringResource: LocalizedStringResource
}

@MainActor
private func connectedSiriRelay() async throws -> RelayController {
    let relay = RelayController.shared
    relay.start()
    guard relay.isPaired else {
        throw SiriRelayError(localizedStringResource: "Link your Android to EasyCall first.")
    }
    let deadline = ContinuousClock.now + .seconds(12)
    while relay.status != .connected || !relay.receivedCapabilities {
        switch relay.status {
        case .bluetoothOff, .denied, .unsupported, .unavailable:
            throw SiriRelayError(localizedStringResource: "Check Bluetooth in EasyCall and try again.")
        default:
            guard ContinuousClock.now < deadline else {
                throw SiriRelayError(localizedStringResource: "EasyCall couldn’t connect to your Android.")
            }
            try await Task.sleep(for: .milliseconds(200))
        }
    }
    return relay
}
