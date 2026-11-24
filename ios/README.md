# ezcall on iPhone

Requires iOS 17 or later. Liquid Glass is available on iOS 26 or later. Use Xcode 26 or later on macOS to build. Open `ezcall.xcodeproj` and select the same signing team for ezcall and ezcallWidgets. Set the project’s `EZCALL_BUNDLE_IDENTIFIER` build setting to a bundle ID belonging to that team. The widget target uses that ID with the `.widgets` suffix. Select a physical iPhone to try Bluetooth and QR scanning.

From the repository root, check that the app builds without device signing.

```sh
xcodebuild -project ios/ezcall.xcodeproj -scheme ezcall -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Run the shared wire fixture against the Swift code and CryptoKit on macOS.

```sh
check_dir="$(mktemp -d)"
xcrun --sdk macosx swiftc ios/ezcall/RelayWire.swift ios/Checks/WireCheck.swift -o "$check_dir/wire-check"
"$check_dir/wire-check" tests/protocol-v1.json
```

1. Complete the setup guide in ezcall on Android and show the Bluetooth pairing QR. On iPhone, scan the code in ezcall and finish the notification and call-audio permission screens.
2. Tap the gear at the top left of **Calls** and use **Test Connection**. Use the bottom tabs to switch between **Calls**, **Contacts**, **Keypad** and **Messages**. If the link stays down, Calls shows its status at the top of the list.
3. In **Messages**, search conversations, compose an SMS or reply inside a thread. A sent SMS shows **Sending…**, then **Sent** or **Not Delivered** with a retry option. Tap the name at the top of a thread for the contact card, or the phone button to call. Enable **Send SMS from the receiving phone** on Android to use its SIM. SMS alerts use the iPhone’s Show Previews setting for the sender and message text.
4. Use **Sync Messages and Contacts** in Settings after changing Android sharing or use pull-to-refresh in Messages or Contacts. On Android, **Forget iPhone** opens a fresh QR while keeping Android sharing and audio settings. It clears the old link and synced data on a connected, updated iPhone. If the iPhone was offline, use **Forget Android** there before scanning again. The iPhone keeps its completed setup when you pair again.

When ezcall is suspended, iOS can wake it for pending Bluetooth events. After the system removes the app from memory, Core Bluetooth can relaunch it for an eligible pending event. The app reauthenticates before reading new data. A call can be missed while the phones are out of range or reconnecting. After swiping ezcall away in the App Switcher, reopen it to resume sharing. ezcall uses QR pairing and Core Bluetooth. Apple documents additional relaunch rules on iOS 26 for accessories set up with AccessorySetupKit. ezcall displays the Android call state and history seen while linked.

When **Call control** is on in ezcall on Android, incoming Android calls appear in the iOS call screen, including the Lock Screen. Answer, decline and end act on Android's SIM call. iOS controls when the call stays full-screen or appears in the system call indicator. Tap the indicator to bring the system call controls back. When ezcall is in the foreground, dialing or answering opens its call screen. Choose **More → Minimize call** to continue using the app. In **Calls**, the expand button beside **End Call** opens the current call again. Use **Keypad** or the phone button beside a recent call or contact number to dial through Android. After call audio is set up on Android, the call plays on iPhone. With two-way audio, ezcall also sends the iPhone microphone. Allow the microphone when asked, or under **Calls** in Settings. Mute in the call screen stops sending the microphone. With compatible versions on both phones, iPhone shows **Reconnecting…** during a short Bluetooth outage and restores the active call and audio after authentication. If recovery expires, the SIM call stays on Android.

When CallKit handles an ezcall call, iPhone also records it in Phone → Recents. Tap an ezcall entry there to redial through the paired Android. Turn on Call control on Android for redialing.

To add either shortcut widget, edit the iPhone Home Screen, tap **Add Widget**, search for **ezcall**, and add **Calls** or **Messages**. **Calls** opens the call list in ezcall. **Messages** opens the iPhone’s Messages app.

## Device checks

1. Open ezcall on an already paired iPhone. Check that Calls opens first and that the bottom tabs switch to Contacts, Keypad and Messages. Open Messages, search and open a thread, send an SMS and check the Sending… and Sent states, then return to the list. In Calls, tap a row to call and the info button for details. On the keypad, hold 0 for + and hold delete to erase. Use VoiceOver to open Settings. Verify that a permission change on Android updates capabilities without re-pairing.
2. With **Test Connection** passing, send ezcall to the background and lock the iPhone. Call the Android phone and let it stop ringing. Check that the generic call alert remains in Notification Center and that ezcall shows the ended call on reopening. Send a new SMS with Show Previews set to Always, When Unlocked and Never. Check that the sender and text follow each setting before and after unlocking.
3. Leave ezcall in the background with both phones locked for at least ten minutes. Call the Android phone and send it an SMS, and check that both events reach the iPhone before opening ezcall. Next, with the iPhone still locked, turn Android Bluetooth off for one minute, turn it back on and repeat the Lock Screen check. Then run **Test Connection** with the same pairing. If authentication stalls, compare the iOS **Relay** log for GATT discovery, indication subscription and proof verification with Android’s GATT trace.
4. Swipe ezcall away in the App Switcher. Reopen it, confirm **Test Connection** passes, and check that notifications resume.
5. In the Android debug build, use **Simulate incoming call** to test the iPhone microphone and Bluetooth audio loopback. With **Call control** and call audio set up on Android, ring Android while the iPhone is locked. Answer on iPhone and check both directions of audio, mute, speaker and Bluetooth headset routing, then end from each phone in turn. Place a call from **Keypad** and check ringback and that the far end hears the iPhone. On a phone with PSTN redirection, verify that the Android speaker is quiet and the far end cannot hear sound made near Android’s microphone. Disconnect Bluetooth, verify that the handset audio returns, then reconnect and verify that audio moves back to iPhone. End a call while muted and place another to check that the microphone starts unmuted. Decline an incoming call. Answer one call on Android and check that the iPhone shows it answered elsewhere.

6. With Call control on, finish an incoming call and place an outgoing call through ezcall. Check Phone → Recents for both entries and the ezcall attribution iOS displays. Redial each entry and confirm the outgoing call uses Android’s SIM. Repeat from Phone after force-quitting ezcall, then repeat with Bluetooth off. Check that a disconnected redial reports the problem and does not dial later.

The optional support section uses an App Store Connect consumable tip product. Set the Xcode build setting `EZCALL_TIP_PRODUCT_ID` to its product ID to enable the section. Leave it blank for a free build without a tip button.

## Platform references

- [Core Bluetooth central role](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/PerformingCommonCentralRoleTasks/PerformingCommonCentralRoleTasks.html)
- [Core Bluetooth background processing and restoration](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)
- [Conditions for Bluetooth state restoration](https://developer.apple.com/documentation/technotes/tn3115-bluetooth-state-restoration-app-relaunch-rules)
- [Local notifications](https://developer.apple.com/documentation/usernotifications/scheduling-a-notification-locally-from-your-app)
- [Camera scanning](https://developer.apple.com/documentation/visionkit/scanning-data-with-the-camera)
- [Protecting UI snapshots](https://developer.apple.com/documentation/uikit/preparing-your-ui-to-run-in-the-background)
- [CallKit](https://developer.apple.com/documentation/callkit)
- [TelephonyMessagingKit](https://developer.apple.com/documentation/telephonymessagingkit)
- [Apple Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines)
