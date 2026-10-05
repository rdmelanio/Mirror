# Mirror 1.3.0

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
device name, app version, streaming state, rotationDegrees, cameraFps, encodeMs,
sendFps (completed frame writes across viewers), and clients. Phone mode shows
live camera/encode/send statistics; About → Show FPS shows received/drawn rates. Keep streams on your local network;
the plain HTTP endpoint has no authentication.

## Stream orientation and camera controls

Phone mode saves **Stream orientation: Landscape (default), Portrait, or Auto**.
Physical orientation is tracked even if the phone UI rotation is locked. Landscape
always sends upright 16:9 pixels; a portrait mount is center cropped and shows
“Tip: mount the phone sideways for a wider view”. Portrait sends upright 9:16;
Auto follows the physical mount. JPEG pixels are rotated on the phone in NV21
before the single encode; `/video` sends `X-Rotation: 0` and works in a browser.
The TV's manual Rotation remains an additional override.

`GET /control` on port 8080 accepts `zoom=<ratio>`, `torch=on|off`,
`focus=center`, and `action=snapshot`, returning JSON with `ok` and camera state
(or `error`). `/status` also reports `controls`, `zoom`, `minZoom`, `maxZoom`,
`hasFlash`, and `torch`. Zoom is clamped to the selected camera's limits.
Snapshots use full-resolution CameraX ImageCapture, preserve upright EXIF
orientation without mirroring, and save to **Pictures/Mirror** as
`Mirror_yyyyMMdd_HHmmss.jpg`. Android 8–9 requests gallery storage permission
at Start; Android 10+ uses scoped MediaStore storage.

Quick Menu → Camera offers flashlight (when the camera has flash), focus center,
Zoom +/−/reset, snapshot, freeze/unfreeze, Compare, and Clear snapshots.
A successful snapshot flashes white and shows “Saved to phone gallery”. The TV
retains at most three independent frame copies in memory. Compare displays them
side by side, mirrored according to Mirror flip, with labels 1–3. Left/Right
selects a frame and OK opens it full screen; tapping a frame does the same.
Back or **Back to live mirror** returns to the live view. Clearing snapshots or
closing the activity drops the copies; nothing is written to TV storage.
Freeze keeps the displayed frame while the MJPEG reader stays connected and
skips decoding; OK resumes, and a short video tap still opens Quick Menu.

## TV remote controls

| Button | Action |
| --- | --- |
| Left / Right | Switch MIRROR / RING LIGHT |
| Up / Down in MIRROR | Phone camera zoom in 0.25× steps; digital 1.0×–3.0× for external sources |
| Left / Right while zoomed | Pan; return to 1.0× to switch modes |
| Up / Down in RING LIGHT | Brightness 10%–100% in 10% steps |
| OK / Enter / Menu | Open the menu; short OK resumes a frozen frame |
| Long OK / Play-Pause / long touch | Freeze or resume the displayed frame |
| Up / Down in the menu | Move between controls |
| OK in the menu | Select a button or open its choices |
| Left / Right on a slider | Adjust its value |
| Back | Close menu; otherwise press twice within two seconds to exit |
| Volume keys | Normal TV volume control |

The menu contains Mode, Camera, Ring Light, Filters, Display, Connection, Change role, and
About. All TV settings are saved. Mirror flip defaults to On; scaling defaults to
Fit. Rotation supports 0/90/180/270 degrees. Ring Light defaults to Soft gradient,
Medium (25% of the shorter screen edge), Neutral, and 100% brightness. Frame and
Ring shapes, Small (15%) and Large (35%) sizes, Warm and Cool colors are available.
The camera fills the inner ring-light window with a center crop, so no letterbox appears inside the light.

Brightness, contrast, saturation, and warmth filters range from -50 to +50 and
apply in both modes. Soft focus is Off/Low/Medium on Android 12+; older devices
hide it. **Reset filters** restores defaults. Returning to the TV app resumes its
saved connection; unreachable streams retry every three seconds and rediscover cameras after ten
seconds. Tap the video or the touchscreen gear to open the scrollable Quick Menu.

## Builds and signing

Push to `main` or run **Actions → Build Mirror → Run workflow**. GitHub Actions
uses Ubuntu and JDK 17, installs the SDK, runs `./gradlew :app:assembleRelease`,
transport unit tests, and Android lint. It verifies APK signing and absence of
native libraries, uploads the `mirror-apk` artifact, and publishes `mirror.apk`
in a latest GitHub Release tagged `build-<run_number>`.

The project uses stable AGP 9.3.2, Kotlin 2.4.20 (AGP built-in Kotlin), and Gradle
9.8.0. Compile and target SDK are 36. Version name `1.3.0` and code `130` are
defined once in `gradle.properties`. Bump both for future changes.

Both debug and release builds use `keystore/mirror.jks`. Its alias and passwords
are deliberately committed in `gradle.properties` for this personal project.
Never regenerate or replace this key if existing installations should update.

CameraX uses YUV `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`. Optional CameraX
native rotation, color conversion, and one-pixel shifting are disabled; a Kotlin
stride-aware YUV converter and Android framework JPEG APIs encode once at quality 70 (adaptive down to 60). Rotation is
sent as metadata for the viewer. ResolutionSelector requests 720p/1080p without
exceeding the selected dimensions, and Camera2 requests a supported 30 fps range. The TV uses a bounded native MJPEG reader, BitmapFactory, and a custom
View rather than a WebView. Shared code is under `core/`, with `phone/` and `tv/`
separated for future maintenance.

## CHANGELOG

### 1.2.1

- Fix slow Phone-mode capture: bulk stride-aware YUV copies, reusable buffers, a
  single JPEG encode, supported camera FPS request, and bounded 720p/1080p selection.
- Send the newest frame independently to each viewer with buffered, paced writes;
  adapt JPEG quality toward 60 and expose live capture, encode, send, and client stats.
- Optimize viewer parsing and background decoding with frame dropping, safe bitmap
  reuse, cached drawing transforms/filters, automatic camera rotation, and Show FPS.
- Fix touch Quick Menu access and scrolling, predictive/remote Back handling, and
  status placement beside the menu. Preserve all existing mirror/ring-light controls.
- Retry NSD discovery after connection failure, use an empty initial address with
  a hint, and show only a Wi-Fi IP or a clear Wi-Fi warning on the phone.
- Retain saved Front/Rear and Resolution choices and the existing APK signing key.

### 1.2.0

- Initial single-APK phone and Google TV app with a saved role chooser.
- Phone camera foreground streaming, two-client MJPEG server, and LAN discovery.
- Native TV mirror with retry, zoom/pan, ring light, beauty filters, and remote menu.
- Shared signing key and automatic GitHub Actions APK artifacts and latest release.

