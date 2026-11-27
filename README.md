# ezcall

[فارسی](README.fa.md) · [English website](https://ehsatn.github.io/EZcall/en.html) · [Releases](https://github.com/ehsatn/EZcall/releases)

ezcall displays Android calls and SMS on an iPhone or a second Android phone and syncs a copy of Android contacts. The phones communicate over Bluetooth Low Energy. Linking and everyday use work without a server, account or Internet connection. Android runs a visible background relay that reconnects automatically after pairing. Support is optional and every relay feature is free.

## Downloads

| Device | Release file | Requirements |
| --- | --- | --- |
| Android with the SIM | [Sender 0.1.22 · APK](https://github.com/ehsatn/EZcall/releases/download/android-2026-11-28/ezcall-sender-0.1.22-release.apk) | Android 12+, telephony and BLE peripheral advertising |
| Second Android phone | [Receiver 0.1.15 · APK](https://github.com/ehsatn/EZcall/releases/download/android-2026-11-28/ezcall-receiver-0.1.15-release.apk) | Android 12+ and BLE |
| iPhone | [Join EasyCall Relay on TestFlight](https://testflight.apple.com/join/gRnJCZ5E) | iOS 17+ and the TestFlight app |

Open the APK on the phone that will perform that role and follow Android’s installer prompts. Each component has its own version. The release includes SHA-256 checksums. Sender 0.1.9 changed its Android application ID. If upgrading from 0.1.8, pair the new Sender and repeat optional call-audio setup. [Release notes and checksums](https://github.com/ehsatn/EZcall/releases/latest)

## Set up a sending and receiving phone

1. On Android 12 or later, install the Sender APK from the table above on a phone that supports BLE peripheral advertising. To build it yourself, open the `android` directory in Android Studio and follow the [Android build instructions](android/README.md).
2. On the receiving phone, install the Android Receiver APK or open the TestFlight invitation from the table above. The iPhone app uses Liquid Glass on iOS 26 or later. [Android Receiver instructions](android/receiver/README.md) · [iOS build instructions](ios/README.md)
3. On the sender, open **Connection → Start pairing**, choose what to share and show the QR. Optional call audio setup is under **Settings**. On the receiving phone, scan the Android QR and grant Bluetooth access. Allow notifications for incoming calls and SMS.
4. On iPhone, tap the gear at the top left of **Recents**, then **Test Connection**. Use the bottom tabs for **Recents**, **Contacts**, **Keypad** and **Messages**. In **Messages**, search or tap the compose button to send an SMS. Sending uses the Android SIM and requires **Send SMS from the receiving phone** on the sender.
5. On Android Receiver, open **Settings → Test connection** and use **Calls**, **Messages** and **Contacts**. A sender supports one receiver at a time. **Forget receiving phone** on the sender opens a fresh QR while preserving sharing and audio settings.

With **Call control** enabled on Android, the receiving app can answer, decline and place calls on Android's SIM. On iPhone this uses the iOS call screen. After setting up call audio on Android over Wireless debugging, those calls play through the receiving phone. Phones that allow call audio injection also carry the receiving phone’s microphone to the caller. [Android setup](android/README.md#call-audio)

The app uses its own calls, SMS and contacts views. Without call control, an incoming-call alert displays the Android call. With Call control enabled, calls handled by ezcall also appear in the iPhone’s Phone Recents and can be redialed through the paired Android. Android SMS appears in ezcall. Android may offer only a call-state alert if the caller number is unavailable. SMS history is limited to the latest 100 records. Contact sync is limited to 5,000 records. [Apple default calling apps](https://developer.apple.com/documentation/CallKit/Preparing-your-app-to-be-the-default-calling-app) · [Apple carrier messaging](https://developer.apple.com/documentation/telephonymessagingkit)

## Development

| Platform | Stack | Primary guides |
| --- | --- | --- |
| Android 12+ | Kotlin, Android BLE GATT server for the sender, central for the receiver, and foreground services | [BLE overview](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview) · [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) · [GATT server](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer) · [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) · [SMS APIs](https://developer.android.com/reference/android/provider/Telephony.Sms) · [Contacts Provider](https://developer.android.com/identity/providers/contacts-provider) |
| iOS 17+ | SwiftUI, Core Bluetooth central/restoration and local notifications | [Central programming guide](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/PerformingCommonCentralRoleTasks/PerformingCommonCentralRoleTasks.html) · [Bluetooth background execution](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html) · [Restoration limits](https://developer.apple.com/library/archive/qa/qa1962/_index.html) · [Local notifications](https://developer.apple.com/documentation/usernotifications/scheduling-a-notification-locally-from-your-app) · [Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines) |

- [Bluetooth protocol and test vectors](docs/protocol-v1.md)
- [Security design and device tests](docs/security.md)
- [Android Receiver setup and build](android/receiver/README.md)

## Distribution

Google Play reviews access to SMS and call logs. Its published exceptions include cross-device synchronization. The installer and OS must permit restricted permissions on the test device as well. Enable only the categories you want and check the permission status on Android. [Google Play SMS and Call Log policy](https://support.google.com/googleplay/android-developer/answer/10208820)

Android shows optional wallet addresses and a feedback link under **Support ezcall**. The iPhone app offers a review link for the public App Store release or a feedback link for the beta. [iOS review configuration](ios/README.md#reviews)

iOS background delivery is subject to system Bluetooth restoration limits. After explicitly force-quitting ezcall on iPhone, reopen it to resume background delivery. When a relay is offline, recent SMS can be recovered with a sync. An incoming-call alert requires a connection while the call is ringing. [Apple restoration behavior](https://developer.apple.com/library/archive/qa/qa1962/_index.html)

## Build and contribute

For Android, install JDK 21 and Android SDK 36. Run the following from the `android` directory. On Windows use `.\gradlew.bat` in place of `./gradlew`.

```sh
./gradlew :app:assembleDebug :receiver:assembleDebug :app:testDebugUnitTest :receiver:testDebugUnitTest
```

The [iPhone guide](ios/README.md) covers signing, Swift protocol checks and physical-device tests. The Kotlin and Swift implementations share a [test vector](tests/protocol-v1.json). GitHub Pages serves the static Persian and English entry points in `docs/index.html` and `docs/en.html` on `main`.

For a bug report, include both device models, OS and app versions, and steps to reproduce. Remove phone numbers, SMS contents and pairing codes. For a pull request, describe how you tested the affected role. [GitHub Issues](https://github.com/ehsatn/EZcall/issues)

Original ezcall code is available under the [MIT License](LICENSE). Third-party components retain their own terms. [Third-party notices](THIRD_PARTY.md)
