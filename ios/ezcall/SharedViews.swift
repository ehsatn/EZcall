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

    var body: some View {
        Circle()
            .fill(LinearGradient(colors: [Color(red: 0.66, green: 0.76, blue: 0.88),
                                          Color(red: 0.46, green: 0.49, blue: 0.73)],
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
                    Avatar(name: name, size: 176)
                        .overlay {
                            Circle().strokeBorder(.white.opacity(0.25), lineWidth: 1)
                        }
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
                .listRowInsets(EdgeInsets(top: 20, leading: 0, bottom: 16, trailing: 0))
            }

            if !calls.isEmpty {
                Section {
                    Text("Call History")
                        .font(.headline)
                        .accessibilityAddTraits(.isHeader)
                        .listRowBackground(Color.white.opacity(0.10))
                    ForEach(calls) { call in
                        HStack(spacing: 12) {
                            Text(Self.label(call.state))
                            Spacer()
                            Text(RelayDates.list(call.timestamp)).foregroundStyle(.secondary)
                        }
                        .font(.subheadline)
                        .accessibilityElement(children: .combine)
                        .listRowBackground(Color.white.opacity(0.10))
                    }
                }
            }

            if !numbers.isEmpty {
                Section {
                    ForEach(numbers, id: \.self) { number in
                        Button { relay.dial(number) } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text("Phone").font(.subheadline).foregroundStyle(.secondary)
                                    Text(number)
                                }
                                Spacer()
                                Image(systemName: "phone.fill").accessibilityHidden(true)
                            }
                            .foregroundStyle(.primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .listRowBackground(Color.white.opacity(0.10))
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
        .scrollContentBackground(.hidden)
        .background {
            LinearGradient(colors: [Color(red: 0.40, green: 0.43, blue: 0.52),
                                    Color(red: 0.24, green: 0.19, blue: 0.37),
                                    Color(red: 0.09, green: 0.07, blue: 0.16)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
                .ignoresSafeArea()
        }
        .environment(\.colorScheme, .dark)
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
            Image(systemName: systemImage).font(.title2)
                .foregroundStyle(.white)
                .frame(width: 58, height: 58)
                .background(.thinMaterial, in: Circle())
                .overlay { Circle().strokeBorder(.white.opacity(0.22), lineWidth: 1) }
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(title)
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
