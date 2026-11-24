import SwiftUI
import WidgetKit

@main
struct EzcallWidgets: WidgetBundle {
    var body: some Widget {
        ShortcutWidget(destination: .calls)
        ShortcutWidget(destination: .messages)
    }
}

private struct ShortcutWidget: Widget {
    let destination: WidgetDestination
    private var isCalls: Bool { destination == .calls }
    private var title: LocalizedStringKey { isCalls ? "Calls" : "Messages" }
    private var subtitle: LocalizedStringKey {
        isCalls ? "Open the call history in ezcall." : "Open your iPhone’s Messages app."
    }

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: isCalls ? "ezcall.calls" : "ezcall.messages", provider: ShortcutProvider()) { _ in
            Image(systemName: isCalls ? "phone" : "text.bubble")
                .font(.system(size: 48, weight: .medium))
                .foregroundStyle(.white)
                .widgetAccentable()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel(Text(title))
                .containerBackground(for: .widget) {
                    Color(red: 0, green: 0.20, blue: 0.82)
                }
                .widgetURL(destination.url)
        }
        .configurationDisplayName(Text(title))
        .description(Text(subtitle))
        .supportedFamilies([.systemSmall])
    }
}

private struct ShortcutProvider: TimelineProvider {
    struct Entry: TimelineEntry {
        let date = Date()
    }

    func placeholder(in context: Context) -> Entry { Entry() }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        completion(Entry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        completion(Timeline(entries: [Entry()], policy: .never))
    }
}
