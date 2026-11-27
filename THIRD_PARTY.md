# Third-party software | اجزای شخص ثالث

The MIT license covers original ezcall code. Dependencies retain their upstream licenses and notices. مجوز MIT برای کد اصلی ایزی کال است. اجزای زیر تحت شرایط ناشران خود عرضه می‌شوند.

| Component | Use | Upstream terms |
| --- | --- | --- |
| Vazirmatn | Persian typeface on Android and the website | [Bundled SIL Open Font License and copyright](docs/assets/Vazirmatn-OFL.txt) |
| Gradle Wrapper | Android builds | [Gradle license](https://github.com/gradle/gradle/blob/master/LICENSE) |
| Material Components for Android | Android user interface | [Material Components](https://github.com/material-components/material-components-android/blob/master/LICENSE) |
| ZXing core | Pairing QR | [ZXing](https://github.com/zxing/zxing/blob/master/LICENSE) |
| LibADB Android | Optional on-device Wireless debugging setup | [LibADB 3.1.1 license and notices](https://github.com/MuntashirAkon/libadb-android/tree/3.1.1) |
| Shizuku API | Optional call audio setup through Shizuku | [Shizuku API licenses and notices](https://github.com/RikkaApps/Shizuku-API) |
| Conscrypt | Android TLS provider | [Conscrypt notices](https://github.com/google/conscrypt/blob/master/NOTICE) |
| Bouncy Castle | Cryptographic dependency on Android | [Bouncy Castle license](https://www.bouncycastle.org/about/license/) |

The build declares its direct Android dependencies in the [Sender](android/app/build.gradle.kts) and [Receiver](android/receiver/build.gradle.kts) Gradle files.
