# Changelog

## 1.10.2

- Keep one phone eCrew WebView and its session lease for the complete activity lifetime, including stop/start, fold, rotation and other configuration changes. Interactive fetches use the current document; only an explicit Refresh reloads it.
- Recognize published-until My Schedule labels, sidebar calendar links and avatar/top-bar login indicators. Use the Android browser UA without embedded-WebView tokens and suppress X-Requested-With when supported.
- Wait for eCrew's Login page before logout cleanup. Clear origin cookies and WebStorage, then use an offline app-owned eCrew-origin cleanup document for sessionStorage, IndexedDB, CacheStorage and service workers; no script runs in Login and no other origin is selected. Capture-log Clear data uses the same ordered logout flow before deleting private roster data.
- Detect eCrew's another-active-session message without idle evaluateJavascript polling, cancel work without retrying, and add instance/owner, lifecycle, cookie-count and storage diagnostics.
- Add session matcher, lifecycle, logout ordering, termination and user-agent regression tests. Parser, alarms, clock face, TV, camera, warning audio and signing are unchanged.

## 1.10.1 — Roster Link login and review fixes

- Give the visible eCrew screen exclusive process-wide session ownership; workers yield and destroy their browser before the activity opens. Delay periodic work by its interval and suppress background fetching for three minutes after interactive login/capture. Automatically perform the first fetch in that same browser after a three-second delay.
- Use the real WebView user agent. Remove regex interception/re-downloads; activate fetch/XHR/blob/window capture only for fetching or a manual Print tap, restore hooks afterward, and stop idle polling. Restrict cookie-authenticated downloads to explicit browser downloads or active print-overlay sources with the same UA and current-page Referer.
- Log sanitized main-frame paths, available HTTP statuses and non-login document titles. Login pages receive no injected script or field/title inspection. Notify session expiry only during a fetch from an already linked session. Add explicit portal logout plus origin cookie expiry and work cancellation.
- Make screenshot blocking optional, default off, for roster screens. Tolerate up to three unreadable roster days, mark them CHECK without alarms, preserve other dates and show “⚠ check eCrew”. Header/period/column failures and more than three unreadable days still retain the last good roster.
- Select Calendar or eCrew Roster Link for the existing Leave for duty caution/warning alarms; default to eCrew when linked with parsed data. Keep PREPARE independent and retain source-independent stage delivery history to avoid duplicate firing.
- Persist an amber ROSTER CHANGED clock/screen-saver banner until its summary is opened; retain newer changes arriving during review. Give roster change notifications a separate channel and vibration pattern.
- Add session ownership/concurrency, cooldown/delay, partial parsing, source selection/stage replay and banner acknowledgement regressions. Version 1.10.1, code 192. Preserve TV, streaming, launch, signing and bundled warning audio.

## 1.10.0 — Roster Link

- Add phone-only eCrew WebView login, automatic roster capture, manual Print capture, PDF file import and Share to Mirror. Password fields are never inspected; cookies remain private to WebView. Confirmation of scheduling changes remains manual.
- Add position-based PDF text extraction and column grammar with overnight duties, actual/estimated times, deadhead release estimates, memos, legends and station timezones. Keep the last good roster on failures, private JSON and the latest two captured PDFs.
- Add constrained 15/30/60-minute WorkManager refresh, refresh before reporting, session-expired notifications, stale state, change summaries, next-duty countdown and the optional clock duty line.
- Add up to five adjustable roster alarms with PREPARE default 80 minutes, exact alarm-clock scheduling, boot/time/update rescheduling, skip-next, snooze and a lock-screen amber MASTER CAUTION. Synthesize an original alarm chime in code; offer system sound.
- Add a private 200-step capture log, explicit PDF sharing and clear-data controls. Use an origin-scoped AndroidX WebView message bridge instead of a globally exposed JavascriptInterface. Portal automation remains unverified against a live authenticated account; manual Print and import remain available.
- Add synthetic non-personal grammar, column, timezone, JSON, diff and alarm-plan regression tests. Correct the supplied 03:10 minus 80-minute expectation to same-day 01:50 and cover actual date rollback separately.
- Version 1.10.0, code 191, as requested after inspecting main at 1.9.0. No TV roster loading or camera/stream changes.

## 1.9.0

- Add a phone-only white mirror icon to the clock and screen saver. Tap to wake the paired TV and launch Mirror; hold to select/drag, then pinch to resize the icon independently of the clock. Save position and size, add a size slider, visibility toggle and position reset.
- Add a TV launch settings category with Android 11+ secure wireless ADB pairing, a wake/launch test, address/connection-port updates and Forget pairing. Pair Mirror separately from Bugjaeger once; retain the encrypted client identity across normal app updates and reconnects.
- Discover only the paired TV's advertised secure ADB service on demand, handling changing ports/IPs. Fall back to the configured connection address/port when discovery is unavailable.
- Send wake-up before launching the existing TV activity; accept Android's harmless already-running response. Bound discovery and socket operations, prevent concurrent launches, report failures, and close the connection on success or failure.
- Keep all TV discovery/ADB work off the UI thread and independent of camera streaming. No persistent ADB connection, background scan, added foreground service or wake lock. Retain a universal APK without native libraries using pure Java TLS/SPAKE2.
- Bundle third-party notices/licenses in About and add secure pairing/TLS/ADB protocol and settings migration regression checks. Preserve existing clock, ECAM alerts, roster, weather, camera and TV features. Version name 1.9.0, version code 190.

## 1.8.0

- Bundle the supplied Airbus master warning as a prepared seamless PCM loop, removing its introductory fade-in and MP3 restart gap while retaining the original warning cadence. Make this recording the warning default on upgrade; keep duration, caution audio and immediate Stop controls.
- Introduce a focused Phone home with live preview, Start/Stop camera, Clock mode, one Settings button and compact camera status.
- Group all detailed phone controls into settings categories. Wide screens show left navigation, a vertical separator and right-hand controls; portrait uses category pages with Back navigation.
- Add a phone-only ECAM-inspired black/B612 interface and alert panels with amber/red annunciators, green duty data and cyan actions. Keep warning details/Stop readable while the annunciator flashes.
- Fix night mode overriding caution text to red; retain existing clock styles, custom colors and saved positions.
- Preserve pairing/browser security, camera controls, diagnostics, alarm tests, calendar/weather settings, screen saver setup and display protection in their respective categories.
- Validate the audio splice before packaging and add alert-layout geometry coverage. Version name 1.8.0, version code 180.

## 1.7.0

- Add optional phone-only Leave for duty alarms tied to the selected synced roster calendar in Philippine time.
- Configure caution and warning independently: defaults 60 and 50 minutes before reporting; warning sound lasts 10 seconds, editable from 1–600 seconds.
- Add persistent amber caution and slow red/black warning screens over the secure lock screen. Acknowledge/Stop restores the saved clock; warning clears automatically after ten minutes.
- Include flights, airport standby and timed training/new duty codes; skip OFF, HS/HSA and all-day entries. Add editable excluded codes and explicit opt-in for incomplete flight event times.
- Schedule precise alarms independently of the visible clock, refresh with Android calendar jobs/provider changes, rebuild after reboot/update/time changes, and revalidate synced duties before sounding.
- Prevent duplicate stage delivery, handle overnight reporting, reschedule changed duties, and trigger only the most urgent overdue stage before reporting.
- Add original Airbus-inspired tones, selectable audio files, test buttons, next-alert/readiness status, and alarm-volume/DND setup links. Keep notifications private and camera/TV streaming independent.
- Add regression coverage for duty eligibility, stage timing, late updates, overnight duties, delivery history, cancellation and settings migration. Version name 1.7.0, version code 170.

## 1.6.1

- Give the clock a large, independent center area. Place date/alarm at upper left,
  weather at upper right, and the two duty days at lower left/right by default.
  Long routes and extra duties fit their own regions without shrinking the clock.
- Add lock-screen and screen-saver layout editing: tap an item to select it,
  then hold briefly and drag. Save each item's position in the clock settings JSON.
  Add an editing toggle and Reset layout button; retain pinch sizing and UTC toggle.
- Keep all clock information moving together for burn-in protection, with safe
  edge clearance and bounded hourly repositioning. Keep independent info colors.
- Hold empty space for two seconds to exit while editing is enabled. Cancel pending
  gestures when leaving the clock; camera streaming and the secure lock remain active.
- Add center/corner geometry, position persistence and safe placement coverage.
  Version name 1.6.1, version code 161. No TV changes.

## 1.6.0

- Add an optional read-only calendar roster to the phone clock and screen saver.
  Select a Google calendar synced on the phone; Mirror never edits calendar events.
- Condense flight descriptions into complete routes (including repeated turns),
  with reporting–debriefing times. Preserve HS, HSA, AS and unfamiliar training/duty
  codes with their event duty times; show ALL DAY for all-day entries.
- Keep all roster dates/times in Asia/Manila. Keep overnight duties through debriefing,
  retain multiple duties until the last ends, then promote the following day.
  Always show a second day and explicitly report Tomorrow: no calendar entry.
- Recheck on opening, every 15 minutes, on provider changes, at duty ends and
  Philippine midnight. Request Android calendar-account sync at scheduled checks;
  Google sync completion remains OS-controlled. Retain a private offline cache and
  show when the local calendar was checked, with offline/cached indications.
- Add independent custom HSV colors for schedule, date, alarm and weather.
  Night mode still temporarily overrides all text to Night Red.
- Replace camera-status words with a moving white waiting circle, slow red blinking
  viewing circle, or dim gray off circle, avoiding confusion with roster standby codes.
- Keep large clock digits independent of long roster-line widths; preserve pinch
  size, touch guard, pixel shifting, camera/standby service and all TV features.
- Add roster regression tests for routes, repeated turns, unknown codes, overnight
  and all-day duties, multiple daily duties, empty tomorrow, malformed times and
  device timezone changes. Version name 1.6.0, version code 160.

## 1.5.1

- Add a saved Show UTC time toggle. Disable it for local time only; re-enable it
  to restore the primary UTC/local preference. Cockpit hides its second line when off.
- Add two-finger pinch-to-zoom in Clock mode and Mirror Clock screen saver, plus
  a synchronized 40–100% Clock size slider and a larger default layout.
- Keep the entire clock and info lines inside the display at every size, with
  pixel-shift clearance. Save zoom after gestures rather than writing every frame.
- Fix the exit hint resizing/moving the clock. Pinching and finger movement cancel
  the exit hold, preventing accidental exits while adjusting size.
- Refresh settings controls when returning from the clock; respect system insets
  in screen saver settings and use an explicit screen saver settings component.
- Preserve camera/standby lifecycle, TV code, signing key and existing preferences.
  Version name 1.5.1, version code 151.

## 1.5.0

- Add phone-only Clock mode and interactive Mirror Clock screen saver using one
  custom view, guarded two-second exit, secure-keyguard display and private notifications.
- Add Cockpit, Minimal, Stacked and Word clock styles, official pinned B612/B612 Mono
  fonts and bundled OFL license, local/UTC priority, time format, HSV colors and gradients.
- Save all clock preferences in one JSON data class; add live preview, optional date,
  alarm, city-based Open-Meteo weather, mirror status and seconds.
- Add pixel shift, slow drift, hourly reposition, smoothed sensor brightness,
  light-based night/away hysteresis and live-stream dimming without camera lifecycle changes.
- Keep existing TV modes, camera controls, pairing, standby, auto-sleep, delayed mirror,
  Show FPS and statistics; no TV-side changes. Version name 1.5.0, version code 150.

## 1.4.0 (140) — 2026-10-05

- Add cameraId-based pairing, a remote/touch number pad, 2-minute secure codes,
  five-attempt lockout, hashed token persistence, authenticated camera routes,
  paired-device management, immediate stream revocation and blocked-IP alerts.
- Add opt-in password-protected browser video/status (no browser control),
  optional IP Webcam Basic credentials, Wi-Fi interface binding and loss/recovery.
  HTTP remains unencrypted; existing viewers pair once after upgrading.
- Add saved default-on phone standby with authenticated stream wake, 60-second
  idle shutdown, connected-viewer notifications and a background-camera fallback.
  Start stays user initiated; there is no boot auto-start.
- Add saved TV auto-sleep (default 10 minutes), dim sleep screen and key/touch wake.
- Add Delayed mode with 3/5/10/15-second options, compressed-JPEG buffering capped
  at 60 MiB, countdown/badge and the existing single-frame decode/bitmap pool.
- Preserve orientation, real zoom/pan, flashlight, focus, snapshots/Compare,
  freeze, Ring Light, filters, touch/remote, stats, Show FPS and rediscovery.
- Extend security, HTTP and bounded-buffer JVM regression coverage. Keep the
  existing universal 32/64-bit APK, signing key, 720p encoder and 30 fps send cap.

Hardware acceptance still requires the OnePlus 6T (Android 14), Z Fold 7
(Android 16) and TCL Google TV (Android TV 14, 32-bit). Automated checks do not
measure their 24–30 fps performance or establish vendor background-camera policy.

## 1.3.0 (130) — 2026-10-05

- Add saved Landscape / Portrait / Auto stream orientation and physical mount
  tracking independent of the phone screen rotation. Crop and rotate NV21 on
  the phone before one JPEG encode; upright browser/TV streams send X-Rotation 0.
- Fill the inner Ring Light window with a center crop; retain Mirror Fit/Fill,
  mirror flip, and the manual rotation override.
- Add the phone /control API for clamped CameraX zoom, flashlight, center focus,
  and full-resolution, upright, unmirrored MediaStore gallery snapshots.
  Extend /status with camera capabilities and zoom/torch state.
- Add remote and touch Camera menu controls, real phone zoom with external-source
  digital fallback, freeze via long OK/Play-Pause/long touch, and outfit Compare
  for the last three snapshot frame copies held only in TV memory.
- Retain 720p/1080p, rear/front camera, two viewers, discovery/reconnect, filters,
  Ring Light settings, phone stats, Show FPS, signing, and universal 32/64-bit APK.
  Keep latest-frame backpressure, reusable YUV/decode buffers, and a 30 fps send cap.
- Add NV21 aspect/crop/rotation and concurrent camera-control transport regressions.

Hardware validation: use OnePlus 6T Android 14 at 720p with Show FPS on the Z Fold 7
Android 16 and TCL Android TV 14. Verify upright output in all four mounts/modes,
Fit on a 16:9 display, no black inner ring window, camera controls, full-resolution
unmirrored gallery images, freeze/compare, remote/touch, and normal volume keys.
Confirm at least 24 fps on the camera and weak 32-bit viewer. This device benchmark
requires physical hardware and is not established by the automated build checks.


