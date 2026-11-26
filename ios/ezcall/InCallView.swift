import AVFoundation
import SwiftUI

@MainActor
struct InCallView: View {
    @ObservedObject var relay: RelayController
    let callID: UUID
    let minimize: () -> Void
    @State private var speaker = false
    @State private var more = false
    @State private var keypad = false
    @State private var outputError: String?
    @State private var heardTestAudio = false

    var body: some View {
        GeometryReader { geometry in
            if let call = relay.phoneCall, call.id == callID {
                ScrollView {
                    VStack(spacing: 14) {
                        HStack(spacing: 6) {
                            Image("CallProviderIcon").renderingMode(.template)
                                .resizable().frame(width: 18, height: 18)
                            Text("ezcall Audio")
                            Text("·")
                            if relay.callReconnecting {
                                Text("Reconnecting…")
                            } else if let date = call.connectedAt {
                                Text(date, style: .timer).monospacedDigit().fixedSize()
                            } else {
                                Text("Connecting…")
                            }
                        }
                        .font(.headline)
                        .foregroundStyle(.white.opacity(0.78))
                        Text(relay.name(for: call.address))
                            .font(.largeTitle.weight(.semibold))
                            .accessibilityAddTraits(.isHeader)
                        Spacer(minLength: 20)
                        if call.endFailed {
                            Text("Couldn’t end the call. Try again.")
                        } else if let outputError {
                            Text(ezText(outputError))
                        } else if let feedback = relay.feedback {
                            Text(ezText(feedback))
                        } else if call.connectedAt != nil && !relay.callAudioActive && !relay.callReconnecting {
                            Text("Audio on Android").foregroundStyle(.secondary)
                        }
                        if relay.activeCall?.test_audio_confirmation == true,
                           relay.activeCall.flatMap({ UUID(uuidString: $0.id) }) == callID, call.connectedAt != nil {
                            Text("Listen for the beeps, then speak to hear your voice back.")
                                .multilineTextAlignment(.center)
                            Button {
                                heardTestAudio = relay.confirmTestAudio(call.id)
                            } label: {
                                Text(LocalizedStringKey(heardTestAudio ? "Playback confirmed" : "I heard the beeps"))
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(heardTestAudio || !relay.callAudioActive || relay.callReconnecting)
                        }
                        let size = min(78, max(44, (geometry.size.width - 80) / 3))
                        Grid(horizontalSpacing: 12, verticalSpacing: 20) {
                            GridRow {
                                control("Speaker", "speaker.wave.2.fill", size, selected: speaker,
                                        enabled: relay.callAudioActive, action: toggleSpeaker)
                                control("FaceTime", "video.fill", size, enabled: false) {}
                                control("Mute", "mic.slash.fill", size, selected: relay.muted,
                                        enabled: relay.canMutePhoneCall) { relay.togglePhoneCallMute(call.id) }
                            }
                            GridRow {
                                control("More", "ellipsis", size) { more = true }
                                control("End", "phone.down.fill", size, tint: .red) { relay.endPhoneCall(call.id) }
                                control("Keypad", "circle.grid.3x3.fill", size, enabled: relay.canSendCallTones) { keypad = true }
                            }
                        }
                        .frame(maxWidth: 420)
                    }
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 18)
                    .padding(.top, max(20, geometry.size.height * 0.07))
                    .padding(.bottom, max(18, geometry.size.height * 0.035))
                    .frame(minHeight: geometry.size.height)
                    .frame(maxWidth: .infinity)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        .background {
            RadialGradient(colors: [Color(red: 0.12, green: 0.29, blue: 0.45),
                                    Color(red: 0.07, green: 0.12, blue: 0.21),
                                    Color(red: 0.05, green: 0.07, blue: 0.13)],
                           center: .topLeading, startRadius: 12, endRadius: 820)
                .ignoresSafeArea()
        }
        .foregroundStyle(.white)
        .preferredColorScheme(.dark)
        .confirmationDialog("Call options", isPresented: $more) {
            Button("Minimize call", action: minimize)
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $keypad) {
            VStack(spacing: 16) {
                Text("Call keypad").font(.title2.bold())
                Grid(horizontalSpacing: 16, verticalSpacing: 10) {
                    ForEach(["123", "456", "789", "*0#"], id: \.self) { row in
                        GridRow {
                            ForEach(Array(row), id: \.self) { digit in
                                Button {
                                    guard relay.sendCallTone(digit, for: callID) else {
                                        outputError = "Couldn’t send the tone. Try again."
                                        keypad = false
                                        return
                                    }
                                } label: {
                                    Text(String(digit)).font(.title)
                                        .frame(width: 62, height: 48)
                                }
                                .buttonStyle(.bordered)
                            }
                        }
                    }
                }
                Button("Done") { keypad = false }.frame(minHeight: 44)
            }
            .presentationDetents([.medium, .large])
        }
        .onChange(of: relay.canSendCallTones) { if !relay.canSendCallTones { keypad = false } }
        .onAppear(perform: readRoute)
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)) { _ in readRoute() }
    }

    private func control(_ title: LocalizedStringKey, _ icon: String, _ size: CGFloat, selected: Bool = false,
                         enabled: Bool = true, tint: Color? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 7) {
                let symbol = Image(systemName: icon)
                    .font(.system(size: size * 0.36, weight: .semibold))
                    .frame(width: size, height: size)
                let color = selected ? Color.cyan : tint
                if #available(iOS 26, *) {
                    symbol.glassEffect(.regular.tint(color).interactive(enabled), in: Circle())
                } else {
                    symbol.background(color ?? .white.opacity(0.16), in: Circle())
                }
                Text(title).font(.callout.weight(.medium))
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private func readRoute() {
        speaker = AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.portType == .builtInSpeaker }
    }

    private func toggleSpeaker() {
        do {
            try AVAudioSession.sharedInstance().overrideOutputAudioPort(speaker ? .none : .speaker)
            outputError = nil
            readRoute()
        } catch {
            outputError = "Couldn’t change the audio output. Try again."
        }
    }
}
