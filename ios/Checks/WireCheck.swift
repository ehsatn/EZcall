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

