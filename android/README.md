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

1. The sender opens its setup guide on first launch. Choose what to share, continue to Bluetooth, then show the pairing QR. After connecting, the guide offers optional call audio. Previously paired phones can review audio without pairing the receiver again. Reopen any step from **Settings → Setup guide**.
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

In the debug build, open **Status** and tap **Simulate incoming call** once the receiving phone is connected. Allow the microphone in ezcall on the receiving phone before testing with the screen locked. Answer **ezcall test call**, then speak. Android sends the receiving phone’s microphone audio back across the same Bluetooth link, so you can hear your own voice. The test ends within 45 seconds after answering and uses no SIM call.

## Wireless debugging

1. Connect Android to Wi-Fi and enable Developer options. In ezcall, open **Settings** and tap **Pair with Wireless debugging** and allow notifications if prompted.
2. In Developer options, open **Wireless debugging**, enable it, then select **Pair device with pairing code**. Leave the code window open.
3. Open the notification shade and tap **Enter code** on the ezcall notification. Enter the six digits. If discovery fails, enter the digits followed by a colon and the port shown in the same Android dialog, for example `123456:37811`.
4. Android lists **ezcall** under **Paired devices** after a new pairing. An existing authorization may still appear as **Xcall**. Remove that entry in Wireless debugging to revoke the authorization.

The app pairs with the Android debugger on the same phone using a key in Android Keystore. Call alerts, messages, and contacts use the existing Bluetooth connection with the receiving phone.

## Call audio

1. Enable **Call alerts**, then **Call control** to answer, decline, end and place calls from the receiving phone. Android asks for the phone and answer-calls permissions.
2. Pair with Wireless debugging as above and keep Wireless debugging on. In **Settings → Call audio**, tap **Set up call audio**. ezcall starts a local audio component as the shell user and reports what this phone allows.
3. **Two-way audio ready** means the caller hears the receiving phone’s microphone. **Listen only on this phone** means the receiving phone plays the caller, but Android doesn't let apps inject audio into calls. Talk on Android in that case.
4. Answer or place a call on the receiving phone. Calls answered on Android keep their audio on Android.

On phones that support PSTN redirection, ezcall moves the call off the sender’s microphone and speaker once receiver audio is ready and the first microphone packet has reached Android. Android restores its local call route when receiver audio pauses, Bluetooth drops, or the call ends. Listen-only phones rely on Android’s microphone. Use current builds on both phones.

After Android restarts, connect the sender to Wi-Fi, turn on **Developer options → Wireless debugging**, then return to ezcall and tap **Restart call audio**. You can close the settings screen. Only the initial pairing needs the code window. If discovery fails, tap **Enter connection port** and enter the port shown under **IP address and port** on the main Wireless debugging screen. ezcall also tries to restart the component when the relay starts and when a call needs audio, if Wireless debugging is on. Remove ezcall under **Paired devices** in Wireless debugging to stop future restarts.

Audio capture depends on the phone. The component prefers Android 13's call audio interception devices, then the far-end voice source, then the mixed call source. With the mixed source the receiving phone also hears its own voice. Its log is `/data/local/tmp/ezcall_audio.log` and is readable with `adb shell cat`.

The installer and system control hard-restricted SMS and call-log permissions. For Play distribution, complete the sensitive-permission review for the cross-device synchronization use case. The app supports calls and contacts according to the individual grants available on the device. [Android permission reference](https://developer.android.com/reference/android/Manifest.permission) · [Google Play permission policy](https://support.google.com/googleplay/android-developer/answer/10208820)

To enable the optional **Donate** link, set `support_url` in `app/src/main/res/values/strings.xml` to your HTTPS support page and rebuild. The Bluetooth relay works offline.
