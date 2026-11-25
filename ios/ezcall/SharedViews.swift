import SwiftUI
import UIKit

/// Date labels in the style of Phone and Messages.
enum RelayDates {
    /// Recents and conversation rows: 10:42, Yesterday, Tuesday, 11/20/26.
    static func list(_ date: Date, now: Date = .now) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return date.formatted(.dateTime.hour().minute().locale(EzcallLanguage.current.locale)) }
        if calendar.isDateInYesterday(date) { return ezText("Yesterday") }
        if withinWeek(date, now: now) { return date.formatted(.dateTime.weekday(.wide).locale(EzcallLanguage.current.locale)) }
        return date.formatted(Date.FormatStyle(date: .numeric, time: .omitted).locale(EzcallLanguage.current.locale))
    }

    /// Day headings for call details and conversation separators: Today, Yesterday, Tuesday, Fri, Nov 20.
    static func day(_ date: Date, now: Date = .now) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return ezText("Today") }
        if calendar.isDateInYesterday(date) { return ezText("Yesterday") }
        if withinWeek(date, now: now) { return date.formatted(.dateTime.weekday(.wide).locale(EzcallLanguage.current.locale)) }
        if calendar.isDate(date, equalTo: now, toGranularity: .year) {
            return date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day().locale(EzcallLanguage.current.locale))
        }
        return date.formatted(.dateTime.month(.abbreviated).day().year().locale(EzcallLanguage.current.locale))
    }

    private static func withinWeek(_ date: Date, now: Date) -> Bool {
        let calendar = Calendar.current
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date),
                                           to: calendar.startOfDay(for: now)).day ?? 0
        return (2..<7).contains(days)
    }
}

/// A contact monogram like the one in Phone, Messages and Contacts.
struct Avatar: View {
    let name: String?
    var size: CGFloat = 40
    var conversationTint = false

    var body: some View {
        Circle()
            .fill(LinearGradient(colors: conversationTint
                                 ? [Color(red: 0.66, green: 0.76, blue: 0.88),
                                    Color(red: 0.46, green: 0.49, blue: 0.73)]
                                 : [Color(red: 0.65, green: 0.67, blue: 0.72),
                                          Color(red: 0.52, green: 0.54, blue: 0.58)],
                                 startPoint: .top, endPoint: .bottom))
            .overlay {
                if let initials = Self.initials(name) {
                    Text(initials)
                        .font(.system(size: size * 0.4, weight: .medium))
                        .foregroundStyle(.white)
                        .minimumScaleFactor(0.5)
                        .padding(size * 0.1)
                } else {
                    Image(systemName: "person.fill")
                        .font(.system(size: size * 0.5))
                        .foregroundStyle(.white)
                }
            }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }

    static func initials(_ name: String?) -> String? {
        guard let name else { return nil }
        let words = name.split(whereSeparator: \.isWhitespace).filter { $0.first?.isLetter == true }
        guard let first = words.first?.first else { return nil }
        let last = words.count > 1 ? words.last?.first : nil
        return (String(first) + (last.map { String($0) } ?? "")).uppercased()
    }
}

/// A contact card for a synced contact or a bare number, reached from Recents, Contacts and Messages.
@MainActor
struct ContactCard: View {
    @ObservedObject var relay: RelayController
    let name: String?
    let numbers: [String]
    var calls: [RelayCall] = []
    var showsMessage = true
    @State private var conversation: String?

    init(relay: RelayController, name: String?, numbers: [String], calls: [RelayCall] = [], showsMessage: Bool = true) {
        self.relay = relay
        self.name = name
        self.numbers = numbers
        self.calls = calls
        self.showsMessage = showsMessage
    }

    private var title: String { name ?? numbers.first ?? ezText("No Name") }

    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    Avatar(name: name, size: 88)
                    Text(title)
                        .font(.title.weight(.semibold))
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    if let number = numbers.first {
                        HStack(spacing: 8) {
                            if showsMessage {
                                actionTile("message", systemImage: "message.fill") {
                                    conversation = relay.threadAddress(for: number)
                                }
                            }
                            actionTile("call", systemImage: "phone.fill") { relay.dial(number) }
                        }
                        .padding(.top, 6)
                    }
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
            }

            if let latest = calls.first {
                Section(RelayDates.day(latest.timestamp)) {
                    ForEach(calls) { call in
                        HStack(spacing: 16) {
                            Text(call.timestamp, format: .dateTime.hour().minute().locale(EzcallLanguage.current.locale))
                                .monospacedDigit()
                            Text(Self.label(call.state))
                                .foregroundStyle(.secondary)
                        }
                        .font(.subheadline)
                        .accessibilityElement(children: .combine)
                    }
                }
            }

            if !numbers.isEmpty {
                Section {
                    ForEach(numbers, id: \.self) { number in
                        Button { relay.dial(number) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Phone").font(.subheadline).foregroundStyle(.primary)
                                Text(number).foregroundStyle(.tint)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .accessibilityLabel(ezText("Call %@", number))
                        .contextMenu {
                            Button("Copy", systemImage: "doc.on.doc") { UIPasteboard.general.string = number }
                            if showsMessage {
                                Button("Send Message", systemImage: "message") {
                                    conversation = relay.threadAddress(for: number)
                                }
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $conversation) { address in
            ConversationView(relay: relay, address: address)
        }
    }

    static func label(_ state: RelayCall.State) -> String {
        switch state {
        case .ringing: ezText("Ringing")
        case .active: ezText("On Call")
        case .ended: ezText("Call")
        }
    }

    private func actionTile(_ title: LocalizedStringKey, systemImage: String,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemImage).font(.title3)
                Text(title).font(.caption)
            }
            .foregroundStyle(.tint)
            .frame(maxWidth: .infinity, minHeight: 58)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: .rect(cornerRadius: 12))
        }
        .buttonStyle(.borderless)
    }
}

extension RelayController {
    /// Syncs and waits for Android so pull-to-refresh stays visible until the data arrives.
    func refresh() async {
        syncNow()
        let deadline = ContinuousClock.now + .seconds(20)
        while syncing, ContinuousClock.now < deadline {
            do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
        }
    }
}
