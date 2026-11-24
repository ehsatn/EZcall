import SwiftUI
import UIKit

@MainActor
struct MessagesView: View {
    @ObservedObject var relay: RelayController
    @State private var search = ""
    @State private var composing = false

    let showCalls: () -> Void
    @FocusState private var searching: Bool

    private struct Thread: Identifiable {
        let id: String
        let latest: RelaySMS
    }

    private var threads: [Thread] {
        Dictionary(grouping: relay.data.messages, by: \.address).compactMap { address, messages in
            guard let latest = messages.max(by: { $0.date < $1.date }) else { return nil }
            let matches = search.isEmpty || relay.name(for: address).localizedCaseInsensitiveContains(search)
                || address.localizedCaseInsensitiveContains(search)
                || messages.contains { $0.body.localizedCaseInsensitiveContains(search) }
            return matches ? Thread(id: address, latest: latest) : nil
        }.sorted { $0.latest.date > $1.latest.date }
    }

    var body: some View {
        let threads = self.threads
        List(threads) { thread in
            // A hidden link keeps the row tappable while the chevron sits beside the date, as in Messages.
            ZStack {
                NavigationLink(value: thread.id) { EmptyView() }.opacity(0)
                row(thread)
            }
        }
        .listStyle(.plain)
        .overlay {
            if relay.data.messages.isEmpty {
                ContentUnavailableView("No Messages", systemImage: "message",
                    description: Text("SMS shared from your Android will appear here."))
            } else if threads.isEmpty {
                ContentUnavailableView.search(text: search)
            }
        }
        .navigationTitle("Messages")
        .navigationDestination(for: String.self) { address in
            ConversationView(relay: relay, address: address)
        }
        .toolbar(.hidden, for: .tabBar)
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: 8) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                    TextField("Search messages", text: $search)
                        .focused($searching)
                        .submitLabel(.search)
                        .onSubmit { searching = false }
                    if !search.isEmpty {
                        Button { search = "" } label: {
                            Image(systemName: "xmark.circle.fill")
                                .frame(width: 44, height: 44)
                        }
                        .accessibilityLabel("Clear search")
                    }
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .messageBarGlass()
                Button {
                    searching = false
                    showCalls()
                } label: {
                    Image(systemName: "phone.fill")
                        .font(.title3)
                        .frame(width: 48, height: 48)
                        .messageBarGlass()
                }
                .accessibilityLabel("Back to Calls")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("New Message", systemImage: "square.and.pencil") { composing = true }
            }
        }
        .sheet(isPresented: $composing) {
            NavigationStack { NewMessageView(relay: relay) }
        }
        .refreshable { await relay.refresh() }
    }

    private func row(_ thread: Thread) -> some View {
        HStack(spacing: 12) {
            Avatar(name: relay.contactName(for: thread.id), size: 44)
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(relay.name(for: thread.id))
                        .font(.headline)
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(RelayDates.list(thread.latest.timestamp))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Image(systemName: "chevron.forward")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                Text(thread.latest.body)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2, reservesSpace: true)
            }
            .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

private extension View {
    @ViewBuilder
    func messageBarGlass() -> some View {
        if #available(iOS 26, *) {
            glassEffect(.regular.interactive(), in: Capsule())
        } else {
            background(.regularMaterial, in: Capsule())
        }
    }
}

@MainActor
struct ConversationView: View {
    @ObservedObject var relay: RelayController
    let address: String
    @State private var showContact = false
    @State private var confirmRetry = false

    init(relay: RelayController, address: String) {
        self.relay = relay
        self.address = address
    }

    private struct Row: Identifiable {
        let id: String
        let body: String
        let date: Date
        let received: Bool
        var pending = false
        var separator = false
    }

    /// The SMS just sent from this conversation, until sync brings back Android's copy.
    private var pending: RelayController.OutgoingSMS? {
        guard let outgoing = relay.outgoingSMS, outgoing.address == address else { return nil }
        let synced = relay.data.messages.contains {
            $0.address == address && $0.direction != "in" && $0.body == outgoing.body
                && $0.timestamp > outgoing.date.addingTimeInterval(-300)
        }
        return synced ? nil : outgoing
    }

    private var pendingFailed: Bool {
        guard let pending, let result = relay.smsStatus else { return false }
        return result.id == pending.id && !result.sent
    }

    private var rows: [Row] {
        var rows = relay.data.messages.filter { $0.address == address }.sorted { $0.date < $1.date }
            .map { Row(id: $0.id, body: $0.body, date: $0.timestamp, received: $0.direction == "in") }
        if let pending {
            rows.append(Row(id: "pending-" + pending.id, body: pending.body, date: pending.date,
                            received: false, pending: true))
        }
        for index in rows.indices {
            rows[index].separator = index == 0 || rows[index].date.timeIntervalSince(rows[index - 1].date) > 3_600
        }
        return rows
    }

    private var contact: RelayContact? { relay.contact(for: address) }
    private var contactName: String? { contact.flatMap { $0.name.isEmpty ? nil : $0.name } }

    var body: some View {
        let rows = self.rows
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(rows) { row in
                        VStack(spacing: 0) {
                            if row.separator { separator(row.date, first: row.id == rows.first?.id) }
                            bubbleRow(row)
                                .font(.body)
                                .padding(.top, row.separator ? 0 : 10)
                        }
                    }
                    status
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: rows.last?.id) {
                withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo("bottom", anchor: .bottom) }
            }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Button { showContact = true } label: {
                    Avatar(name: contactName, size: 44, conversationTint: true)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(relay.name(for: address))
                .accessibilityHint("Shows contact details")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Call", systemImage: "phone") { relay.dial(address) }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            header
                .padding(.bottom, 8)
                .frame(maxWidth: .infinity)
        }
        .navigationDestination(isPresented: $showContact) {
            ContactCard(relay: relay, name: contactName,
                        numbers: contact.map { Array(Set($0.numbers)).sorted() } ?? [address],
                        showsMessage: false)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            SMSComposer(relay: relay, address: address, isNewMessage: false)
        }
        .confirmationDialog("Your message was not delivered.", isPresented: $confirmRetry,
                            titleVisibility: .visible) {
            Button("Try Again") { retry() }
            if let pending {
                Button("Delete Message", role: .destructive) { relay.discardOutgoingSMS(pending.id) }
            }
        } message: {
            if let message = relay.smsStatus?.message { Text(message) }
        }
    }

    private var header: some View {
        Button { showContact = true } label: {
            HStack(spacing: 4) {
                Text(relay.name(for: address)).lineLimit(1)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(.secondary)
            }
            .font(.headline)
            .foregroundStyle(.primary)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .messageBarGlass()
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .accessibilityLabel(relay.name(for: address))
        .accessibilityHint("Shows contact details")
    }

    private func separator(_ date: Date, first: Bool) -> some View {
        let time = date.formatted(date: .omitted, time: .shortened)
        return VStack(spacing: 2) {
            if first { Text("Text Message · SMS") }
            Text("\(Text(RelayDates.day(date)).fontWeight(.semibold)) \(time)")
        }
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .padding(.top, first ? 4 : 18)
            .padding(.bottom, 8)
    }

    private func bubbleRow(_ row: Row) -> some View {
        let text = Self.linked(row.body)
        let url = text.runs.count == 1 ? text.runs.first?.link : nil
        let webLink = url?.scheme?.lowercased() == "https" || url?.scheme?.lowercased() == "http"
        let shape = BubbleShape(received: row.received)
        return HStack(alignment: .center, spacing: 8) {
            Group {
                if webLink, let url {
                    Link(destination: url) {
                        HStack(spacing: 10) {
                            Text(verbatim: row.body)
                            Image(systemName: "safari.fill").accessibilityHidden(true)
                        }
                        .padding(.vertical, 6)
                    }
                    .foregroundStyle(.secondary)
                } else {
                    Text(text)
                        .foregroundStyle(row.received ? Color.primary : Color.white)
                        .tint(row.received ? Color.accentColor : Color.white)
                }
            }
                .frame(minWidth: 14)
                .padding(.horizontal, 15)
                .padding(.vertical, 9)
                .background(shape.fill(row.received || webLink ? Color(uiColor: .systemGray5) : Color(uiColor: .systemGreen)))
                .contentShape(.contextMenuPreview, shape)
                .contextMenu {
                    Button("Copy", systemImage: "doc.on.doc") { UIPasteboard.general.string = row.body }
                }
                .accessibilityLabel(row.received ? relay.name(for: address) + ", " + row.body : String(localized: "You") + ", " + row.body)
                .containerRelativeFrame(.horizontal, count: 4, span: 3, spacing: 0,
                                        alignment: row.received ? .leading : .trailing)
            if row.pending && pendingFailed {
                Button { confirmRetry = true } label: {
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.red)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Not Delivered")
                .accessibilityHint("Shows options to send the message again")
            }
        }
        .frame(maxWidth: .infinity, alignment: row.received ? .leading : .trailing)
    }

    @ViewBuilder
    private var status: some View {
        if let pending {
            Group {
                if pendingFailed {
                    Text("Not Delivered").foregroundStyle(.red)
                } else if relay.smsStatus?.id == pending.id {
                    Text("Sent")
                } else {
                    Text("Sending…")
                }
            }
            .font(.footnote.weight(.medium))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.trailing, pendingFailed ? 34 : 4)
            .padding(.top, 3)
        }
    }

    private func retry() {
        guard let pending else { return }
        relay.discardOutgoingSMS(pending.id)
        if relay.sendSMS(to: address, body: pending.body) == nil {
            relay.notice = String(localized: "Android isn’t ready to send SMS. Check the connection and try again.")
        }
    }

    /// Message text with tappable web links, as in Messages.
    static func linked(_ text: String) -> AttributedString {
        var result = AttributedString(text)
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else {
            return result
        }
        for match in detector.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let url = match.url, let range = Range(match.range, in: result) else { continue }
            result[range].link = url
            result[range].underlineStyle = .single
        }
        return result
    }
}

private struct BubbleShape: Shape {
    let received: Bool

    func path(in rect: CGRect) -> Path {
        let width = rect.width, height = rect.height
        let radius = min(20, min(width, height) / 2)
        var path = Path(roundedRect: CGRect(origin: .zero, size: rect.size),
                        cornerRadius: radius, style: .circular)
        var tail = Path()
        tail.move(to: CGPoint(x: width - 11, y: height - 10))
        tail.addQuadCurve(to: CGPoint(x: width - 7, y: height + 5),
                          control: CGPoint(x: width - 12, y: height - 1))
        tail.addQuadCurve(to: CGPoint(x: width - 22, y: height - 2),
                          control: CGPoint(x: width - 13, y: height + 5))
        tail.closeSubpath()
        path = path.union(tail)
        if received {
            path = path.applying(CGAffineTransform(translationX: width, y: 0).scaledBy(x: -1, y: 1))
        }
        return path.offsetBy(dx: rect.minX, dy: rect.minY)
    }
}

@MainActor
struct NewMessageView: View {
    @ObservedObject var relay: RelayController
    @Environment(\.dismiss) private var dismiss
    @State private var recipient = ""
    @State private var choosing = false
    @FocusState private var recipientFocused: Bool

    private struct Suggestion: Identifiable {
        let id: String
        let name: String
        let number: String
    }

    private var suggestions: [Suggestion] {
        let query = recipient.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return [] }
        let digits = query.filter(\.isNumber)
        return Array(relay.data.contacts.lazy.flatMap { contact -> [Suggestion] in
            let nameMatches = contact.name.localizedCaseInsensitiveContains(query)
            return Array(Set(contact.numbers)).sorted().compactMap { number -> Suggestion? in
                let numberMatches = !digits.isEmpty && number.filter(\.isNumber).contains(digits)
                guard nameMatches || numberMatches else { return nil }
                return Suggestion(id: contact.id + "|" + number,
                                  name: contact.name.isEmpty ? String(localized: "No Name") : contact.name,
                                  number: number)
            }
        }.prefix(50))
    }

    var body: some View {
        let suggestions = self.suggestions
        List(suggestions) { suggestion in
            Button {
                recipient = suggestion.number
                recipientFocused = false
            } label: {
                HStack(spacing: 12) {
                    Avatar(name: suggestion.name, size: 36)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(suggestion.name).foregroundStyle(.primary)
                        Text(suggestion.number).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .listStyle(.plain)
        .overlay(alignment: .top) {
            if suggestions.isEmpty {
                Text("SMS is sent from your Android’s SIM. Carrier charges may apply.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                    .padding(.top, 24)
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) { recipientField }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            SMSComposer(relay: relay, address: recipient, isNewMessage: true) { dismiss() }
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle("New Message")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Cancel") { dismiss() }.disabled(relay.sendingSMS)
            }
        }
        .interactiveDismissDisabled(relay.sendingSMS)
        .sheet(isPresented: $choosing) {
            NavigationStack {
                SMSRecipientView(contacts: relay.data.contacts) { recipient = $0 }
            }
        }
        .onAppear { recipientFocused = true }
    }

    private var recipientField: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Text("To:").foregroundStyle(.secondary)
                TextField("", text: $recipient)
                    .focused($recipientFocused)
                    .textContentType(.telephoneNumber)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .disabled(relay.sendingSMS)
                    .accessibilityLabel("To")
                if !relay.data.contacts.isEmpty {
                    Button("Choose Contact", systemImage: "plus.circle") { choosing = true }
                        .labelStyle(.iconOnly)
                        .font(.title3)
                        .frame(minWidth: 44, minHeight: 44)
                        .disabled(relay.sendingSMS)
                }
            }
            .padding(.leading, 16)
            .padding(.trailing, 8)
            .frame(minHeight: 44)
            Divider()
        }
        .background(Color(uiColor: .systemBackground))
    }
}

@MainActor
private struct SMSRecipientView: View {
    let contacts: [RelayContact]
    let choose: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""

    var body: some View {
        List {
            ForEach(contacts.filter(matches)) { contact in
                ForEach(Array(Set(contact.numbers)).sorted(), id: \.self) { number in
                    Button {
                        choose(number)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(contact.name.isEmpty ? String(localized: "No Name") : contact.name)
                                .foregroundStyle(.primary)
                            Text(number).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Contacts")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        }
    }

    private func matches(_ contact: RelayContact) -> Bool {
        search.isEmpty || contact.name.localizedCaseInsensitiveContains(search)
            || contact.numbers.contains { $0.localizedCaseInsensitiveContains(search) }
    }
}

/// The Messages-style text field with an inline send button.
@MainActor
private struct SMSComposer: View {
    @ObservedObject var relay: RelayController
    let address: String
    /// New Message checks the typed recipient and waits for Android before closing.
    /// A conversation clears the field at once and shows the bubble with its delivery state.
    let isNewMessage: Bool
    let sent: () -> Void
    @State private var text = ""
    @State private var requestID: String?
    @State private var problem: String?

    init(relay: RelayController, address: String, isNewMessage: Bool, sent: @escaping () -> Void = {}) {
        self.relay = relay
        self.address = address
        self.isNewMessage = isNewMessage
        self.sent = sent
    }

    private var waiting: Bool { relay.sendingSMS && requestID != nil && isNewMessage }
    private var hasText: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var ready: Bool { relay.status == .connected && relay.canSendSMS }

    var body: some View {
        VStack(spacing: 6) {
            if let hint {
                Text(hint)
                    .font(.footnote)
                    .foregroundStyle(problem == nil ? Color.secondary : Color.red)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            }
            field
        }
        .padding(.horizontal, 12)
        .padding(.top, 6)
        .padding(.bottom, 8)
        .onChange(of: relay.smsStatus?.id) {
            guard let result = relay.smsStatus, result.id == requestID, isNewMessage else { return }
            requestID = nil
            if result.sent {
                text = ""
                sent()
            } else {
                problem = result.message
            }
        }
        .onChange(of: text) { problem = nil }
    }

    private var hint: String? {
        if let problem { return problem }
        if relay.status != .connected { return String(localized: "Waiting for your Android to connect") }
        if !relay.canSendSMS { return String(localized: "Turn on Send SMS from the receiving phone in ezcall on Android.") }
        return nil
    }

    @ViewBuilder
    private var field: some View {
        let content = HStack(alignment: .bottom, spacing: 4) {
            TextField("Text Message · SMS", text: $text, axis: .vertical)
                .textFieldStyle(.plain)
                .font(.title3)
                .lineLimit((isNewMessage ? 3 : 1)...6)
                .disabled(waiting)
                .padding(.vertical, isNewMessage ? 12 : 8)
                .padding(.leading, 14)
            if waiting {
                ProgressView().frame(width: 44, height: 44)
            } else if hasText || isNewMessage {
                Button(action: send) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 30))
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, ready && hasText ? Color(uiColor: .systemGreen) : Color(uiColor: .systemGray3))
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!hasText || !ready || relay.sendingSMS)
                .accessibilityLabel("Send")
            }
        }
        .padding(.trailing, 3)
        .frame(minHeight: 40)
        .animation(.easeOut(duration: 0.15), value: hasText)

        if #available(iOS 26, *) {
            content.glassEffect(.regular.interactive(), in: .rect(cornerRadius: 20))
        } else {
            content
                .background(Color(uiColor: .systemBackground), in: .rect(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).strokeBorder(Color(uiColor: .systemGray4)))
        }
    }

    private func send() {
        problem = nil
        let recipient = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !recipient.isEmpty else {
            problem = String(localized: "Enter a phone number or choose a contact.")
            return
        }
        if isNewMessage, !recipient.allSatisfy({ $0.isNumber || " +-().".contains($0) }) || !recipient.contains(where: \.isNumber) {
            problem = String(localized: "Choose a contact or enter a phone number.")
            return
        }
        guard text.utf8.count <= 1_024 else {
            problem = String(localized: "This message is too long to send as SMS.")
            return
        }
        guard let id = relay.sendSMS(to: recipient, body: text) else {
            problem = String(localized: "Android isn’t ready to send SMS. Try again when connected.")
            return
        }
        requestID = id
        if !isNewMessage { text = "" }
    }
}
