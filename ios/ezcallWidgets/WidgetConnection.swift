import Foundation
import WidgetKit

enum WidgetConnection {
    static let kind = "ezcall.calls"
    private static let connectionFlag: URL? = {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "EzcallWidgetGroup") as? String else { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("relay-connected")
    }()

    static var isConnected: Bool {
        get {
            guard let connectionFlag else { return false }
            return FileManager.default.fileExists(atPath: connectionFlag.path)
        }
        set {
            guard let connectionFlag, isConnected != newValue else { return }
            do {
                if newValue {
                    try Data().write(to: connectionFlag, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                } else {
                    try FileManager.default.removeItem(at: connectionFlag)
                }
                WidgetCenter.shared.reloadTimelines(ofKind: kind)
            } catch {
                NSLog("Could not update the widget connection status")
            }
        }
    }
}
