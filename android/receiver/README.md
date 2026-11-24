# ezcall Receiver for Android

Install the receiver on the Android phone that will display calls and messages. It requires Android 12 or later and Bluetooth Low Energy. The Android sender with the SIM can still pair with the iPhone app. One receiving phone can be paired with a sender at a time.

1. In ezcall on the sending phone, enable the sharing categories you want and open its pairing QR.
2. In **ezcall Receiver**, choose **Scan QR** or **Choose QR image** and allow **Nearby devices**. Delete any saved QR image after pairing. Allow notifications for background call and SMS alerts. SMS alerts show the sender and message. Hiding sensitive notification content in Android settings hides both on the lock screen.
3. When the link connects, open **Settings → Test connection**. The receiver syncs the latest 100 SMS and up to 5,000 contacts. With sender 0.1.8 or later, changes on the sending phone also refresh the receiver while connected. **Sync now** is available for a manual refresh.
4. Enable **Call alerts** and **Call control** on the sender. On the receiver, open **Settings → Calls in the Phone app** and enable **ezcall Receiver** in Android’s calling accounts. The Phone app can now ring, answer and decline relayed calls. When dialing in the Phone app, select the ezcall account to use the sending phone. If the account is unavailable, the receiver uses its own incoming call screen. On Android 14 or later, **Allow calls on the lock screen** grants full-screen access for this fallback. Both paths follow the phone’s sound settings.
5. For call audio, complete the sender’s [audio setup](../README.md#call-audio). If two-way audio is supported, grant microphone access when answering or dialing. If the sender supports listening only, speak into the sending phone.
6. Keep Bluetooth on. The visible Android service reconnects when both phones are nearby. After a system Force Stop, reopen the receiver. To remove the pairing and synced data, open **Settings → Forget sending phone**. On the sender, **Forget receiving phone** opens a fresh QR for the next pairing.

Choose **Settings → Language** to switch between English and Persian. The setting applies to this app.

Open **Messages** and tap a conversation to see its history and reply. For a new conversation, tap **New SMS** and enter a number or choose a synced contact. Enable SMS sending on the sender to send from its SIM. Android’s Phone app maintains its own call history for calls handled through the ezcall calling account.

Build with JDK 21 and Android SDK 36 from the `android` directory.

```powershell
.\gradlew.bat :receiver:assembleDebug :receiver:testDebugUnitTest :receiver:lintDebug
adb install -r .\receiver\build\outputs\apk\debug\receiver-debug.apk
```

Verify on two Android phones by pairing, testing the connection, placing an incoming call, receiving an SMS, syncing contacts, then locking and unlocking the receiver. Toggle Bluetooth and check that the connection recovers. With call audio configured, answer a test call and check both directions before relying on it for a SIM call.
