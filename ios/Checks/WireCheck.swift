import CryptoKit
import Foundation

private struct Vector: Decodable {
    let qr: String
    let clientPrivate: String
    let clientHello: String
    let androidHello: String
    let nextKey: String
    let ready: String
    let c2aReady: String
    let a2cReady: String
    let ping: String
    let c2aPing: String
    let audioKey: String
    let audioPayload: String
    let a2cAudio0: String
    let a2cAudio1: String
    let c2aAudio0: String
}

private enum CheckFailure: Error {
    case invalidFixture, failed(String), expectedRejection(String)
}

@main
struct WireCheck {
    static func main() throws {
        guard CommandLine.arguments.count == 2 else { throw CheckFailure.invalidFixture }
        let raw = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
        let vector = try JSONDecoder().decode(Vector.self, from: raw)
        guard let secret = RelayWire.pairingSecret(vector.qr) else { throw CheckFailure.invalidFixture }
        let client = try P256.KeyAgreement.PrivateKey(rawRepresentation: hex(vector.clientPrivate))
        let request = Data([1]) + client.publicKey.x963Representation
        try check(request == hex(vector.clientHello), "SEC1 client hellos match")

        let response = try hex(vector.androidHello)
        var link = try SecureLink(secret: secret, clientKey: client, response: response)
        try check(link.next == hex(vector.nextKey), "iOS derives the rotated pairing key")
        let ready = Data(vector.ready.utf8)
        let sealedReady = try link.seal(ready)
        try check(sealedReady == hex(vector.c2aReady), "iOS AES-GCM ready agrees with Android")
        let sealedPing = try link.seal(Data(vector.ping.utf8))
        try check(sealedPing == hex(vector.c2aPing), "iOS advances the outgoing counter")

        let androidReady = try hex(vector.a2cReady)
        let frame = try RelayWire.frame(androidReady)
        var reader = FrameReader()
        let first = try reader.read(Data(frame.prefix(2)))
        let second = try reader.read(Data(frame.dropFirst(2).prefix(19)))
        let completed = try reader.read(Data(frame.dropFirst(21)))
        try check(first.isEmpty && second.isEmpty && completed == [androidReady], "ATT fragments reassemble")
        let opened = try link.open(androidReady)
        try check(opened == ready, "Android AES-GCM ready opens on iOS")
        try reject("replayed counter") { _ = try link.open(androidReady) }
        try reject("wrong QR secret") {
            _ = try SecureLink(secret: Data(repeating: 255, count: 32), clientKey: client, response: response)
        }
        var corrupted = androidReady
        let last = corrupted.index(before: corrupted.endIndex)
        corrupted[last] ^= 1
        var fresh = try SecureLink(secret: secret, clientKey: client, response: response)
        try reject("tampered GCM tag") { _ = try fresh.open(corrupted) }
        try reject("invalid frame size") { _ = try reader.read(Data([0, 1, 0, 1])) }
        try check(RelayWire.pairingSecret(vector.qr + "=") == nil, "QR parser enforces canonical Base64URL")

        let audioKey = try hex(vector.audioKey)
        let audioPayload = try hex(vector.audioPayload)
        var iPhoneAudio = try AudioSeal(key: audioKey)
        try check(try iPhoneAudio.seal(audioPayload) == hex(vector.c2aAudio0), "iOS audio packet agrees with Android")
        let laterPacket = try hex(vector.a2cAudio1)
        try check(iPhoneAudio.open(laterPacket) == audioPayload, "Android audio opens on iOS after a gap")
        try check(iPhoneAudio.open(try hex(vector.a2cAudio0)) == nil, "Older audio sequence is rejected")
        try check(iPhoneAudio.open(laterPacket) == nil, "Replayed audio is rejected")
        var androidAudio = try AudioSeal(key: audioKey, outgoing: AudioSeal.androidToIPhone,
                                         incoming: AudioSeal.iPhoneToAndroid)
        try check(try androidAudio.seal(audioPayload) == hex(vector.a2cAudio0), "Audio directions use separate nonces")
        let singleNotification = try androidAudio.seal(hex("0002aabbcc"))
        var pairedAudio = try AudioSeal(key: audioKey)
        let pair = pairedAudio.open(singleNotification).flatMap(AudioSeal.splitPair)
        try check(pair?.0 == Data([0xaa, 0xbb]) && pair?.1 == Data([0xcc]), "iOS separates both 20 ms frames")
        for malformed in ["0000aabb", "0002aabb", "0003aabb", "0001aa"] {
            try check(try AudioSeal.splitPair(hex(malformed)) == nil, "iOS rejects an invalid audio pair")
        }
        let stale = RelayCall(id: "past", address: "1", date: 1, state: .active, simulated: nil, test_audio_confirmation: nil)
        let current = RelayCall(id: "current", address: "2", date: 2, state: .active, simulated: nil, test_audio_confirmation: nil)
        let message = RelaySMS(id: "sms", address: "1", body: "test", date: 1, direction: "in")
        let contact = RelayContact(id: "contact", name: "Test", numbers: ["1"])
        var data = RelayData(messages: [message], calls: [current, stale], contacts: [contact])
        try check(data.endCalls(except: current.id) && data.calls.map(\.state) == [.active, .ended], "Only the current call remains active")
        let saved = RelayData().retaining(data).retaining(data)
        try check(saved.messages == [message] && saved.contacts == [contact] && saved.calls.map(\.state) == [.ended, .ended],
                  "Saved data retains all categories and deduplicates exact records")

        let oldCache = Data(#"{"messages":[],"calls":[],"contacts":[]}"#.utf8)
        var inbox = try JSONDecoder().decode(RelayData.self, from: oldCache)
        let multipart = RelaySMS(id: "multipart", address: "synthetic", body: ["Test ", "message"].joined(),
                                 date: 101, direction: "in")
        try check(inbox.receiveSMS(message), "Live SMS can arrive during the initial import")
        inbox.mergeSMS([message], cursor: "1:1", more: true)
        try check(inbox.smsImportComplete == nil, "A partial page does not complete the import")
        let cachedInbox = try JSONEncoder().encode(inbox)
        inbox = try JSONDecoder().decode(RelayData.self, from: cachedInbox)
        try check(inbox.smsCursor == "1:1" && inbox.messages == [message], "Cold restore preserves both rows and their import cursor")
        inbox.mergeSMS([multipart], cursor: "101:2", more: false)
        try check(!inbox.receiveSMS(multipart), "Live delivery and catch-up deduplicate the same message")
        inbox.mergeSMS([], cursor: "101:2", more: false)
        try check(inbox.messages == [multipart, message] && inbox.smsImportComplete == true,
                  "An empty update keeps the local history and completed import")
        let older = (0..<501).map {
            RelaySMS(id: "older-\($0)", address: "1", body: "test", date: 0, direction: "in")
        }
        inbox.mergeSMS(older, cursor: nil, more: false)
        try check(inbox.messages.count == 503, "An import retains more than 500 messages")
        inbox.clearSMS()
        try check(inbox.messages.isEmpty && inbox.smsCursor == nil && inbox.smsImportComplete == nil,
                  "Revocation clears the inbox and import metadata")
        print("iOS wire fixture passed")
    }

    private static func check(_ condition: @autoclosure () throws -> Bool, _ label: String) throws {
        guard try condition() else { throw CheckFailure.failed(label) }
    }

    private static func reject(_ label: String, _ run: () throws -> Void) throws {
        do { try run() } catch { return }
        throw CheckFailure.expectedRejection(label)
    }

    private static func hex(_ string: String) throws -> Data {
        let bytes = Array(string.utf8)
        guard bytes.count.isMultiple(of: 2) else { throw CheckFailure.invalidFixture }
        var output = Data()
        for position in stride(from: 0, to: bytes.count, by: 2) {
            let pair = String(decoding: bytes[position..<(position + 2)], as: UTF8.self)
            guard let byte = UInt8(pair, radix: 16) else { throw CheckFailure.invalidFixture }
            output.append(byte)
        }
        return output
    }
}

