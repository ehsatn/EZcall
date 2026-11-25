import Foundation

enum WidgetDestination: String {
    case calls = "app.xcall.relay.widget://calls"
    case messages = "app.xcall.relay.widget://messages"

    var url: URL { URL(string: rawValue)! }
}
