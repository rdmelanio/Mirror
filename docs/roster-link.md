# Roster Link (phone role, Mirror 1.10.1)

Open **Roster Link** from Phone home or Settings. Open eCrew and sign in on the portal's own page. Mirror never reads credential fields; Android Autofill remains available. Once the dashboard shows My Schedule, Mirror shows “eCrew linked” and enables background refresh. Mirror automatically starts the first capture in the same open browser three seconds after linking. **Fetch roster now** uses that browser again without a second dashboard load.

If automatic Print fails, open My Schedule and tap the portal's Print button yourself. The same download/blob/viewer hooks capture the PDF. Alternatively, choose **Import roster PDF**, or share an application/pdf file to **Share to Mirror**. Imported files require a Personal Crew Schedule Report text layer; malformed or incomplete captures keep the last good parsed roster.

Mirror only detects **Confirm all changes**. It never clicks it, its enclosing control, or a confirmation dialog. Review and acknowledge scheduling changes yourself in eCrew.

## Refresh and alarms

Roster alarms & refresh offers 15, 30 (default), or 60 minutes. Android WorkManager is best-effort: network availability, Doze and vendor background restrictions can delay jobs. Mirror also requests a refresh two hours before reporting and on opening when its last successful capture is over ten minutes old. A session-expired redirect pauses refresh until you sign in again. The next-duty card and list show amber stale state after six hours, red after 24 hours.

PREPARE defaults to 80 minutes before reporting. Add up to five alarms; choose labels, offsets in five-minute steps, and individual switches. Flights and AS default on, HSA off, and unknown timed codes on. Offsets cross midnight using the report instant in the first departure station's timezone. Enable Android notification/full-screen alarm access as needed. Exact alarms use the system alarm-clock API; a denied permission is recorded in the capture log. Existing calendar departure alarms remain separately configurable.

Tap the amber MASTER CAUTION button to acknowledge; Snooze delays ten minutes. An original synthesized chime repeats every three seconds with vibration. Select system alarm sound in settings if preferred. After five minutes, Mirror stops and leaves a missed-alarm notice. Skip next PREPARE suppresses its next scheduled occurrence, including after a report-time change.

## Private diagnostics

Capture log keeps the last 200 steps, timestamps, fixed result strings, HTTP statuses, and request paths without queries or fragments. It contains no response bodies, credential fields or cookie values. Copy log for diagnosis. Share last PDF opens an explicit sharing confirmation because the PDF includes other crew members' private information. Log out of eCrew uses the portal logout control, expires cookies for the eCrew origin and cancels background refresh. Clear data deletes PDFs, parsed JSON, alarm/refresh preferences and the WebView session/cache.

PDFs and parsed JSON stay in app-private storage with backup disabled. Only latest.pdf and previous.pdf are retained. The last captured PDF remains available even when parsing fails. No analytics, automatic uploads, cloud synchronization or TV roster processing is added. The origin-scoped AndroidX blob bridge rejects foreign origins and messages while the top-level page is /Login; capture scripts are not evaluated there.

Portal selectors and export behavior are defensively implemented from the supplied observation, but have not been verified against an authenticated live portal. Keep Android System WebView updated. On a failure, try manual Print or PDF import, then inspect the capture step and sanitized HTTP status. An unknown airport uses Asia/Manila and logs a warning. Verify report times against the portal before relying on alarms during initial on-device validation.

## Synthetic validation

JVM fixtures cover OFF/RVL, multiple duties in a column, actual/estimated times, delays, DHC legs, standby and mid-leg midnight continuation, memo flags, Unicode cleanup, positioned column binning, timezone fallback, JSON round-trip, roster diff summaries and alarm movement/skip plans. No real PDF or personal crew data is checked in.

The supplied 30/10 03:10 report example has PREPARE at **30/10 01:50**. A separate 00:30 report fixture verifies rollback to the previous day at 23:10.

The visible eCrew screen owns the session from onStart to onStop; it destroys its browser when leaving. Background refresh waits at least three minutes after interactive login/capture and periodic work starts after its full selected interval. No guessed report URL is re-requested during browsing. Capture hooks and polling stop outside an active fetch; a manual Print click activates the same capture scope.

Screenshots are allowed by default. Enable **Block screenshots on roster screens** in Roster alarms & refresh if desired. An unreadable day says **⚠ check eCrew** and schedules no alarms, while other days remain available. More than three unreadable dates reject the capture. In **Leave for duty**, choose **Roster source: Calendar | eCrew Roster Link**; the default selects eCrew when linked with parsed data. PREPARE remains independent. Tap the amber **ROSTER CHANGED** clock/screen-saver line to read its summaries and acknowledge that banner; portal confirmation remains manual.
