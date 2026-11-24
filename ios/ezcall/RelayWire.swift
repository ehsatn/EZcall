import CryptoKit
import Foundation

enum WireError: Error {
    case malformed, outOfOrder
}

enum RelayWire {
    static let limit = 65_536
    static let codePrefix = "xcall:v1:"

    static func pairingSecret(_ input: String) -> Data? {
        let token = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard token.hasPrefix(codePrefix) else { return nil }
        let code = String(token.dropFirst(codePrefix.count))
        guard code.utf8.count == 43,
              code.utf8.allSatisfy({
                  switch $0 {
                  case 65...90, 97...122, 48...57, 45, 95: return true
                  default: return false
                  }
              }),
              let key = Data(base64Encoded: code
                  .replacingOccurrences(of: "-", with: "+")
                  .replacingOccurrences(of: "_", with: "/") + "="),
              key.count == 32,
              key.base64EncodedString()
                  .replacingOccurrences(of: "+", with: "-")
                  .replacingOccurrences(of: "/", with: "_")
                  .replacingOccurrences(of: "=", with: "") == code
        else { return nil }
        return key
    }

    static func bytes<T: FixedWidthInteger>(_ integer: T) -> Data {
        var value = integer.bigEndian
        return withUnsafeBytes(of: &value) { Data($0) }
    }

    static func frame(_ body: Data) throws -> Data {
        guard (1...limit).contains(body.count) else { throw WireError.malformed }
        return bytes(UInt32(body.count)) + body
    }
}

// One sealed Opus packet per BLE audio write or notification. The audio key arrives in `audio_start`
// over the authenticated link. Skipped packets are allowed, but a sequence number is never accepted twice.
struct AudioSeal {
    static let androidToIPhone: UInt32 = 1
    static let iPhoneToAndroid: UInt32 = 2
    static let maxPayload = 1_024
    static let overhead = 21

    private let key: SymmetricKey
    private let outgoing: UInt32
    private let incoming: UInt32
    private var nextSend: UInt32 = 0
    private var lastReceived: Int64 = -1

    init(key: Data, outgoing: UInt32 = iPhoneToAndroid, incoming: UInt32 = androidToIPhone) throws {
        guard key.count == 32, outgoing != incoming else { throw WireError.malformed }
        self.key = SymmetricKey(data: key)
        self.outgoing = outgoing
        self.incoming = incoming
    }

    mutating func seal(_ payload: Data) throws -> Data {
        guard (1...Self.maxPayload).contains(payload.count), nextSend < UInt32.max else { throw WireError.malformed }
        let header = Data([5]) + RelayWire.bytes(nextSend)
        let box = try AES.GCM.seal(payload, using: key, nonce: Self.nonce(outgoing, nextSend), authenticating: header)
        nextSend += 1
        return header + box.ciphertext + box.tag
    }

    mutating func open(_ packet: Data) -> Data? {
        let bytes = [UInt8](packet)
        guard ((Self.overhead + 1)...(Self.maxPayload + Self.overhead)).contains(bytes.count), bytes[0] == 5 else { return nil }
        let sequence = bytes[1...4].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard Int64(sequence) > lastReceived,
              let nonce = try? Self.nonce(incoming, sequence),
              let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: Data(bytes[5..<(bytes.count - 16)]),
                                               tag: Data(bytes.suffix(16))),
              let payload = try? AES.GCM.open(box, using: key, authenticating: Data(bytes[0..<5]))
        else { return nil }
        lastReceived = Int64(sequence)
        return payload
    }

    private static func nonce(_ direction: UInt32, _ sequence: UInt32) throws -> AES.GCM.Nonce {
        try AES.GCM.Nonce(data: RelayWire.bytes(direction) + Data(count: 4) + RelayWire.bytes(sequence))
    }
}

struct FrameReader {
    private var buffer = Data()

    mutating func read(_ part: Data) throws -> [Data] {
        guard !part.isEmpty, part.count <= 512, buffer.count <= RelayWire.limit + 4 else {
            throw WireError.malformed
        }
        buffer.append(part)
        var frames = [Data]()
        while buffer.count >= 4 {
            let size = buffer.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
            guard (1...RelayWire.limit).contains(size) else { throw WireError.malformed }
            guard buffer.count >= 4 + size else { break }
            frames.append(Data(buffer.dropFirst(4).prefix(size)))
            buffer.removeFirst(4 + size)
        }
        return frames
    }
}

struct SecureLink {
    /// Replaces the pairing secret once Android accepts `rekey`, so a copied QR stops working.
    let next: Data
    private let outgoing: SymmetricKey
    private let incoming: SymmetricKey
    private var writeCounter: UInt64 = 0
    private var readCounter: UInt64 = 0

    init(secret: Data, clientKey: P256.KeyAgreement.PrivateKey, response: Data) throws {
        guard secret.count == 32, response.count == 98, response.first == 2 else {
            throw WireError.malformed
        }
        let serverBytes = Data(response.dropFirst().prefix(65))
        let transcript = clientKey.publicKey.x963Representation + serverBytes
        let preSharedKey = SymmetricKey(data: secret)
        guard HMAC<SHA256>.isValidAuthenticationCode(
            Data(response.suffix(32)),
            authenticating: Data("xcall/v1/android".utf8) + transcript,
            using: preSharedKey
        ) else { throw WireError.malformed }
        let remote = try P256.KeyAgreement.PublicKey(x963Representation: serverBytes)
        let shared = try clientKey.sharedSecretFromKeyAgreement(with: remote)
        let salt = Data(HMAC<SHA256>.authenticationCode(
            for: Data("xcall/v1/kdf".utf8) + transcript, using: preSharedKey
        ))
        outgoing = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: salt, sharedInfo: Data("xcall/v1/c2a".utf8), outputByteCount: 32
        )
        incoming = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: salt, sharedInfo: Data("xcall/v1/a2c".utf8), outputByteCount: 32
        )
        next = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: salt, sharedInfo: Data("xcall/v1/next".utf8), outputByteCount: 32
        ).withUnsafeBytes { Data($0) }
    }

    mutating func seal(_ plaintext: Data) throws -> Data {
        guard writeCounter < UInt64.max, plaintext.count <= RelayWire.limit - 25 else {
            throw WireError.malformed
        }
        let counter = RelayWire.bytes(writeCounter)
        let header = Data([3]) + counter
        let nonce = try AES.GCM.Nonce(data: Data(repeating: 0, count: 4) + counter)
        let box = try AES.GCM.seal(plaintext, using: outgoing, nonce: nonce, authenticating: header)
        writeCounter += 1
        return header + box.ciphertext + box.tag
    }

    mutating func open(_ frame: Data) throws -> Data {
        guard (25...RelayWire.limit).contains(frame.count), frame.first == 3,
              readCounter < UInt64.max else { throw WireError.malformed }
        let counter = RelayWire.bytes(readCounter)
        let header = Data(frame.prefix(9))
        guard header == Data([3]) + counter else { throw WireError.outOfOrder }
        let nonce = try AES.GCM.Nonce(data: Data(repeating: 0, count: 4) + counter)
        let box = try AES.GCM.SealedBox(
            nonce: nonce,
            ciphertext: Data(frame.dropFirst(9).dropLast(16)),
            tag: Data(frame.suffix(16))
        )
        let plaintext = try AES.GCM.open(box, using: incoming, authenticating: header)
        readCounter += 1
        return plaintext
    }

}

