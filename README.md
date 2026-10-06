# Mirror 1.6.1

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
   The TV automatically asks for pairing: tap **Pair new TV** on the phone and
   enter its 6-digit code using the TV's number pad (D-pad/OK or touch).
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
all camera endpoints require pairing or explicitly enabled browser authentication.

## Security & pairing

**Existing installations must pair each TV/viewer once after upgrading to 1.4.0.**
Update both camera and viewer. The pairing screen appears automatically when a
Mirror camera is discovered or entered by address and has no saved token.

On the phone, press **Start**, then **Pair new TV**. Keep its code screen visible
while entering the cryptographically random 6-digit code on the TV. The code
expires after 2 minutes; **Regenerate** creates a new one. The on-screen number
pad has 0–9, Delete and OK and supports D-pad/OK/Back and touch. Five incorrect
codes invalidate the code and lock pairing for 60 seconds; the TV shows
“Too many attempts”. Close the code screen to disable pairing.

The camera has a persistent random `cameraId`. Each viewer keeps its random
32-byte token in private SharedPreferences **per cameraId**, so changing the
phone IP and automatic rediscovery do not require pairing again. The phone
persists only SHA-256 token hashes, device names and last-seen times. In
**Paired devices**, **Remove** revokes a device and closes its stream immediately.
It then sees “This TV is no longer paired - press OK to pair again”.

Only `GET /hello` (app identity, cameraId, name, version, requiresPairing) and
`POST /pair` (JSON code/deviceId/deviceName, returning token) are public on the
LAN. `/video`, `/status`, `/control` and all other routes require the
`X-Mirror-Token` HTTP header. Missing/invalid credentials return HTTP 401 with no
data; tokens in URLs are never accepted. Unpaired attempts on these three routes
produce “Blocked connection from <ip>” notifications, limited to once per minute
per IP. Allow notification permission at Start to see these alerts and the
persistent “Standby - waiting for TV” / “Streaming to <TV name> - N viewer(s)”
notification.

**Allow browser viewing** is off by default. Enable it in Phone mode and set a
password. Open `/video` or `/status` in Chrome and use username `mirror` and that
password at the HTTP Basic prompt. Disable the setting to remove the password.
Browser credentials never authorize `/control`. For the **IP Webcam** app,
Quick Menu → Connection has optional Username/Password fields; these are sent as
HTTP Basic credentials only to non-Mirror sources.

The server binds only to the Wi-Fi address and rejects non-private addresses.
Without Wi-Fi it shows “Not on Wi-Fi - connect to the same Wi-Fi as your TV”.
Wi-Fi loss stops the camera, server and discovery advertisement; Wi-Fi return
restores them if Start was still on. **Video and credentials are not encrypted
in this version.** Use trusted local Wi-Fi and do not forward the camera port.
TLS/encryption is reserved for a later release.

## Standby

Phone mode saves **Standby - camera turns on only when your TV connects**, on by
default. Press **Start** while Mirror is in the foreground after each reboot.
The foreground service advertises the camera and serves pairing/status with the
camera off. An authenticated `/video` viewer wakes it; the TV shows
“Waking camera...” for up to 5 seconds. After the last stream disconnects, the
camera turns off after 60 seconds while the server and advertisement stay on.
Explicit browser viewing also wakes the camera when enabled. Turning Standby off
keeps the camera running while Start is on and Wi-Fi is connected.

If Android/your ROM refuses background camera access, Phone mode explains the
fallback: open Mirror briefly to reopen the camera in the foreground; that device
then keeps the camera running instead of using standby. There is no boot receiver
and no automatic start on boot. Battery optimization exclusions still apply.

## Auto-sleep and Delayed mode

Quick Menu → **Auto-sleep** offers Off / 5 / **10 (default)** / 15 / 30 minutes
without a remote key or touch. Sleep disconnects the stream, allowing phone
standby, and shows a dim “Sleeping - press any button to wake” screen. A key or
touch reconnects; volume keys continue to control the TV normally.

Left/Right cycles **Mirror → Ring Light → Delayed**, or choose Quick Menu → Mode.
Delayed lets you turn around and then see your back. Up/Down changes 3 / **5
(default)** / 10 / 15 seconds; Quick Menu → Delay offers the same choices.
“Recording... ready in 5 s” counts down while filling, then a small “Delayed 5s”
badge appears. Changing the delay starts a new buffer. Mirror flip, rotation,
filters, freeze, Show FPS, snapshots and Compare remain available.

The delay buffer holds timestamped **compressed JPEG byte arrays only**, capped
at 60 MiB; oldest frames are dropped first. Only the selected display frame is
decoded, using the existing bitmap pool. A large external-camera stream may not
fit its full requested delay in the cap; reduce resolution/delay in that case.

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
| Left / Right | Cycle MIRROR → RING LIGHT → DELAYED (reverse with Left) |
| Up / Down in MIRROR | Phone camera zoom in 0.25× steps; digital 1.0×–3.0× for external sources |
| Left / Right while zoomed | Pan; return to 1.0× to switch modes |
| Up / Down in RING LIGHT | Brightness 10%–100% in 10% steps |
| Up / Down in DELAYED | Change delay: 3 / 5 / 10 / 15 seconds |
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
apply in all three modes. Soft focus is Off/Low/Medium on Android 12+; older devices
hide it. **Reset filters** restores defaults. Returning to the TV app resumes its
saved connection; unreachable streams retry every three seconds and rediscover cameras after ten
seconds. Tap the video or the touchscreen gear to open the scrollable Quick Menu.

## Clock Face

Phone mode now has a **Clock** section with a live preview. Start the camera
service first, then tap **Clock mode**. The clock is independent of the existing
streaming/standby service; opening it, closing it, or entering Away never stops
capture or disconnects a TV. No TV controls or TV clock overlay are added.

Choose **Cockpit** (B612 Mono, large LOC and secondary UTC, or swap them),
**Minimal** (system thin or B612), **Stacked** (bold B612), or **Word clock**
(English, five-minute steps). Local time follows the system 12/24-hour preference
unless overridden; aviation UTC always uses 24-hour Z notation. Choose a preset,
touch the HSV sliders for a custom color, or enable a vertical two-color gradient.
Date, next alarm, weather, mirror status and seconds have individual saved toggles.

**Show UTC time** can be switched off for a local-only clock. Cockpit hides the
second time line; enabling UTC again restores your saved primary-time preference.
**Clock size** ranges from 40–100% of the center area (default 90%). Pinch
with two fingers on Clock mode or the screen saver to resize it; the saved size
is shared with the settings slider. The large clock sits at the center, date and
alarm at upper left, weather at upper right, and the two roster days at lower
left/right. Information fits independently and cannot shrink the clock.

To move any displayed item, **tap it to select it**, then **hold it briefly and
drag**. The selection outline disappears after eight seconds. Positions are
saved for both Clock mode and the screen saver, and stay within the display with
burn-in movement clearance. You may intentionally overlap items; **Reset layout**
in settings restores the center/corner arrangement. Disable **Allow tap-and-hold
layout editing** if you prefer to prevent accidental changes. Pinching cancels
selection and the exit hold; the brief hint never changes the clock's size.

For automatic use while charging, tap **Set as screen saver**, choose **Mirror
Clock**, and set **When to start: While charging**. The screen saver's settings
button opens the same Clock settings. Screen saver activation and secure-keyguard
behavior depend on your Android ROM; test both on the OnePlus before leaving it
mounted. Configure a secure PIN/password in Android. The clock never dismisses
or unlocks the keyguard. Clock mode can remain visible over the locked screen.
**Hold empty space for two seconds to exit**. When layout editing is disabled,
a stationary two-second hold anywhere exits; taps only show a brief hint.
When the phone is already locked, exiting reveals its normal secure lock screen.
Mirror notifications redact connection details on the lock screen, and the clock
never reads or displays notification contents.

Keep **pixel shift**, **hourly reposition**, and **auto brightness** enabled and
use a low maximum brightness to reduce AMOLED wear. Optional **slow drift** moves
all content together, including info lines. Hourly reposition uses bounded
vertical offsets to retain the chosen arrangement; pixel shift and drift also
respect the display edges. Black is always pure black. These
measures reduce burn-in risk; no always-on OLED display is guaranteed burn-in-free.
Leave seconds and drift off for minute-only scheduled redraws; enabling either
uses one redraw per second, with no continuous animation. Preview does not change
screen brightness or simulate darkness. With no light sensor, use manual brightness;
automatic night/away cannot run without ambient readings.

Night behavior uses **light, never time of day**: after 60 seconds below 5 lux,
the clock becomes Night Red at minimum brightness. After 60 seconds above 15 lux,
it returns to the chosen theme. **Away** draws nothing after 4 / **8** / 12 / 24
hours of continuous darkness without an authenticated viewer; viewing restarts
that inactivity interval. Light above 5 lux resumes the clock immediately.
The sensor remains active while black, and streaming/standby keeps working.
**Dim while LIVE** halves normal clock brightness. Monitoring begins when the
clock opens; reopening conservatively starts a new darkness interval.

Weather is off until a city is selected. Type a city, tap **Find weather city**,
and select the correct match. Open-Meteo geocoding needs no location permission.
Current temperature and condition refresh every 30 minutes while a clock/preview
is visible. The last success is cached; offline or failed refreshes show `--`.
Weather data by Open-Meteo.com. Phone **About Mirror** contains the complete B612
SIL Open Font License; the repository copy is `licenses/B612-OFL.txt`.

All clock preferences live in one versioned `ClockSettings` JSON value in private
SharedPreferences. Fonts are bundled into `res/font` before Gradle by
`python3 scripts/fetch-clock-fonts.py`, using official `polarsys/b612` commit
`48ac6ba67ecab8123e8e36d6aa05367db0c7b638` and verified Git blob hashes. Run that
command before local builds too. Missing or changed fonts fail the build; there
is no font fallback. No new wake locks are acquired by the clock.

### Calendar roster and info colors

In Phone mode → Clock, enable **Show calendar schedule**, grant **Calendar read
access**, and choose the calendar that eCrew exports to. The Google account must
already be added to the phone with Calendar sync enabled. Mirror reads Android's
synced calendar provider; it does not sign into Google separately and never
creates, edits or deletes events. The account-sync permission only lets Mirror
request a refresh from Android's existing sync adapter. It is not event-write access.
Use **Roster calendar** to change calendars and **Refresh calendar now** to recheck.
Disable the schedule toggle to stop calendar monitoring while using the clock.

The compact roster uses **Philippine time (Asia/Manila)** regardless of the phone
or clock timezone. Standard eCrew descriptions supply reporting/debriefing times
and flight legs; `MNL–CEB`, `CEB–MNL`, `MNL–CEB`, `CEB–MNL` becomes
`MNL–CEB–MNL–CEB–MNL · 3:25 AM–9:25 AM`. Repeated sectors stay intact.
Disconnected legs are separated with `/` instead of inventing a connection.
HS, HSA, AS and new training/duty codes are displayed exactly as calendar codes,
with the calendar start/end times. All-day duties show **ALL DAY**. Missing or
invalid flight report/debrief fields use event times with an explicit fallback note.

The main block shows today, including any unfinished overnight duty; tomorrow
appears below. Multiple duties remain grouped until the final debriefing/duty end,
then the next day is promoted and the following day appears beneath it, with dates.
An overnight duty does not disappear at midnight; its end is marked **(+1 day)**.
An empty tomorrow says **Tomorrow: no calendar entry**, not OFF, so you know to
check eCrew's calendar export. Future gaps are not silently skipped.

Mirror queries the selected local calendar on opening, every **15 minutes**, on
provider changes, at **each duty end (including final debriefing)** and at Philippine
midnight while the clock/preview is visible. Scheduled checks also ask Android to
sync the selected account. Google delivery depends on network/account sync and ROM
restrictions; Mirror cannot guarantee a cloud update has arrived. **Calendar checked**
means the last successful local-provider read, not the last Google server sync.
The query covers the previous week and next 35 days (including recurring instances).
A private cache allows offline display and normal duty advancement; offline/failed
reads are marked, and dates outside cache coverage say they need refreshing.
Denied permission, missing calendars and provider failures are not presented as
empty days. Roster details are intentionally visible over the lock screen.

Choose **Schedule color**, **Date color**, **Alarm color**, and **Weather color**
independently using their HSV pickers. The clock retains its own theme/gradient.
Night mode temporarily turns text red, restoring the chosen colors in daylight.
Long schedule lines are fitted independently so they do not squeeze the main digits.

**Camera indicator** replaces the former STANDBY/LIVE/OFF words: a white hollow
circle means the camera service is waiting/ready, a red circle blinking once per
second means a viewer is connected, and a dim gray hollow circle means the service
is off. It moves with the rest of the clock. When the live indicator is enabled,
it uses the existing one-second redraw schedule; no continuous animation or new
wake lock is used. No TV controls, overlays or TV-side roster display are added.

## Builds and signing

Push to `main` or run **Actions → Build Mirror → Run workflow**. GitHub Actions
uses Ubuntu and JDK 17, installs the SDK, runs `./gradlew :app:assembleRelease`,
transport unit tests, and Android lint. It verifies APK signing and absence of
native libraries, uploads the `mirror-apk` artifact, and publishes `mirror.apk`
in a latest GitHub Release tagged `build-<run_number>`.

The project uses stable AGP 9.3.2, Kotlin 2.4.20 (AGP built-in Kotlin), and Gradle
9.8.0. Compile and target SDK are 36. Version name `1.6.1` and code `161` are
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



