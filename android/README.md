# ezcall Sender for Android

To receive on a second Android phone, install [ezcall Receiver](receiver/README.md).

The sender runs on Android 12 or later. The phone must support advertising as a Bluetooth LE peripheral. A SIM and telephony support are needed to observe cellular calls and SMS.

Sender 0.1.9 uses the new application ID `app.ezcall.android`. Android installs it separately from Sender 0.1.8. Set up sharing and pair the receiver with the new Sender, then repeat Wireless debugging pairing if using call audio. The receiver’s **Forget sending phone** action clears its local synced history.

Open this directory in Android Studio with Android SDK 36 and JDK 21. The checked-in Gradle wrapper can also build and check the app from PowerShell.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

For the second command, install [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools), enable USB debugging on the test phone, and approve that computer from the phone.

1. On first launch, choose **Start setup guide** or **Skip setup**. In the guide, choose what to share and continue to pairing. The Sender requests Nearby devices permission if needed and shows the pairing QR when Bluetooth is ready. After pairing, the guide offers optional call audio. Reopen the guide from **Settings → Setup guide**. A saved pairing skips the QR step.
2. Scan the two-minute QR in ezcall on iPhone or ezcall Receiver on Android. On iPhone, open **Settings** with the gear in **Recents** and tap **Test Connection**. On Android Receiver, open **Settings** and tap **Test connection**.
3. Open **Status** to check the connection, shared features, call audio, notifications and battery settings. The relay reconnects automatically after pairing.
4. Use **Connection → Forget receiving phone** to replace the current link with a fresh QR. Audio and sharing settings are kept.
5. Open **Settings** from the top bar to change the app language, set up call audio, allow notifications or allow the relay to keep running with the screen off.

| Situation | Expected Android behavior |
| --- | --- |
| Switch apps, close ezcall from Recents, or lock the screen | The foreground relay stays active while Android allows it. |
| The receiving phone goes out of range or the Bluetooth link drops | Android advertises again. The receiving phone reconnects and authenticates when it can reach Android. If the handshake times out, Android opens a fresh GATT server. |
| Android Bluetooth is turned off and back on | The relay waits with no live link and resumes advertising when Bluetooth returns. |
| Reboot or update Android's ezcall app | The paired relay can restart when Android delivers the boot or update event and Nearby devices permission remains granted. |
| Force Stop in Android Settings | Reopen ezcall to restart the relay after a system Force Stop. |

During device testing, run **Test connection** on the receiving phone after each recovery, then check call alerts and SMS and contact sync. Test screen lock, Recents and reboot separately. [Android foreground services](https://developer.android.com/develop/connectivity/bluetooth/ble/background) · [Android Force Stop behavior](https://developer.android.com/about/versions/15/behavior-changes-all#enhanced-stop-states)

To check Doze on a test phone, leave both phones locked and use `adb shell dumpsys deviceidle force-idle`. Ring the Android SIM and check for the call on the receiving phone. Run `adb shell dumpsys deviceidle unforce` afterward. [Doze testing](https://developer.android.com/training/monitoring-device-state/doze-standby#testing_doze)

Once the receiving phone is connected, open **Audio** and tap **Start test** under **Test call audio**. Answer on the receiving phone, wait for the test tones, then speak. The tones test sender-to-receiver playback even with the receiver microphone off. The echo tests microphone capture and the return path. The test uses no SIM call. With Android Receiver, it ends after all checks pass or after 45 seconds of active testing.

The sender’s **Test report** shows the outgoing test tone and the microphone audio it receives. Android Receiver shows its microphone and incoming audio. Speak after the tones. The apps report the test result automatically after Android plays the tone and returned microphone signal. For device diagnostics, capture logs on both phones with the `EzcallTestCall` Logcat tag and match the test IDs. Each phone uses its own elapsed-time clock. `ECHO_PLAYED` means the Receiver’s Android playback position advanced past non-silent returned microphone audio. Physical audibility depends on the output device and volume.

## Wireless debugging

1. Connect Android to Wi-Fi and enable Developer options. In ezcall, open **Settings** and tap **Pair with Wireless debugging** and allow notifications if prompted.
2. In Developer options, open **Wireless debugging**, enable it, then select **Pair device with pairing code**. Leave the code window open.
3. Open the notification shade and tap **Enter code** on the ezcall notification. Enter the six digits. If discovery fails, enter the digits followed by a colon and the port shown in the same Android dialog, for example `123456:37811`.
4. Android lists **ezcall** under **Paired devices** after a new pairing. An existing authorization may still appear as **Xcall**. Remove that entry in Wireless debugging to revoke the authorization.

The app pairs with the Android debugger on the same phone using a key in Android Keystore. Call alerts, messages, and contacts use the existing Bluetooth connection with the receiving phone.

If automatic discovery fails, enter the **connection port** from the main Wireless debugging screen in **Settings → Call audio → Enter connection port**. Sender verifies the endpoint before saving it and tries discovery again if the saved port stops working.

## USB debugging

On the Sender phone, enable **USB debugging** in Developer options and approve your computer when you connect the USB cable. Open **Settings → Call audio → Set up with USB** and keep the dialog open. Run the command it shows using Android Platform-Tools. If more than one USB device is attached, run `adb devices` and replace `-d` with `-s SERIAL` for the Sender. When the app reports the audio status, you can unplug the cable. Repeat USB setup if the audio component stops or the phone restarts.

## Direct root

On an Android 13 or later Sender with `su` available to the app, open **Settings → Call audio → Audio setup methods** and approve ezcall in the root manager. The first setup offers direct root automatically. If permission is denied or call audio cannot start, use Shizuku, Wireless debugging, or USB from the same screen.

## Shizuku

1. Install and start [Shizuku](https://shizuku.rikka.app/guide/setup/) 13 or later on the Sender phone using root, USB or Wireless debugging.
2. In ezcall Sender, open **Settings → Call audio → Audio setup methods → Set up with Shizuku** and allow ezcall in the Shizuku permission prompt.
3. After a phone restart, start Shizuku again. In ezcall, use **Restart with Shizuku** if call audio is stopped.

Shizuku starts the same local call audio component as the USB and Wireless debugging methods. Receiver pairing continues over Bluetooth.

## Call audio

1. Enable **Call alerts**, then **Call control** to answer, decline, end and place calls from the receiving phone. Android asks for the phone and answer-calls permissions.
2. In **Settings → Call audio**, choose direct root, Shizuku, USB, or Wireless debugging. ezcall starts a local audio component and reports what this phone allows.
3. **Two-way audio ready** means the receiver can take over both directions. **Audio stays on this phone** means the sender can still relay call controls while the conversation stays on the sender.
4. Answer or place a call on the receiving phone. Calls answered on Android keep their audio on Android.

On phones that support PSTN call audio access, ezcall streams audio when the receiver is ready. Android continues to manage the sender’s microphone and speaker throughout the call. The audio relay pauses or stops when the receiver pauses audio, Bluetooth drops, or the call ends. Use current builds on both phones.

During the first seconds the receiver talks in a relayed call, the call audio component compares that voice with the phone’s own call uplink capture. A match confirms that the voice reached the SIM call, and **Settings → Call audio** says so. No match proves nothing: some phones take that capture before the receiver’s voice is mixed in, as a Xiaomi 12 Lite (Snapdragon 778G) did while the other person heard the receiver. Every 5 seconds the component also logs how much of the receiver’s audio arrived and how loud it was.

**Settings → Call audio → Send audio report** shares the device model, chipset, Android build, setup method, the last check result and the component’s recent log as text. It contains no numbers, contacts or messages.

Enable **Floating call button** in the sender’s **Settings → Call audio** and allow ezcall to display over other apps. Tap **Use audio on this phone** while the call is ringing or using the receiver. The receiver closes its call screen and the SIM call continues on Android. The same action is available in the sender’s relay notification.

If you use Wireless debugging, after Android restarts, connect the sender to Wi-Fi, turn on **Developer options → Wireless debugging**, then return to ezcall and tap **Restart call audio**. You can close the settings screen. Only the initial pairing needs the code window. If discovery fails, tap **Enter connection port** and enter the port shown under **IP address and port** on the main Wireless debugging screen. ezcall also tries to restart the component when the relay starts and when a call needs audio, if Wireless debugging is on. Remove ezcall under **Paired devices** in Wireless debugging to stop future restarts.

Run the test call on Android 12, 12L, 13, 14, 15, 16 and 17 with each phone in the sender and receiver roles. Repeat with the receiver locked and with its microphone permission granted and denied. For real SIM audio, the sender requires Android 13 or later and device support for PSTN call audio access. On supported phones, place a call from the receiver and answer an incoming call there. Verify the caller and receiver can hear each other and that the sender's normal call audio stays available. Receive a multipart SMS on the sender during the call and check that it appears on the receiver without a gap in relayed speech. During a relayed call, stop Bluetooth and verify the SIM call continues on the sender. The component log is `/data/local/tmp/ezcall_audio.log` and is readable with `adb shell cat`.

The installer and system control hard-restricted SMS and call-log permissions. For Play distribution, complete the sensitive-permission review for the cross-device synchronization use case. The app supports calls and contacts according to the individual grants available on the device. [Android permission reference](https://developer.android.com/reference/android/Manifest.permission) · [Google Play permission policy](https://support.google.com/googleplay/android-developer/answer/10208820)

To enable the optional **Donate** link, set `support_url` in `app/src/main/res/values/strings.xml` to your HTTPS support page and rebuild. The Bluetooth relay works offline.
