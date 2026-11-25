import SwiftUI
import WidgetKit

@main
struct EzcallWidgets: Widget {
    var body: some WidgetConfiguration {
        // Keep the Calls kind so widgets placed by earlier versions update in place.
        StaticConfiguration(kind: WidgetConnection.kind, provider: ShortcutProvider()) { entry in
            VStack(spacing: 10) {
                HStack(spacing: 8) {
                    shortcut(.calls, title: "Calls", symbol: "phone.fill")
                    shortcut(.messages, title: "Messages", symbol: "message.fill")
                }
                HStack(spacing: 5) {
                    Circle()
                        .fill(entry.isConnected ? Color.green : Color.secondary)
                        .frame(width: 7, height: 7)
                        .accessibilityHidden(true)
                    Text(entry.isConnected ? LocalizedStringKey("Connected") : LocalizedStringKey("Disconnected"))
                        .font(.footnote.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .containerBackground(for: .widget) { Color(uiColor: .systemBackground) }
        }
        .configurationDisplayName("Calls & Messages")
        .description("Open ezcall’s call history or the iPhone’s Messages app.")
        .supportedFamilies([.systemSmall])
    }

    private func shortcut(_ destination: WidgetDestination, title: LocalizedStringKey, symbol: String) -> some View {
        Link(destination: destination.url) {
            Image(systemName: symbol)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.primary)
                .widgetAccentable()
                .frame(width: 50, height: 50)
                .background(.green.opacity(0.16), in: RoundedRectangle(cornerRadius: 14))
        }
        .accessibilityLabel(Text(title))
    }
}

private struct ShortcutProvider: TimelineProvider {
    struct Entry: TimelineEntry {
        let date = Date()
        let isConnected: Bool
    }

    func placeholder(in context: Context) -> Entry { Entry(isConnected: false) }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        completion(Entry(isConnected: WidgetConnection.isConnected))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        completion(Timeline(entries: [Entry(isConnected: WidgetConnection.isConnected)], policy: .never))
    }
}
