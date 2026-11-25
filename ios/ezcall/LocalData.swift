import CryptoKit
import Foundation
import Security

struct RelaySMS: Codable, Identifiable, Hashable {
    let id: String
    let address: String
    let body: String
    let date: Double
    let direction: String

    var timestamp: Date { Date(timeIntervalSince1970: date / 1_000) }
}

struct RelayCall: Codable, Identifiable, Hashable {
    enum State: String, Codable, Hashable {
        case ringing, active, ended

        var label: String {
            switch self {
            case .ringing: "Incoming"
            case .active: "In progress"
            case .ended: "Ended"
            }
        }
    }

    let id: String
    let address: String
    let date: Double
    let state: State
    let simulated: Bool?
    let test_audio_confirmation: Bool?

    var timestamp: Date { Date(timeIntervalSince1970: date / 1_000) }

    var ended: RelayCall {
        RelayCall(id: id, address: address, date: date, state: .ended, simulated: simulated,
                  test_audio_confirmation: test_audio_confirmation)
    }
}

struct RelayContact: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let numbers: [String]
}

struct RelayData: Codable {
    var messages: [RelaySMS] = []
    var calls: [RelayCall] = []
    var contacts: [RelayContact] = []

    mutating func merge(_ messages: [RelaySMS]) {
        var byID: [String: RelaySMS] = [:]
        for message in self.messages + messages { byID[message.id] = message }
        self.messages = Array(byID.values.sorted { $0.date > $1.date }.prefix(500))
    }

    mutating func update(_ call: RelayCall) {
        calls.removeAll { $0.id == call.id }
        calls.insert(call, at: 0)
        if calls.count > 100 { calls.removeLast() }
    }

    mutating func replaceContacts(_ contacts: [RelayContact]) {
        var byID: [String: RelayContact] = [:]
        for contact in contacts { byID[contact.id] = contact }
        self.contacts = byID.values.sorted {
            $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    @discardableResult
    mutating func endCalls(matching id: String? = nil, except currentID: String? = nil) -> Bool {
        var changed = false
        for index in calls.indices where calls[index].state != .ended && calls[index].id != currentID {
            guard id == nil || calls[index].id == id else { continue }
            calls[index] = calls[index].ended
            changed = true
        }
        return changed
    }

    func retaining(_ source: RelayData) -> RelayData {
        RelayData(messages: Set(messages + source.messages).sorted { $0.date > $1.date },
                  calls: Set(calls + source.calls.map(\.ended)).sorted { $0.date > $1.date },
                  contacts: Set(contacts + source.contacts).sorted {
                      $0.name.localizedStandardCompare($1.name) == .orderedAscending
                  })
    }
}

struct PairingRecord: Codable {
    let secret: Data
    /// Key Android switches to after it accepts `rekey`. Kept until a handshake proves which one Android uses.
    let next: Data?
    let cacheKey: Data
    let peripheralID: UUID
}

enum LocalVault {
    enum Failure: Error { case keychain(OSStatus), corrupt }

    private static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "app.xcall.relay.pairing",
        kSecAttrAccount as String: "android"
    ]

    private static var archiveQuery: [String: Any] {
        query.merging([kSecAttrAccount as String: "archive"]) { _, new in new }
    }

    static func loadPairing() throws -> PairingRecord? {
        let marker = try directory().appendingPathComponent("installed")
        if !FileManager.default.fileExists(atPath: marker.path) {
            try removePairing()
            try removeArchive()
            try Data().write(to: marker, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return nil
        }
        guard let raw = try loadItem(query) else { return nil }
        let record = try JSONDecoder().decode(PairingRecord.self, from: raw)
        guard record.secret.count == 32, record.cacheKey.count == 32, record.next.map({ $0.count == 32 }) ?? true
        else { throw Failure.corrupt }
        return record
    }

    private static func loadItem(_ query: [String: Any]) throws -> Data? {
        var lookup = query
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw Failure.keychain(status) }
        guard let raw = item as? Data else { throw Failure.corrupt }
        return raw
    }

    static func savePairing(_ record: PairingRecord) throws {
        try saveItem(JSONEncoder().encode(record), query: query)
    }

    private static func saveItem(_ value: Data, query: [String: Any]) throws {
        let updates: [String: Any] = [kSecValueData as String: value]
        var status = SecItemUpdate(query as CFDictionary, updates as CFDictionary)
        if status == errSecItemNotFound {
            var attributes = query
            attributes[kSecValueData as String] = value
            attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(attributes as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw Failure.keychain(status) }
    }

    static func removePairing() throws {
        try removeItem(query, file: dataFile())
    }

    static func removeArchive() throws {
        try removeItem(archiveQuery, file: archiveFile())
    }

    private static func removeItem(_ query: [String: Any], file: URL) throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw Failure.keychain(status)
        }
        if FileManager.default.fileExists(atPath: file.path) { try? FileManager.default.removeItem(at: file) }
    }

    static func loadData(using key: Data) throws -> RelayData {
        try loadData(using: key, at: dataFile())
    }

    static func loadArchive() throws -> RelayData? {
        guard let key = try loadItem(archiveQuery) else { return nil }
        return try loadData(using: key, at: archiveFile())
    }

    static func retain(_ data: RelayData) throws -> RelayData {
        let archived = try (loadArchive() ?? RelayData()).retaining(data)
        let key: Data
        if let existing = try loadItem(archiveQuery) {
            key = existing
        } else {
            key = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
            try saveItem(key, query: archiveQuery)
        }
        try saveData(archived, using: key, at: archiveFile())
        return archived
    }

    private static func loadData(using key: Data, at path: URL) throws -> RelayData {
        guard key.count == 32 else { throw Failure.corrupt }
        guard FileManager.default.fileExists(atPath: path.path) else { return RelayData() }
        let encrypted = try Data(contentsOf: path)
        guard encrypted.count <= 8_388_608 else { throw Failure.corrupt }
        let box = try AES.GCM.SealedBox(combined: encrypted)
        let plain = try AES.GCM.open(box, using: SymmetricKey(data: key))
        return try JSONDecoder().decode(RelayData.self, from: plain)
    }

    static func saveData(_ data: RelayData, using key: Data) throws {
        try saveData(data, using: key, at: dataFile())
    }

    private static func saveData(_ data: RelayData, using key: Data, at path: URL) throws {
        let plain = try JSONEncoder().encode(data)
        guard let encrypted = try AES.GCM.seal(plain, using: SymmetricKey(data: key)).combined,
              encrypted.count <= 8_388_608 else { throw Failure.corrupt }
        try encrypted.write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private static func dataFile() throws -> URL {
        try directory().appendingPathComponent("relay.v1")
    }

    private static func archiveFile() throws -> URL {
        try directory().appendingPathComponent("archive.v1")
    }

    private static func directory() throws -> URL {
        let files = FileManager.default
        var directory = try files.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                      appropriateFor: nil, create: true)
            .appendingPathComponent("Xcall", isDirectory: true)
        try files.createDirectory(at: directory, withIntermediateDirectories: true)
        var attributes = URLResourceValues()
        attributes.isExcludedFromBackup = true
        try directory.setResourceValues(attributes)
        return directory
    }

}

