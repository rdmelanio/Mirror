# Mirror 1.2.0

One Android APK for a phone camera and a Google TV mirror. Kotlin, Android Views,
one `:app` module, Android 8.0+ (API 26). The APK contains no native `.so` libraries
and works on both 32-bit armeabi-v7a and 64-bit Android devices.

## Install without a PC

Download the latest signed APK:

**https://github.com/rdmelanio/Mirror/releases/latest/download/mirror.apk**

- **Phone:** open that URL in your browser, download `mirror.apk`, allow the browser
  to install unknown apps if prompted, and install.
- **Google TV:** install the **Downloader** app, allow it to install unknown apps,
  and enter the same URL. Download and install `mirror.apk`.
- Install the same APK on both devices. Future releases use the same signing key
  and install over this version. Keep the committed keystore when updating.

## Choose a role and connect

1. Connect the TV and phone to the same local Wi-Fi network.
2. On first launch, choose **TV - show the mirror** on the TV and
   **Phone - be the camera** on the phone. The suggested button is already focused.
3. On the phone, select Rear (default) or Front and 720p (default) or 1080p. Press
   **Start**, allow camera access, and allow notifications to see the persistent
   Stop control. Camera and notification permissions are requested only here.
4. The TV finds the phone automatically. If discovery is blocked by the router,
   press OK, go to Connection, and enter the address shown on the phone.
   `192.168.1.50:8080` automatically becomes `http://192.168.1.50:8080/video`.
   **Find camera automatically** lists discovered cameras for you to select.
5. Use **Battery optimization settings** on the phone and allow Mirror to run
   without battery restrictions. On Samsung, also allow unrestricted battery use
   and remove Mirror from sleeping/deep-sleeping apps. The camera runs in a
   foreground service with a wake lock and continues when the screen turns off.
6. Press **Stop** on the phone or its notification when finished. Both modes have
   **Change role**, which returns to the chooser.

The saved role opens directly next time. After choosing TV on Google TV, saying
**“Hey Google, open Mirror”** launches the app through its TV launcher entry.
Voice recognition and app selection are handled by Google TV.

The TV also accepts an IP Webcam stream such as `http://192.168.1.50:8080/video`.
Two viewers can watch the phone stream simultaneously. `GET /status` returns the
device name, app version, and streaming state. Keep streams on your local network;
the plain HTTP endpoint has no authentication.

## TV remote controls

| Button | Action |
| --- | --- |
| Left / Right | Switch MIRROR / RING LIGHT |
| Up / Down in MIRROR | Zoom 1.0×–3.0× in 0.25 steps |
| Left / Right while zoomed | Pan; return to 1.0× to switch modes |
| Up / Down in RING LIGHT | Brightness 10%–100% in 10% steps |
| OK / Enter / Menu | Open the menu on the right |
| Up / Down in the menu | Move between controls |
| OK in the menu | Select a button or open its choices |
| Left / Right on a slider | Adjust its value |
| Back | Close menu; otherwise press twice within two seconds to exit |
| Volume keys | Normal TV volume control |

The menu contains Mode, Ring Light, Filters, Display, Connection, Change role, and
About. All TV settings are saved. Mirror flip defaults to On; scaling defaults to
Fit. Rotation supports 0/90/180/270 degrees. Ring Light defaults to Soft gradient,
Medium (25% of the shorter screen edge), Neutral, and 100% brightness. Frame and
Ring shapes, Small (15%) and Large (35%) sizes, Warm and Cool colors are available.
The camera fits inside the ring-light area.

Brightness, contrast, saturation, and warmth filters range from -50 to +50 and
apply in both modes. Soft focus is Off/Low/Medium on Android 12+; older devices
hide it. **Reset filters** restores defaults. Returning to the TV app resumes its
saved connection; unreachable streams retry every three seconds.

## Builds and signing

Push to `main` or run **Actions → Build Mirror → Run workflow**. GitHub Actions
uses Ubuntu and JDK 17, installs the SDK, runs `./gradlew :app:assembleRelease`,
transport unit tests, and Android lint. It verifies APK signing and absence of
native libraries, uploads the `mirror-apk` artifact, and publishes `mirror.apk`
in a latest GitHub Release tagged `build-<run_number>`.

The project uses stable AGP 9.3.2, Kotlin 2.4.20 (AGP built-in Kotlin), and Gradle
9.8.0. Compile and target SDK are 36. Version name `1.2.0` and code `120` are
defined once in `gradle.properties`. Bump both for future changes.

Both debug and release builds use `keystore/mirror.jks`. Its alias and passwords
are deliberately committed in `gradle.properties` for this personal project.
Never regenerate or replace this key if existing installations should update.

CameraX uses YUV `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`. Optional CameraX
native rotation, color conversion, and one-pixel shifting are disabled; a Kotlin
stride-aware YUV converter and Android framework image APIs produce upright JPEG
frames at quality 70. Device camera capabilities may select the nearest supported
resolution. The TV uses a bounded native MJPEG reader, BitmapFactory, and a custom
View rather than a WebView. Shared code is under `core/`, with `phone/` and `tv/`
separated for future maintenance.

## CHANGELOG

### 1.2.0

- Initial single-APK phone and Google TV app with a saved role chooser.
- Phone camera foreground streaming, two-client MJPEG server, and LAN discovery.
- Native TV mirror with retry, zoom/pan, ring light, beauty filters, and remote menu.
- Shared signing key and automatic GitHub Actions APK artifacts and latest release.
