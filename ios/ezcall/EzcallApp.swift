import AVFoundation
import BackgroundTasks
import Contacts
import Intents
import SwiftUI
import UIKit
import UserNotifications

@main
@MainActor
struct EzcallApp: App {
    private static let refreshID = "app.xcall.relay.refresh"
    @UIApplicationDelegateAdaptor(EzcallDelegate.self) private var appDelegate
    @StateObject private var relay = RelayController.shared
    @State private var settings = false
    @State private var minimizedCall: UUID?
    @State private var pendingRedial: PendingRedial?
    @State private var redialError = ""
    @State private var showRedialError = false
    @State private var tab = HomeTab.recents
    @State private var lastCallTab = HomeTab.recents
    @State private var callListID = UUID()
    @State private var messagePath: [MessageDestination] = []
    @AppStorage("setupComplete") private var setupComplete = false
    @AppStorage(EzcallLanguage.preference) private var language = EzcallLanguage.en
    @Environment(\.scenePhase) private var scenePhase

    private struct PendingRedial: Equatable {
        let number: String
        let deadline: ContinuousClock.Instant
    }

    private enum HomeTab: String, CaseIterable {
        case recents = "Calls", contacts = "Contacts", keypad = "Keypad", search = "Search", messages = "Messages"

        var icon: String {
            switch self {
            case .recents: "clock.fill"
            case .contacts: "person.crop.circle.fill"
            case .keypad: "circle.grid.3x3.fill"
            case .search: "magnifyingglass"
            case .messages: "message.fill"
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            appContent
                .onOpenURL(perform: openWidget)
                .onContinueUserActivity("INStartCallIntent", perform: redial)
                .onContinueUserActivity("INStartAudioCallIntent", perform: redial)
                .onChange(of: relay.smsNotificationDestination, initial: true) { openSMSNotificationIfReady() }
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.protectedDataDidBecomeAvailableNotification)) { _ in
                    openSMSNotificationIfReady()
                }
                .onChange(of: setupComplete) { openSMSNotificationIfReady() }
                .task(id: scenePhase) {
                    guard scenePhase == .active else { return }
                    while !Task.isCancelled {
                        do { try await Task.sleep(for: .seconds(300)) } catch { return }
                        relay.checkForUpdates()
                    }
                }
                .onChange(of: relay.canDial) { dialRecentIfReady() }
                .task(id: pendingRedial) {
                    guard let redial = pendingRedial else { return }
                    do { try await Task.sleep(until: redial.deadline, clock: .continuous) }
                    catch { return }
                    guard !Task.isCancelled, pendingRedial == redial else { return }
                    dialRecentIfReady()
                }
                .alert("Couldn’t Redial", isPresented: $showRedialError) {
                    Button("OK", role: .cancel) {}
                } message: {
                    Text(ezText(redialError))
                }
                .environment(\.locale, language.locale)
                .environment(\.layoutDirection, language.direction)
        }
        .backgroundTask(.appRefresh(Self.refreshID)) {
            await relay.refreshInBackground()
            await scheduleRefresh()
        }
        .onChange(of: visibleCallID, initial: true) { presentVisibleCall() }
        .onChange(of: scenePhase, initial: true) {
            if scenePhase == .active {
                relay.becameActive()
                dialRecentIfReady()
                openSMSNotificationIfReady()
                presentVisibleCall()
            } else if scenePhase == .background {
                scheduleRefresh()
            }
        }
        .onChange(of: relay.isPaired) {
            if !relay.isPaired {
                BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.refreshID)
                settings = false
                pendingRedial = nil
                tab = .recents
            } else {
                openSMSNotificationIfReady()
            }
        }
    }

    private func scheduleRefresh() {
        guard relay.isPaired, relay.lastSyncedAt != nil else { return }
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    private func openSMSNotificationIfReady() {
        guard scenePhase == .active, relay.isPaired, setupComplete,
              UIApplication.shared.isProtectedDataAvailable,
              let destination = relay.smsNotificationDestination, let messageID = destination.messageID else { return }
        settings = false
        if let id = relay.phoneCall?.id { minimizedCall = id }
        tab = .messages
        messagePath = [destination]
        relay.consumeSMSNotification(messageID)
    }

    private func openWidget(_ url: URL) {
        switch WidgetDestination(rawValue: url.absoluteString) {
        case .calls:
            settings = false
            callListID = UUID()
            tab = .recents
        case .messages:
            UIApplication.shared.open(URL(string: "sms:")!)
        case nil:
            break
        }
    }

    private var visibleCallID: UUID? {
        guard let id = relay.phoneCall?.id, id != minimizedCall else { return nil }
        return id
    }

    private func presentVisibleCall() {
        appDelegate.presentCall(visibleCallID, relay: relay) { id in
            if relay.phoneCall?.id == id { minimizedCall = id }
        }
    }

    @ViewBuilder
    private var appContent: some View {
        if relay.isPaired && setupComplete {
            homeTabs
                .safeAreaInset(edge: .top, spacing: 0) {
                    if let call = relay.phoneCall, call.id == minimizedCall { ongoingBar(call) }
                }
                .onChange(of: tab) { _, selected in
                    if selected != .messages { lastCallTab = selected }
                }
                .sheet(isPresented: $settings) {
                    NavigationStack { RelaySettings(relay: relay) }
                }
                .alert(ezText(relay.notice ?? ""), isPresented: noticeShown) {
                    Button("OK", role: .cancel) {}
                }
        } else if !relay.isPaired {
            NavigationStack { PairView(relay: relay).toolbar { languageMenu } }
        } else {
            NavigationStack { SetupPermissionsView(relay: relay) { setupComplete = true }.toolbar { languageMenu } }
        }
    }

    @ToolbarContentBuilder private var languageMenu: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                EzcallLanguagePicker()
            } label: {
                Label("Language", systemImage: "globe")
            }
        }
        ToolbarItem(placement: .topBarLeading) {
            AppReviewLink().labelStyle(.iconOnly)
        }
    }

    private var noticeShown: Binding<Bool> {
        Binding(get: { relay.notice != nil && !settings },
                set: { if !$0 { relay.notice = nil } })
    }

    private func redial(_ activity: NSUserActivity) {
        let handle: INPersonHandle?
        switch activity.interaction?.intent {
        case let intent as INStartCallIntent:
            guard intent.callCapability != .videoCall, intent.destinationType != .voicemail,
                  intent.destinationType != .emergency else { return }
            let contacts = intent.contacts?.isEmpty == false ? intent.contacts : intent.callRecordToCallBack?.participants
            handle = contacts?.count == 1 ? contacts?.first?.personHandle : nil
        case let intent as INStartAudioCallIntent:
            handle = intent.contacts?.count == 1 ? intent.contacts?.first?.personHandle : nil
        default:
            return
        }
        guard let handle, handle.type == .phoneNumber,
              let number = handle.value, !number.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            showRedialFailure("This recent call doesn’t have a phone number.")
            return
        }
        relay.start()
        guard relay.isPaired else {
            showRedialFailure("Link your Android to ezcall, then try again.")
            return
        }
        pendingRedial = PendingRedial(number: number, deadline: ContinuousClock.now + .seconds(12))
        dialRecentIfReady()
    }

    private func dialRecentIfReady() {
        guard let redial = pendingRedial else { return }
        if ContinuousClock.now >= redial.deadline {
            pendingRedial = nil
            showRedialFailure("Connect to Android and turn on Call control, then try again.")
            return
        }
        guard relay.isPaired, relay.status == .connected, relay.canDial else { return }
        if relay.audioMode == .twoWay, AVAudioApplication.shared.recordPermission == .undetermined,
           UIApplication.shared.applicationState != .active { return }
        pendingRedial = nil
        relay.dial(redial.number)
    }

    private func showRedialFailure(_ message: String) {
        redialError = message
        showRedialError = true
    }

    @ViewBuilder
    private var homeTabs: some View {
        ZStack {
            phoneTabs
                .opacity(tab == .messages ? 0 : 1)
                .allowsHitTesting(tab != .messages)
                .accessibilityHidden(tab == .messages)
            if tab == .messages { homeContent(.messages) }
        }
    }

    @ViewBuilder
    private var phoneTabs: some View {
        let selection = Binding(get: { tab == .messages ? lastCallTab : tab }, set: { tab = $0 })
        if #available(iOS 26, *) {
            TabView(selection: selection) {
                Tab("Calls", systemImage: HomeTab.recents.icon, value: .recents) { homeContent(.recents) }
                Tab("Contacts", systemImage: HomeTab.contacts.icon, value: .contacts) { homeContent(.contacts) }
                Tab("Keypad", systemImage: HomeTab.keypad.icon, value: .keypad) { homeContent(.keypad) }
                Tab(value: .search, role: .search) { homeContent(.search) }
            }
            .tabViewStyle(.sidebarAdaptable)
        } else {
            TabView(selection: selection) {
                ForEach(HomeTab.allCases.filter { $0 != .messages }, id: \.self) { item in
                    homeContent(item)
                        .tabItem { Label(LocalizedStringKey(item.rawValue), systemImage: item.icon) }
                        .tag(item)
                }
            }
        }
    }

    @ViewBuilder
    private func homeContent(_ item: HomeTab) -> some View {
        switch item {
        case .keypad:
            KeypadView(relay: relay)
        case .recents:
            NavigationStack { RecentsView(relay: relay, openSettings: openSettings)
                    .homeActions(settings: openSettings, messages: { tab = .messages }) }
                .id(callListID)
        case .contacts:
            NavigationStack { ContactsView(relay: relay).homeActions(settings: openSettings) }
        case .search:
            NavigationStack { ContactsView(relay: relay, searchesCalls: true)
                    .homeActions(settings: openSettings, messages: { tab = .messages }) }
        case .messages:
            NavigationStack(path: $messagePath) {
                MessagesView(relay: relay) { tab = lastCallTab }
                    .homeActions(settings: openSettings)
            }
        }
    }

    private func openSettings() {
        settings = true
    }
}

private extension View {
    func homeActions(settings: @escaping () -> Void, messages: (() -> Void)? = nil) -> some View {
        toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(action: settings) {
                    Image(systemName: "gearshape").font(.title3.weight(.semibold))
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel("Settings")
                .tint(Color.primary)
            }
            if let messages {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: messages) {
                        Image(systemName: "message.fill").font(.title3.weight(.semibold))
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel("Messages")
                    .tint(Color.primary)
                }
            }
        }
    }
}

@MainActor
private final class EzcallDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private let privacyTag = 824_012
    private var callWindow: UIWindow?
    private weak var previousKeyWindow: UIWindow?
    private var requestedCall: (id: UUID, relay: RelayController, minimize: (UUID) -> Void)?
    private var presentedCallID: UUID?

    // A separate scene window can cover a composer or settings sheet without losing its contents.
    func presentCall(_ id: UUID?, relay: RelayController, minimize: @escaping (UUID) -> Void) {
        requestedCall = id.map { ($0, relay, minimize) }
        updateCallWindow()
    }

    private func updateCallWindow() {
        guard let (id, relay, minimize) = requestedCall, relay.phoneCall?.id == id else {
            let wasKey = callWindow?.isKeyWindow == true
            callWindow?.isHidden = true
            callWindow = nil
            presentedCallID = nil
            if wasKey { previousKeyWindow?.makeKey() }
            previousKeyWindow = nil
            return
        }
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }) else { return }
        if presentedCallID == id, let callWindow, callWindow.windowScene === scene {
            if !callWindow.isKeyWindow || callWindow.isHidden { callWindow.makeKeyAndVisible() }
            return
        }
        let window = callWindow ?? UIWindow(windowScene: scene)
        if window.windowScene !== scene { window.windowScene = scene }
        if callWindow == nil { previousKeyWindow = scene.keyWindow }
        window.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.normal.rawValue + 1)
        let language = EzcallLanguage.current
        window.rootViewController = UIHostingController(rootView: InCallView(relay: relay, callID: id) { minimize(id) }
            .environment(\.locale, language.locale)
            .environment(\.layoutDirection, language.direction))
        window.accessibilityViewIsModal = true
        callWindow = window
        presentedCallID = id
        window.makeKeyAndVisible()
    }

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: "relay-sms", actions: [], intentIdentifiers: [],
                                   hiddenPreviewsBodyPlaceholder: ezText("New message on Android"), options: [])
        ])
        RelayController.shared.start()
        NotificationCenter.default.addObserver(self, selector: #selector(coverScene(_:)),
                                               name: UIScene.willDeactivateNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(revealScene(_:)),
                                               name: UIScene.didActivateNotification, object: nil)
        return true
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                           willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        let request = notification.request
        if request.content.categoryIdentifier == "relay-sms" || request.identifier.hasPrefix("call-") {
            return [.banner, .list, .sound]
        }
        return []
    }

    func applicationProtectedDataDidBecomeAvailable(_ application: UIApplication) {
        RelayController.shared.protectedDataBecameAvailable()
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                           didReceive response: UNNotificationResponse,
                                           withCompletionHandler completionHandler: @escaping @Sendable () -> Void) {
        let request = response.notification.request
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier,
              request.content.categoryIdentifier == "relay-sms" || request.identifier.hasPrefix("message-") else {
            completionHandler()
            return
        }
        let identifier = request.content.userInfo["messageID"] as? String ?? request.identifier
        Task { @MainActor in
            RelayController.shared.enqueueSMSNotification(identifier)
            completionHandler()
        }
    }

    @objc private func coverScene(_ notification: Notification) {
        guard let scene = notification.object as? UIWindowScene else { return }
        for window in scene.windows where (window.isKeyWindow || window === previousKeyWindow) && window.viewWithTag(privacyTag) == nil {
            let cover = UIView(frame: window.bounds)
            cover.tag = privacyTag
            cover.backgroundColor = .systemBackground
            cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            cover.accessibilityViewIsModal = true
            window.addSubview(cover)
        }
    }

    @objc private func revealScene(_ notification: Notification) {
        guard let scene = notification.object as? UIWindowScene else { return }
        for window in scene.windows { window.viewWithTag(privacyTag)?.removeFromSuperview() }
        updateCallWindow()
    }
}

/// The large onboarding button used by Apple's setup screens.
private struct ProminentButton: View {
    let title: LocalizedStringKey
    var systemImage: String?
    let action: () -> Void

    var body: some View {
        let button = Button(action: action) {
            label
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 50)
        }
        if #available(iOS 26, *) {
            button.buttonStyle(.glassProminent).buttonBorderShape(.capsule)
        } else {
            button.buttonStyle(.borderedProminent).buttonBorderShape(.roundedRectangle(radius: 14))
        }
    }

    @ViewBuilder
    private var label: some View {
        if let systemImage {
            Label(title, systemImage: systemImage)
        } else {
            Text(title)
        }
    }
}

@MainActor
private struct PairView: View {
    @ObservedObject var relay: RelayController
    @Environment(\.openURL) private var openURL
    @State private var scanner = false

    var body: some View {
        ScrollView {
            VStack(spacing: 36) {
                VStack(spacing: 14) {
                    Image(systemName: "iphone.gen3.radiowaves.left.and.right")
                        .font(.system(size: 64))
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)
                    Text("Link Your Android")
                        .font(.largeTitle.bold())
                        .accessibilityAddTraits(.isHeader)
                    Text("See calls and texts from your Android on this iPhone.")
                        .foregroundStyle(.secondary)
                }
                .multilineTextAlignment(.center)
                .padding(.top, 40)

                VStack(alignment: .leading, spacing: 20) {
                    step("1.circle.fill", "Open ezcall on your Android and tap **Start pairing**.")
                    step("2.circle.fill", "Choose what to share and tap **Show QR code**.")
                    step("3.circle.fill", "Keep both phones nearby while they connect.")
                }

                if let saved = relay.retainedData {
                    NavigationLink("Saved Data") { SavedRelayDataView(data: saved) }
                }

                if let feedback = relay.feedback {
                    Label(LocalizedStringKey(feedback), systemImage: "exclamationmark.circle")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityAddTraits(.updatesFrequently)
                }
            }
            .padding(.horizontal, 32)
            .padding(.bottom, 24)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            pairingAction
                .frame(maxWidth: 440)
                .padding(.horizontal, 24)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity)
                .background(Color(uiColor: .systemBackground))
        }
        .sheet(isPresented: $scanner) {
            ScannerSheet { scannedCode in
                if relay.pair(with: scannedCode) {
                    scanner = false
                }
            }
        }
    }

    private func step(_ symbol: String, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 14) {
            Image(systemName: symbol)
                .font(.title2)
                .foregroundStyle(.tint)
                .frame(width: 30)
            Text(text).fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var pairingAction: some View {
        if relay.isPairing {
            VStack(spacing: 8) {
                HStack(spacing: 10) {
                    if relay.status.inProgress { ProgressView() }
                    Text(LocalizedStringKey(relay.status.title)).font(.headline)
                }
                .frame(minHeight: 50)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.updatesFrequently)

                if relay.status == .denied {
                    ProminentButton(title: "Open Settings", action: openSettings)
                }
                Button("Cancel") { relay.cancelPairing() }
                    .frame(minHeight: 44)
            }
        } else {
            ProminentButton(title: "Scan QR Code", systemImage: "qrcode.viewfinder") { scanner = true }
        }
    }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
    }
}

/// One permission per page with the system prompt behind the primary button, as in Apple's setup flows.
@MainActor
private struct SetupPermissionsView: View {
    @ObservedObject var relay: RelayController
    let finish: () -> Void
    @State private var microphoneStep = false
    @Environment(\.openURL) private var openURL

    private var needsMicrophone: Bool {
        relay.audioMode == .twoWay && relay.microphone != .granted
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: microphoneStep ? "mic.fill" : "bell.badge.fill")
                    .font(.system(size: 60))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(.tint)
                    .padding(.bottom, 8)
                    .accessibilityHidden(true)
                Text(LocalizedStringKey(microphoneStep ? "Talk Through iPhone" : "Get Call and Text Alerts"))
                    .font(.largeTitle.bold())
                    .accessibilityAddTraits(.isHeader)
                Text(LocalizedStringKey(microphoneStep
                     ? "Allow the microphone so callers can hear you on calls you answer or place through your Android."
                     : "ezcall can notify you when your Android gets a call or SMS. SMS alerts show the sender and message according to your iPhone’s Show Previews setting."))
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .padding(.horizontal, 32)
            .padding(.top, 80)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
            .id(microphoneStep)
            .transition(.opacity)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 8) {
                if microphoneStep { microphoneActions } else { notificationActions }
            }
            .frame(maxWidth: 440)
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
        }
        .animation(.easeInOut(duration: 0.25), value: microphoneStep)
        .onChange(of: relay.alerts) { old, new in
            if !microphoneStep, old == .notDetermined, new != .notDetermined { next() }
        }
    }

    @ViewBuilder
    private var notificationActions: some View {
        switch relay.alerts {
        case .notDetermined:
            ProminentButton(title: "Turn On Notifications") { relay.enableAlerts() }
            Button("Not Now", action: next).frame(minHeight: 44)
        case .denied:
            ProminentButton(title: "Continue", action: next)
            Button("Open Settings", action: openSettings).frame(minHeight: 44)
        default:
            ProminentButton(title: "Continue", action: next)
        }
    }

    @ViewBuilder
    private var microphoneActions: some View {
        if relay.microphone == .denied {
            ProminentButton(title: "Continue", action: finish)
            Button("Open Settings", action: openSettings).frame(minHeight: 44)
        } else {
            ProminentButton(title: "Allow Microphone") { relay.requestMicrophone(completion: finish) }
            Button("Not Now", action: finish).frame(minHeight: 44)
        }
    }

    private func next() {
        if needsMicrophone { microphoneStep = true } else { finish() }
    }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
    }
}

@MainActor
private struct RecentsView: View {
    @ObservedObject var relay: RelayController
    let openSettings: () -> Void
    @State private var detail: CallDetail?
    @State private var showStatus = false

    private struct CallDetail: Hashable {
        let address: String
        let ids: [String]
    }

    private struct CallGroup: Identifiable {
        let calls: [RelayCall]
        var id: String { calls[0].id }
        var latest: RelayCall { calls[0] }
    }

    /// Consecutive finished calls with the same number on the same day share a row, as in Phone.
    private var groups: [CallGroup] {
        let calendar = Calendar.current
        var groups: [[RelayCall]] = []
        for call in relay.data.calls.sorted(by: { $0.date > $1.date }) {
            if let last = groups.last?.last, last.address == call.address, last.state == .ended,
               call.state == .ended, calendar.isDate(last.timestamp, inSameDayAs: call.timestamp) {
                groups[groups.count - 1].append(call)
            } else {
                groups.append([call])
            }
        }
        return groups.map { CallGroup(calls: $0) }
    }

    var body: some View {
        let groups = self.groups
        List {
            if showStatus && relay.status != .connected {
                connectionRow
            }
            ForEach(groups) { group in row(group) }
        }
        .listStyle(.plain)
        .overlay {
            if relay.data.calls.isEmpty {
                ContentUnavailableView("No Calls", systemImage: "clock",
                    description: Text("Calls from your Android will appear here."))
            }
        }
        .navigationTitle("Calls")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $detail) { detail in card(for: detail) }
        .task(id: relay.status == .connected) {
            // Launch and short reconnects stay quiet; only a lasting problem shows the status row.
            guard relay.status != .connected else {
                showStatus = false
                return
            }
            do { try await Task.sleep(for: .seconds(2)) } catch { return }
            withAnimation { showStatus = true }
        }
    }

    private var connectionRow: some View {
        Button(action: openSettings) {
            HStack(spacing: 10) {
                if relay.status.inProgress {
                    ProgressView()
                } else {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                }
                Text(LocalizedStringKey(relay.status.title)).foregroundStyle(.secondary)
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .font(.subheadline)
        }
        .accessibilityHint("Opens Settings")
    }

    private func row(_ group: CallGroup) -> some View {
        let call = group.latest
        let contactName = relay.contactName(for: call.address)
        let count = group.calls.count > 1 ? " (\(group.calls.count))" : ""
        return HStack(spacing: 10) {
            Button { showDetail(group) } label: {
                HStack(spacing: 12) {
                    Avatar(name: contactName, size: 46)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(relay.name(for: call.address) + count)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Text(subtitle(call, named: contactName != nil))
                            .font(.subheadline)
                            .foregroundStyle(call.state == .ended ? Color.secondary : Color.green)
                            .lineLimit(1)
                    }
                    .frame(minHeight: 42)
                    Spacer(minLength: 8)
                    Text(RelayDates.list(call.timestamp))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityHint("Shows details")

            Button { relay.dial(call.address) } label: {
                Image(systemName: "phone.fill").font(.body.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .background(Color(uiColor: .secondarySystemBackground), in: Circle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Call")
            .disabled(call.address.isEmpty)
        }
        .padding(.vertical, 7)
    }

    private func subtitle(_ call: RelayCall, named: Bool) -> String {
        guard call.state == .ended else { return ContactCard.label(call.state) }
        return named ? call.address : ezText("Phone")
    }

    private func showDetail(_ group: CallGroup) {
        detail = CallDetail(address: group.latest.address, ids: group.calls.map(\.id))
    }

    private func card(for detail: CallDetail) -> some View {
        let contact = relay.contact(for: detail.address)
        let calls = relay.data.calls.filter { detail.ids.contains($0.id) }.sorted { $0.date > $1.date }
        return ContactCard(relay: relay,
                           name: contact.flatMap { $0.name.isEmpty ? nil : $0.name },
                           numbers: contact.map { Array(Set($0.numbers)).sorted() }
                               ?? (detail.address.isEmpty ? [] : [detail.address]),
                           calls: calls)
    }

}

private extension EzcallApp {
    func ongoingBar(_ call: RelayController.PhoneCall) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(relay.name(for: call.address))
                    .font(.headline)
                    .lineLimit(1)
                Group {
                    if call.endFailed {
                        Text("Couldn’t end the call. Try again.")
                    } else if relay.callReconnecting {
                        Text("Reconnecting…")
                    } else if let connectedAt = call.connectedAt {
                        Text(connectedAt, style: .timer).monospacedDigit()
                    } else {
                        Text("Calling…")
                    }
                }
                .font(.subheadline)
                .foregroundStyle(call.endFailed ? Color.red : Color.green)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
            Button {
                if relay.phoneCall?.id == call.id { minimizedCall = nil }
            } label: {
                Image(systemName: "arrow.up.left.and.arrow.down.right")
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Return to call")
            Button(role: .destructive) {
                relay.endPhoneCall(call.id)
            } label: {
                Image(systemName: "phone.down.fill")
                    .font(.title3)
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Color(uiColor: .systemRed)))
            }
            .buttonStyle(PressDimStyle())
            .accessibilityLabel("End Call")
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
        .background(.bar)
    }
}

/// Dims a custom-drawn control while it is held down.
private struct PressDimStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.6 : 1)
            .animation(configuration.isPressed ? nil : .easeOut(duration: 0.2), value: configuration.isPressed)
    }
}

private struct KeypadKeyStyle: ButtonStyle {
    let size: CGFloat
    var tint: Color?

    func makeBody(configuration: Configuration) -> some View {
        let label = configuration.label.frame(width: size, height: size).contentShape(Circle())
        if #available(iOS 26, *) {
            label.glassEffect(.regular.tint(tint).interactive(), in: Circle())
                .opacity(configuration.isPressed ? 0.75 : 1)
        } else {
            label.background(Circle().fill(tint ?? Color(uiColor: .systemGray5)))
                .opacity(configuration.isPressed ? 0.65 : 1)
        }
    }
}

@MainActor
private struct KeypadView: View {
    @ObservedObject var relay: RelayController
    @Environment(\.scenePhase) private var scenePhase
    @State private var phoneContacts: [RelayContact] = []
    @State private var contactRevision = 0
    @State private var number = ""
    @State private var lastDialed = ""
    @State private var tones = KeypadTones()
    @State private var showingMatches = false
    @State private var enteredPlus = false

    private struct Match: Identifiable {
        let id: String
        let name: String
        let number: String
    }

    private let keys = [("1", ""), ("2", "ABC"), ("3", "DEF"), ("4", "GHI"), ("5", "JKL"), ("6", "MNO"),
                        ("7", "PQRS"), ("8", "TUV"), ("9", "WXYZ"), ("*", ""), ("0", "+"), ("#", "")]

    var body: some View {
        GeometryReader { proxy in
            let key = Self.keySize(for: proxy.size)
            ScrollView {
                VStack(spacing: 0) {
                    Spacer(minLength: 16)
                    display
                    Spacer(minLength: 16)
                    Grid(horizontalSpacing: key * 0.36, verticalSpacing: key * 0.2) {
                        ForEach(0..<4, id: \.self) { row in
                            GridRow {
                                ForEach(0..<3, id: \.self) { column in
                                    let (digit, letters) = keys[row * 3 + column]
                                    digitKey(digit, letters: letters, size: key)
                                }
                            }
                        }
                        GridRow {
                            Color.clear.frame(width: key, height: key).accessibilityHidden(true)
                            callKey(size: key)
                            deleteKey(size: key)
                        }
                    }
                    .padding(.bottom, 24)
                }
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .onChange(of: relay.phoneCall?.id) {
            if relay.phoneCall != nil { number = "" }
        }
        .onReceive(NotificationCenter.default.publisher(for: .CNContactStoreDidChange)) { _ in
            contactRevision += 1
        }
        .task(id: scenePhase == .active ? contactRevision : -1) {
            if scenePhase == .active { await loadPhoneContacts() }
            else { phoneContacts = [] }
        }
        .sheet(isPresented: $showingMatches) {
            NavigationStack {
                List(matches) { match in
                    Button {
                        select(match)
                        showingMatches = false
                    } label: {
                        HStack(spacing: 12) {
                            Avatar(name: match.name, size: 36)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(match.name).foregroundStyle(.primary)
                                Text(match.number).font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .listStyle(.plain)
                .navigationTitle("Matching Contacts")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { showingMatches = false }
                    }
                }
            }
            .presentationDetents([.medium, .large])
        }
    }

    private static func keySize(for size: CGSize) -> CGFloat {
        let byWidth = (min(size.width, 400) - 64) / 3.72
        let byHeight = (size.height - 150) / 5.8
        return max(60, min(78, byWidth, byHeight))
    }

    private var display: some View {
        let candidates = matches
        return VStack(spacing: 4) {
            Text(number)
                .font(.system(size: 40))
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .truncationMode(.head)
                .frame(maxWidth: .infinity, minHeight: 50)
                .padding(.horizontal, 24)
                .contextMenu {
                    if !number.isEmpty {
                        Button("Copy", systemImage: "doc.on.doc") { UIPasteboard.general.string = number }
                    }
                }
                .accessibilityLabel(number.isEmpty ? ezText("No number entered") : number)

            if let first = candidates.first {
                VStack(alignment: .leading, spacing: 0) {
                    Button { select(first) } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "person.crop.circle.fill")
                            Text(first.name).foregroundStyle(.primary).lineLimit(1)
                            Spacer(minLength: 4)
                            Text(first.number).foregroundStyle(.secondary).lineLimit(1)
                                .layoutPriority(1)
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    if candidates.count > 1 {
                        Divider()
                        Button { showingMatches = true } label: {
                            Label(ezText("%lld more", Int64(candidates.count - 1)), systemImage: "magnifyingglass")
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                .contentShape(Rectangle())
                        }
                    }
                }
                .font(.subheadline)
                .buttonStyle(.plain)
                .padding(.horizontal, 14)
                .frame(maxWidth: 320)
                .background(.thinMaterial, in: .rect(cornerRadius: 20))
                .padding(.horizontal, 24)
            } else {
                Color.clear.frame(height: 24).accessibilityHidden(true)
            }
        }
    }

    private var matches: [Match] {
        let typed = Self.digits(in: number)
        guard typed.count >= 4, !number.contains("*"), !number.contains("#") else { return [] }
        let international = number.hasPrefix("+") || typed.hasPrefix("00")
        let prefix = typed.hasPrefix("00") ? String(typed.dropFirst(2)) : typed
        let localPrefix = !international && typed.hasPrefix("0") ? String(typed.dropFirst()) : nil
        var seen = Set<String>()
        return (phoneContacts + relay.data.contacts).flatMap { contact in
            Set(contact.numbers).sorted().compactMap { address in
                let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
                let body = trimmed.hasPrefix("+") ? trimmed.dropFirst() : trimmed[...]
                guard body.allSatisfy({ $0.isNumber || $0.isWhitespace || "-().".contains($0) }) else { return nil }
                let digits = Self.digits(in: trimmed)
                guard (1...63).contains(digits.count) else { return nil }
                let normalized = digits.hasPrefix("00") ? String(digits.dropFirst(2)) : digits
                let formattedInternational = trimmed.hasPrefix("+") || digits.hasPrefix("00")
                let matchesLocal = localPrefix.map { local in
                    formattedInternational && (1...3).contains { normalized.dropFirst($0).hasPrefix(local) }
                } ?? false
                guard normalized.hasPrefix(prefix) || matchesLocal else { return nil }
                guard seen.insert(normalized).inserted else { return nil }
                let name = contact.name.isEmpty ? ezText("No Name") : contact.name
                return Match(id: contact.id + "|" + address, name: name, number: address)
            }
        }
    }

    private func loadPhoneContacts() async {
        let fetched = await Task.detached(priority: .userInitiated) {
            let status = CNContactStore.authorizationStatus(for: .contacts)
            if status != .authorized {
                guard #available(iOS 18, *), status == .limited else { return [RelayContact]() }
            }
            let keys: [CNKeyDescriptor] = [CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
                        CNContactPhoneNumbersKey as NSString]
            let request = CNContactFetchRequest(keysToFetch: keys)
            request.sortOrder = .userDefault
            var contacts: [RelayContact] = []
            do {
                try CNContactStore().enumerateContacts(with: request) { contact, _ in
                    let numbers = contact.phoneNumbers.map { $0.value.stringValue }
                    guard !numbers.isEmpty else { return }
                    contacts.append(RelayContact(id: "iphone:" + contact.identifier,
                                                 name: CNContactFormatter.string(from: contact, style: .fullName) ?? "",
                                                 numbers: numbers))
                }
                return contacts
            } catch {
                return []
            }
        }.value
        if !Task.isCancelled, scenePhase == .active { phoneContacts = fetched }
    }

    private static func digits(in value: String) -> String {
        value.compactMap { character in
            guard let digit = character.wholeNumberValue, (0...9).contains(digit) else { return nil }
            return String(digit)
        }.joined()
    }

    private func select(_ match: Match) {
        let address = match.number.trimmingCharacters(in: .whitespacesAndNewlines)
        let digits = Self.digits(in: address)
        number = address.hasPrefix("+") ? "+" + digits : digits
    }

    private func digitKey(_ digit: String, letters: String, size: CGFloat) -> some View {
        Button {
            // Holding 0 already entered "+"; the release shouldn't add a 0 as well.
            if enteredPlus {
                enteredPlus = false
                return
            }
            appendDigit(digit)
        } label: {
            VStack(spacing: 0) {
                if digit == "*" {
                    Image(systemName: "asterisk").font(.system(size: size * 0.3, weight: .medium))
                } else {
                    Text(digit).font(.system(size: size * 0.46))
                }
                if !letters.isEmpty || digit == "1" {
                    Text(letters.isEmpty ? " " : letters)
                        .font(.system(size: size * 0.13, weight: .bold))
                        .tracking(size * 0.025)
                        .padding(.top, -size * 0.04)
                }
            }
            .foregroundStyle(.primary)
        }
        .buttonStyle(KeypadKeyStyle(size: size))
        .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
            guard digit == "0", number.isEmpty else { return }
            enteredPlus = true
            appendDigit("+")
        })
        .accessibilityLabel(digit)
        .accessibilityActions {
            if digit == "0" { Button("Enter Plus") { appendDigit("+") } }
        }
    }

    private func callKey(size: CGFloat) -> some View {
        Button(action: call) {
            Image(systemName: "phone.fill")
                .font(.system(size: size * 0.36))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
        }
        .buttonStyle(KeypadKeyStyle(size: size, tint: Color(uiColor: .systemGreen)))
        .accessibilityLabel("Call")
        .disabled(relay.phoneCall != nil)
    }

    private func deleteKey(size: CGFloat) -> some View {
        Button {
            if !number.isEmpty { number.removeLast() }
        } label: {
            Image(systemName: "delete.left.fill")
                .font(.system(size: size * 0.3))
                .foregroundStyle(Color(uiColor: .systemGray2))
                .frame(width: size, height: size)
                .contentShape(Rectangle())
        }
        .buttonStyle(KeypadKeyStyle(size: size))
        .buttonRepeatBehavior(.enabled)
        .opacity(number.isEmpty ? 0 : 1)
        .disabled(number.isEmpty)
        .accessibilityLabel("Delete")
    }

    /// Like Phone, an empty call press brings back the last number dialed here.
    private func call() {
        guard !number.isEmpty else {
            number = lastDialed
            return
        }
        lastDialed = number
        relay.dial(number)
    }

    private func appendDigit(_ digit: String) {
        guard number.count < 64, digit != "+" || number.isEmpty else { return }
        number += digit
        if relay.canPlayKeypadTones { tones.play(digit) }
    }
}

@MainActor
private struct ContactsView: View {
    @ObservedObject var relay: RelayController
    var searchesCalls = false
    @State private var search = ""
    @State private var selectedContact: ContactRoute?

    private struct ContactRoute: Hashable {
        let id: String
    }

    private var visibleContacts: [RelayContact] {
        if search.isEmpty { return relay.data.contacts }
        return relay.data.contacts.filter { contact in
            contact.name.localizedCaseInsensitiveContains(search) ||
            contact.numbers.contains { $0.localizedCaseInsensitiveContains(search) }
        }
    }

    var body: some View {
        if searchesCalls {
            contactList.searchable(text: $search, prompt: "Search")
        } else {
            contactList
        }
    }

    private var contactList: some View {
        let visible = visibleContacts
        let matchingCalls = searchesCalls && !search.isEmpty ? relay.data.calls.filter {
            relay.name(for: $0.address).localizedCaseInsensitiveContains(search)
                || $0.address.localizedCaseInsensitiveContains(search)
        }.sorted { $0.date > $1.date } : []
        let sections = Dictionary(grouping: visible) { contact in
            guard let initial = contact.name.trimmingCharacters(in: .whitespacesAndNewlines)
                    .folding(options: .diacriticInsensitive, locale: .current).first, initial.isLetter else { return "#" }
            return String(initial).uppercased(with: .current)
        }
        let initials = sections.keys.sorted { left, right in
            if left == "#" { return false }
            if right == "#" { return true }
            return left.localizedStandardCompare(right) == .orderedAscending
        }

        return List {
            if searchesCalls {
                if !search.isEmpty {
                    if !visible.isEmpty { Section("Contacts") { ForEach(visible, content: contactRow) } }
                    if !matchingCalls.isEmpty {
                        Section("Calls") {
                            ForEach(matchingCalls) { call in
                                NavigationLink {
                                    ContactCard(relay: relay, name: relay.contactName(for: call.address),
                                                numbers: call.address.isEmpty ? [] : [call.address], calls: [call])
                                } label: {
                                    HStack {
                                        Avatar(name: relay.contactName(for: call.address), size: 44)
                                        Text(relay.name(for: call.address)).lineLimit(1)
                                        Spacer()
                                        Text(RelayDates.list(call.timestamp)).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                ForEach(initials, id: \.self) { initial in
                    let section = Section(initial) {
                        ForEach(sections[initial, default: []], content: contactRow)
                    }
                    if #available(iOS 26, *) {
                        section.sectionIndexLabel(Text(initial))
                    } else {
                        section
                    }
                }
                if !visible.isEmpty {
                    Text(ezText("Contacts: %lld", Int64(visible.count)))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .listRowSeparator(.hidden)
                }
            }
        }
        .listStyle(.plain)
        .overlay {
            if !searchesCalls && relay.data.contacts.isEmpty {
                ContentUnavailableView("No Contacts", systemImage: "person.crop.circle",
                    description: Text("Contacts shared from your Android will appear here."))
            } else if searchesCalls && !search.isEmpty && visible.isEmpty && matchingCalls.isEmpty {
                ContentUnavailableView.search(text: search)
            }
        }
        .navigationTitle(searchesCalls ? LocalizedStringKey("Search") : LocalizedStringKey("Contacts"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $selectedContact) { route in
            if let contact = relay.data.contacts.first(where: { $0.id == route.id }) {
                ContactCard(relay: relay, name: contact.name.isEmpty ? nil : contact.name,
                            numbers: Array(Set(contact.numbers)).sorted())
            }
        }
        .refreshable { await relay.refresh() }
    }

    private func contactRow(_ contact: RelayContact) -> some View {
        Button {
            selectedContact = ContactRoute(id: contact.id)
        } label: {
            HStack(spacing: 14) {
                Avatar(name: contact.name, size: 46)
                Text(contact.name.isEmpty ? ezText("No Name") : contact.name)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

@MainActor
private struct RelaySettings: View {
    @ObservedObject var relay: RelayController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var confirmForget = false

    var body: some View {
        Form {
            Section {
                LabeledContent("Status") {
                    HStack(spacing: 8) {
                        if relay.status.inProgress { ProgressView() }
                        Text(LocalizedStringKey(relay.status.title))
                    }
                }
                Button(LocalizedStringKey(relay.testing ? "Testing…" : "Test Connection")) { relay.testConnection() }
                    .disabled(relay.status != .connected || relay.testing)
                if let result = relay.testResult {
                    Text(LocalizedStringKey(result)).font(.footnote).foregroundStyle(.secondary)
                }
                if relay.status == .denied {
                    Button("Allow Bluetooth in Settings", action: openSettings)
                }
                if relay.status != .connected, let feedback = relay.feedback {
                    Text(LocalizedStringKey(feedback)).font(.footnote).foregroundStyle(.secondary)
                }
            } header: {
                Text("Android")
            } footer: {
                Text("Keep Bluetooth on and ezcall running on your Android. The phones reconnect automatically when they’re nearby.")
            }

            Section {
                if relay.syncing {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(relay.importingSMS ? "Importing SMS History…" : "Syncing…")
                    }
                    .accessibilityElement(children: .combine)
                } else {
                    Button { relay.syncNow() } label: {
                        Label("Check for Updates", systemImage: "arrow.clockwise")
                    }
                    .disabled(!relay.canSync)
                }
                LabeledContent("Last Synced") {
                    if let date = relay.lastSyncedAt {
                        Text(date, format: .dateTime.month().day().hour().minute())
                    } else {
                        Text("Never")
                    }
                }
                if let result = relay.syncResult {
                    Text(LocalizedStringKey(result)).font(.footnote).foregroundStyle(.secondary)
                }
            } header: {
                Text("Messages and Contacts")
            } footer: {
                if relay.status == .connected && !relay.incrementalSync {
                    Text("Update ezcall on Android to enable automatic checks for missed messages and contact changes.")
                } else {
                    Text("SMS history is imported once and stays on this iPhone. ezcall automatically checks for missed messages and contact changes when Android is nearby.")
                }
            }

            Section {
                LabeledContent("Answer and Decline") { Text(LocalizedStringKey(relay.callControl ? "On" : "Off")) }
                LabeledContent("Call from iPhone") { Text(LocalizedStringKey(relay.canDial ? "On" : "Off")) }
                LabeledContent("Call Audio") { Text(LocalizedStringKey(relay.audioMode.title)) }
                switch relay.microphone {
                case .granted:
                    LabeledContent("Microphone") { Text("Allowed") }
                case .denied:
                    Button("Allow Microphone in Settings", action: openSettings)
                default:
                    Button("Allow Microphone") { relay.requestMicrophone() }
                }
                Button("iPhone Contacts for Keypad") {
                    if CNContactStore.authorizationStatus(for: .contacts) == .notDetermined {
                        Task { _ = try? await CNContactStore().requestAccess(for: .contacts) }
                    } else {
                        openSettings()
                    }
                }
            } header: {
                Text("Calls")
            } footer: {
                Text("Turn on Call control and set up call audio in ezcall on your Android. Incoming calls use the iPhone call screen.")
            }

            Section {
                switch relay.alerts {
                case .authorized, .provisional, .ephemeral:
                    LabeledContent("Notifications") { Text("On") }
                    Button("Manage Notifications", action: openNotificationSettings)
                case .denied:
                    Button("Turn On Notifications in Settings", action: openNotificationSettings)
                default:
                    Button("Turn On Notifications") { relay.enableAlerts() }
                }
            } header: {
                Text("Notifications")
            } footer: {
                Text("SMS alerts show the sender and message according to your iPhone’s Show Previews setting.")
            }

            Section {
                EzcallLanguagePicker()
                AppReviewLink()
            }

            Section {
                if let saved = relay.retainedData {
                    NavigationLink("Saved Data") { SavedRelayDataView(data: saved) }
                }
                Button("Forget Android", role: .destructive) { confirmForget = true }
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
        }
        .confirmationDialog("Forget this Android?", isPresented: $confirmForget, titleVisibility: .visible) {
            Button("Forget and Keep Data") { relay.forgetPhone(keepingData: true) }
            Button("Forget and Delete Data", role: .destructive) { relay.forgetPhone() }
        } message: {
            Text("Choose whether to keep calls, messages and contacts on this iPhone.")
        }
    }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
    }

    private func openNotificationSettings() {
        if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
    }
}

private struct SavedRelayDataView: View {
    private enum Category: String, CaseIterable { case calls = "Calls", messages = "Messages", contacts = "Contacts" }

    let data: RelayData
    @State private var category = Category.calls

    var body: some View {
        List {
            Picker("Saved Data", selection: $category) {
                ForEach(Category.allCases, id: \.self) { Text(LocalizedStringKey($0.rawValue)).tag($0) }
            }
            .pickerStyle(.segmented)

            switch category {
            case .calls:
                ForEach(data.calls, id: \.self) { call in
                    VStack(alignment: .leading) {
                        Text(call.address.isEmpty ? ezText("Unknown Caller") : call.address)
                        Text(call.timestamp, format: .dateTime).font(.caption).foregroundStyle(.secondary)
                    }
                }
            case .messages:
                ForEach(data.messages, id: \.self) { message in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(message.address).font(.headline)
                        Text(message.body)
                        Text(message.timestamp, format: .dateTime).font(.caption).foregroundStyle(.secondary)
                    }
                }
            case .contacts:
                ForEach(data.contacts, id: \.self) { contact in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(contact.name).font(.headline)
                        Text(contact.numbers.joined(separator: "\n")).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Saved Data")
    }
}

@MainActor
private final class KeypadTones {
    private var player: AVAudioPlayer?
    private var waves: [Character: Data] = [:]

    func play(_ digit: String) {
        guard let key = digit.first, let (low, high) = CallAudio.frequencies(key) else { return }
        if waves[key] == nil { waves[key] = wave(low: low, high: high) }
        guard let data = waves[key] else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            if session.category != .ambient { try session.setCategory(.ambient) }
            player?.stop()
            let tone = try AVAudioPlayer(data: data)
            tone.volume = 0.12
            player = tone
            tone.play()
        } catch {
            player = nil
        }
    }

    private func wave(low: Double, high: Double) -> Data {
        let rate = 16_000
        let count = 1_280
        var data = Data("RIFF".utf8)
        func append<T: FixedWidthInteger>(_ value: T) {
            var little = value.littleEndian
            withUnsafeBytes(of: &little) { data.append(contentsOf: $0) }
        }
        append(UInt32(36 + count * 2))
        data.append(contentsOf: "WAVEfmt ".utf8)
        append(UInt32(16))
        append(UInt16(1))
        append(UInt16(1))
        append(UInt32(rate))
        append(UInt32(rate * 2))
        append(UInt16(2))
        append(UInt16(16))
        data.append(contentsOf: "data".utf8)
        append(UInt32(count * 2))
        for index in 0..<count {
            let phase = 2 * Double.pi * Double(index) / Double(rate)
            let envelope = Double(min(80, index, count - index - 1)) / 80
            append(Int16(5_000 * envelope * (sin(phase * low) + sin(phase * high))))
        }
        return data
    }
}

private struct AppReviewLink: View {
    private static var reviewURL: URL? {
        guard let id = Bundle.main.object(forInfoDictionaryKey: "EzcallReviewAppID") as? String,
              !id.isEmpty, id.utf8.allSatisfy({ (48...57).contains($0) }) else { return nil }
        return URL(string: "https://apps.apple.com/app/id\(id)?action=write-review")
    }

    var body: some View {
        if let url = Self.reviewURL {
            Link(destination: url) { Label("Rate and Review", systemImage: "star") }
        } else {
            Link(destination: URL(string: "https://github.com/ehsatn/EZcall/issues")!) {
                Label("Send Feedback", systemImage: "text.bubble")
            }
        }
    }
}
