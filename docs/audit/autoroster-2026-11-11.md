> Publication update: the owner explicitly authorized a GitHub prerelease after this report was prepared. The release is labeled diagnostic/incomplete; the technical limitations below remain unchanged.

# Automatic roster follow-up — in progress, not release ready

Remote main was fetched before changes: 368f0b9ea96221c3dee56e45add4ef1dde26b9e2, unchanged from supplied baseline. Existing local remediation changes were retained (1.12.13/218); they previously passed 341 lite / 351 full tests. Signing identity is unchanged and SEC-01 remains accepted/deferred. No push, tag or publication is authorized.

The new owner instructions distinguish ordinary sign-out from explicitly forgetting the encrypted login. This supersedes the previous combined logout action. Existing credentials are preserved on upgrade.

## Evidence

The private five-page PDF was read only outside Git. Poppler positioned text was passed to the same layout parser now used after Android PDFBox extraction, and the private JVM verification passed. This confirms that the shared layout parser accepts that extraction, including adjacent 01/12 beyond the declared November period. It does **not** prove the Android PDFBox glyph extraction path or the accuracy of every duty/sector; those remain device gates. Neither the PDF, extracted text, identifiers nor personal duty records are committed or placed in public fixtures. Public tests use synthetic data.

## Priority status

| ID | Status | Evidence / remaining gate |
|---|---|---|
| ECREW-01 | BLOCKED BY LIVE SCHEMA | Separate automatic structured acquisition navigates to My Schedule and requests storage snapshots without Print. No live portal schema adapter is invented. Typed ingestion exists, but automatic conversion cannot be declared functional until actual authorized published records are available. |
| ECREW-02 | NEEDS DEVICE TEST | Existing origin/session-owned navigation is reused for structured acquisition; DOM flyout tests retained. Real login/frame renewal needs verification. |
| ECREW-03 | BLOCKED BY LIVE SCHEMA | Provided evidence lists storage keys, not their JSON value shapes or completeness. ValidatedRoster and RosterStore.acceptStructured provide a conservative typed boundary; live storage/calendar/detail adapters remain incomplete. |
| ECREW-04 | BLOCKED BY LIVE SCHEMA | Typed boundary rejects missing coverage/duties, unpublished sources, implicit OFF and incomplete timed duties. Full actual/scheduled sector and station-time mapping requires real schema evidence. |
| ECREW-05 | NEEDS DEVICE TEST | Direct typed ingestion shares roster merge, crew guard, transaction, cache, Clock/alarm refresh paths. RosterMergeTest verifies that overlapping imports preserve trusted duties/memos on unsupplied dates. PDF archives remain untouched for direct JSON commits. Source provenance is stored as an additional compatible JSON field. Actual Android side-effect integration is outstanding. |
| ECREW-06 | FIXED SOURCE / NEEDS DEVICE TEST | Raw storage capture no longer marks a fetch successful. Only accepted roster parsing can do so. Separate lastAttempt/lastSourceCapture/lastSuccess fields; UI warns that uncommitted source data needs validation. An old test explicitly expected the false-positive behavior; its assertion is corrected to require failure. |
| ECREW-07 | NEEDS DEVICE TEST | Bounded adjacent calendar date resolution supports year rollover/leap dates; shared layout parser verified against private positioned extraction. Full Android extraction and exact duty accuracy are not claimed verified. |
| ECREW-08 | PARTIAL | Existing bounded content stream and atomic import retained. Stable granular PDF error categories and Android picker/mime integration remain outstanding. |
| ECREW-09 | PARTIAL | PDF fallback remains independent; WAIT_VIEWER bound raised to 60s within the existing 240s total bound. No fabricated viewer event or endpoint. Full event-driven alternatives remain incomplete. |
| ECREW-10 | NEEDS DEVICE TEST | Native Gecko consent before encrypted credential save, autofill without submit, separate keep/forget sign-out actions. Existing saved login preserved. Actual dynamic login form/Keystore behavior requires phone. |
| ECREW-11 | NEEDS DEVICE TEST | Existing session coordinator and quiet period retained; structured work uses the same lease. Session termination does not clear login or roster. |
| ECREW-12 | NEEDS DEVICE TEST | Save diagnostic ZIP uses ACTION_CREATE_DOCUMENT and streamed content resolver output with truthful error/cancellation handling. Share and FileProvider hardware validation remain outstanding. |
| ECREW-13 | PARTIAL | Storage bridge reads only explicitly allowed schedule keys, excluding unrelated token/password entries before reading values; mock DOM regression added. Raw existing captures remain private; exhaustive field-level redaction requires actual schema. |
| ECREW-14 | PARTIAL | New synthetic spillover, typed validation, direct-JSON PDF preservation, structured-no-print and false-success tests; private positioned-layout check. No real source-to-Android-Clock/alarm integration or physical UAT completed. |
| ECREW-15 | BLOCKED BY LIVE SCHEMA / DEVICE | Login→correct automatic usable roster acceptance is **not complete**. Do not replace this with a successful raw capture claim. |

Earlier SEC/AIR/CAM/TV/ROSTER/LAN/PRIV/API/DIAG/DOC findings and hardware gates remain tracked in remediation-218.md; this follow-up does not claim to close their remaining device/security rollout work. SEC-06 source hardening is now implemented locally: read-only verification, a separate publication job requiring successful verification, downloaded-artifact identity/flavor/signature checks before publication, and immutable action SHAs resolved from official repository tag refs (action-pins-2026-11-11.json). No workflow was triggered; actual CI execution and administrative branch protections remain pending.

## Required private evidence to finish the adapter

Use Save diagnostic ZIP from the phone after an authenticated My Schedule load. Supply that capture privately, or an anonymized equivalent preserving JSON keys, value types, dates, sector grouping, publication flags and duty time metadata. It must not contain passwords, cookies or session tokens. The PDF alone cannot establish the live browser schema. No account password or automated authentication is requested.

## Real-device acceptance

Open eCrew, consent to remember login, close/reopen and confirm both fields fill; tap Log In yourself. Confirm My Schedule opens without Print. Until a verified adapter is implemented, expect a non-success warning and preservation of the last trusted roster/alarms. After adapter validation, require committed roster evidence and correct next duty, sectors, standby/rest, Clock and alarms before passing ECREW-15. Test sign-out keeping login versus explicit forgetting; failed cleanup must remain retryable. Save ZIP through Android's picker, test cancellation and unwritable destinations. Import the original PDF privately and compare every duty/sector against the source. Never share its personal content in public test output.

## Validation tracking

37 extension tests passed, including exclusion of unrelated storage keys. Private positioned extraction was verified with the shared parser; a synthetic equivalent runs when no private fixture is supplied. Final results are recorded below. One existing test explicitly asserted the false-success bug; it now requires failure for raw capture without roster commitment. No test is disabled or skipped.

## Additional confirmed defect

ROSTER-MERGE (medium): an overlapping PDF or structured import covering fewer dates than its declared period previously removed old duties on all declared dates. RosterMerge now replaces only explicitly supplied dates, preserving unsupplied trusted duties and memos. RosterMergeTest reproduces a two-day trusted roster followed by a one-day update. This does not infer OFF or invent missing schedule records.

## Release decision

Do not publish this as a completed automatic-roster repair. Main acceptance is blocked until actual authorized JSON shapes can be mapped, validated and exercised through RosterStore/Clock/alarms on the phone. Additional broad audit closure, Android PDFBox extraction, saved-login autofill, ZIP provider behavior, TV/camera and energy validation remain outstanding. Source implementation and all physical acceptance are distinct. No device measurements or repository-administration changes are claimed.

## Final local verification

- `MIRROR_PRIVATE_LAYOUT=/workspace/work/private-roster-verify/layout.json /workspace/work/gradle.sh :app:testLiteDebugUnitTest :app:testFullDebugUnitTest :app:lintLiteRelease :app:lintFullRelease :app:assembleLiteRelease :app:assembleFullRelease`: BUILD SUCCESSFUL in 3m57s; **347 lite / 357 full**, zero failures/errors/skips. The helper sets cached JDK/SDK/Gradle paths and `--offline --max-workers=4 --console=plain`. Log: /workspace/work/autoroster-validated.log.
- `env -u MIRROR_PRIVATE_LAYOUT /workspace/work/gradle.sh :app:testLiteDebugUnitTest --tests '*RosterSpilloverTest'`: passed the synthetic layout without relying on the private sample.
- `node --test scripts/check-ecrew-extension.cjs`: **37 passed**, none failed/skipped.
- `python3 scripts/check-phone-theme.py`, `check-audit-metadata.py`, `check-warning-loop.py`: passed. Metadata validation now checks immutable action references, read-only default permissions and dependent publication.
- Lint: lite **0 errors / 229 warnings**; full **0 errors / 263 warnings**. Warnings have not been hidden or globally suppressed.
- Both APKs pass check-apk-flavors.py and check-release-apks.py after aapt/apksigner inspection: **com.mirror.app**, **1.12.13 / 218**, unchanged release certificate, lite universal/no Gecko/native models, full arm64/Gecko/models. No APK signing source or key was changed.
- New local artifacts are under /workspace/work/autoroster-2026-11-11, preserving mirror.apk and mirror-phone.apk filenames. SHA-256: lite `a839f2c33c4814d9897bfc7f302f86e1fc5d02b0edd33ca3217dea5c6ae746d7`; full `e55108a2d72a0603fa92a9d15cbe90e44340bffa314521ab34faad04dbf55a1e`.
- Real Android/Gecko/Clock/alarm integration and the user's login→usable roster acceptance **have not passed**. There is no live schema adapter yet. Repository-wide manual inspection is not claimed complete; the inventory contains 250 source/test/resource files.

## Updated source inventory

- `.github/workflows/build.yml`
- `CHANGELOG.md`
- `README.md`
- `app/src/full/assets/ecrew/content.js`
- `app/src/full/java/com/mirror/app/phone/AirRemote.kt`
- `app/src/full/java/com/mirror/app/phone/AirRemotePanel.kt`
- `app/src/full/java/com/mirror/app/phone/AirRemoteTv.kt`
- `app/src/full/java/com/mirror/app/phone/AirVisionEngine.kt`
- `app/src/full/java/com/mirror/app/phone/roster/EcrewFirefox.kt`
- `app/src/full/java/com/mirror/app/phone/roster/EcrewLoginStore.kt`
- `app/src/lite/java/com/mirror/app/phone/roster/EcrewFirefox.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/mirror/app/core/LocalCameraTls.kt`
- `app/src/main/java/com/mirror/app/core/PairingGeneration.kt`
- `app/src/main/java/com/mirror/app/phone/AirControlSession.kt`
- `app/src/main/java/com/mirror/app/phone/AirDiagnostics.kt`
- `app/src/main/java/com/mirror/app/phone/AirJoystick.kt`
- `app/src/main/java/com/mirror/app/phone/AirKeyQueue.kt`
- `app/src/main/java/com/mirror/app/phone/AirObservation.kt`
- `app/src/main/java/com/mirror/app/phone/AirOwnership.kt`
- `app/src/main/java/com/mirror/app/phone/AirPoseGeometry.kt`
- `app/src/main/java/com/mirror/app/phone/AirTransportSchedule.kt`
- `app/src/main/java/com/mirror/app/phone/CameraEpochWatchdog.kt`
- `app/src/main/java/com/mirror/app/phone/CameraService.kt`
- `app/src/main/java/com/mirror/app/phone/ClockHost.kt`
- `app/src/main/java/com/mirror/app/phone/MjpegServer.kt`
- `app/src/main/java/com/mirror/app/phone/PairingAuthority.kt`
- `app/src/main/java/com/mirror/app/phone/PhoneControlsPanel.kt`
- `app/src/main/java/com/mirror/app/phone/WakeAccounting.kt`
- `app/src/main/java/com/mirror/app/phone/roster/AcceptedPdf.kt`
- `app/src/main/java/com/mirror/app/phone/roster/CrewImportPolicy.kt`
- `app/src/main/java/com/mirror/app/phone/roster/EcrewAutomation.kt`
- `app/src/main/java/com/mirror/app/phone/roster/EcrewEngine.kt`
- `app/src/main/java/com/mirror/app/phone/roster/EcrewScheduleData.kt`
- `app/src/main/java/com/mirror/app/phone/roster/EcrewStorage.kt`
- `app/src/main/java/com/mirror/app/phone/roster/LocalLogoutCleanup.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RawCaptureCleanup.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RosterCalendarDates.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RosterFetcher.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RosterMerge.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RosterPdf.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RosterStore.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RosterTransaction.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RosterUi.kt`
- `app/src/main/java/com/mirror/app/phone/roster/ValidatedRoster.kt`
- `app/src/main/java/com/mirror/app/tv/SourceAuth.kt`
- `app/src/main/java/com/mirror/app/tv/TvActivity.kt`
- `app/src/main/res/xml/mirror_clock_dream.xml`
- `app/src/test/java/com/mirror/app/phone/AirConfidence218Test.kt`
- `app/src/test/java/com/mirror/app/phone/AirDiagnostics218Test.kt`
- `app/src/test/java/com/mirror/app/phone/AirTransportScheduleTest.kt`
- `app/src/test/java/com/mirror/app/phone/Audit218SecurityTest.kt`
- `app/src/test/java/com/mirror/app/phone/CameraEpochWatchdogTest.kt`
- `app/src/test/java/com/mirror/app/phone/PairingGenerationTest.kt`
- `app/src/test/java/com/mirror/app/phone/ServerSecurityTest.kt`
- `app/src/test/java/com/mirror/app/phone/WakeAccountingTest.kt`
- `app/src/test/java/com/mirror/app/phone/roster/CrewImportPolicyTest.kt`
- `app/src/test/java/com/mirror/app/phone/roster/EcrewExportWindowTest.kt`
- `app/src/test/java/com/mirror/app/phone/roster/LocalCleanup218Test.kt`
- `app/src/test/java/com/mirror/app/phone/roster/RosterMergeTest.kt`
- `app/src/test/java/com/mirror/app/phone/roster/RosterSpilloverTest.kt`
- `app/src/test/java/com/mirror/app/phone/roster/RosterTransaction218Test.kt`
- `app/src/test/java/com/mirror/app/phone/roster/StructuredRosterTest.kt`
- `app/src/test/java/com/mirror/app/tv/ViewerPairing218Test.kt`
- `app/src/testFull/java/com/mirror/app/phone/CameraTls218Test.kt`
- `docs/audit/action-pins-2026-11-11.json`
- `docs/audit/autoroster-2026-11-11.md`
- `docs/audit/device-validation-218.md`
- `docs/audit/remediation-218.md`
- `docs/audit/security-decisions-218.md`
- `docs/audit/validation-218.md`
- `gradle/wrapper/gradle-wrapper.properties`
- `scripts/check-audit-metadata.py`
- `scripts/check-ecrew-extension.cjs`
- `scripts/phone-protected-baseline.json`
- `version.properties`

## Retained audit closure matrix

| ID | Status | Evidence / remaining gate |
|---|---|---|
| SEC-01 | ACCEPTED DEFERRED | Owner exception; signing configuration and identity untouched. |
| SEC-02 | FIXED | PairingAuthority.pair consumes code under its monitor after persistence. Audit218SecurityTest concurrent one-use/invalid identity; PairingAuthorityTest expiry/lockout; ServerSecurityTest HTTP replay/regeneration. |
| SEC-03 | NEEDS DEVICE TEST | AirRemoteTv uses PairingGeneration to guard persistent host/pin commit, closes/cancels pairing on stop and ignores stale completion/status. PairingGenerationTest covers cancelled/overlapping/failed writes; real late TLS/code timeout/reconnect integration outstanding. |
| SEC-04 | BLOCKED | HTTP transport exposure remains. Independently testable LocalCameraTls pins exact certificates, SourceAuth refuses pinned HTTP downgrade, and MjpegServer accepts an explicitly supplied TLS server factory (CameraTls218Test). Production first-trust/identity lifecycle and third-party rollout are not enabled; see security-decisions-218.md. Do not call HTTP secure. |
| SEC-05 | FIXED | PairingAuthority requires mirror username, bounded parser, PBKDF2-HMAC-SHA256 210000 iterations, constant-time hash compare, five-failure one-second cooldown; successful legacy authentication upgrades hash. Audit218SecurityTest and ServerSecurityTest. TLS still required in transit; hardware KDF cost unmeasured. |
| SEC-06 | FIXED (source); CI/admin pending | build.yml verifies with read-only contents permissions; publish needs build and alone gets contents:write; official repository tag refs pinned to immutable SHAs; downloaded APK integrity rechecked before publication; metadata invariants pass. No workflow triggered and no branch rules applied. |
| SEC-07 | FIXED | Gradle 9.8.0 official .sha256 fetched over verified HTTPS, bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c pinned in wrapper properties. Offline cached build does not test a fresh wrapper download. |
| AIR-01 | NEEDS DEVICE TEST | Geometry is explicitly tagged in AirVisionEngine/Observation/Ownership; 1 denotes passed geometry predicates, not model probability. Claim consistency increases with threshold; classifier probability remains gated. AirConfidence218Test covers all slider values, no palm, ties and dropout; Air216GeometryTest covers geometry. Actual model/lighting false-positive rates unmeasured. |
| AIR-02 | FIXED | AirKeyQueue independently rejects >1000 ms observation age, >400 ms queue age and reversed clocks, with distinct reasons. Audit218SecurityTest deadline boundaries and AirDiagnostics218Test visible distinct drop reasons; existing Air215IntegrationTest covers discard/reconnect. Transport race stress remains device checklist. |
| AIR-03 | FIXED | AirJoystick.reset clears shoulder/repeat/activity/reason state. AirConfidence218Test fresh center and shoulder reacquisition; existing navigation/recovery suites retained. |
| AIR-04 | NEEDS DEVICE TEST | AirTransportSchedule raises connected idle scheduling from 10 to 80 ms; send wakes scheduled idle work immediately; active/negotiating/backoff remain bounded. AirTransportScheduleTest. No CPU/battery/latency improvement measured. |
| CAM-01 | NEEDS DEVICE TEST | CameraEpochWatchdog resets on stop/start; 20s first-frame grace vs 8s frame-stall detection, existing 7s confirmation/30s retry bound retained. CameraEpochWatchdogTest and AirDiagnostics218Test reject old-epoch callbacks. CameraX rebind/lock/Wi-Fi tests require devices. |
| CAM-02 | NEEDS DEVICE TEST | Existing wake acquisition, demand release and onDestroy release traced; WakeAccounting instruments held duration/acquisitions in copied diagnostics with WakeAccountingTest. Air-enabled demand can hold CPU even with camera stopped; no speculative energy change applied. Instrumented matched device energy/wake-lock measurements outstanding. |
| TV-01 | NEEDS DEVICE TEST | ViewerPairing.pair returns token without persistence; TvActivity commits only current screen/address/generation on UI thread. PairingGenerationTest and ViewerPairing218Test (real HTTP pairing without persistence, stale-screen rejection and failed preference-commit restoration); back/cancel invalidates generation. Real activity/navigation race integration outstanding. |
| ROSTER-01 | NEEDS DEVICE TEST | RosterStore parses before RosterTransaction stages backups/PDF/JSON and commits; next-load recovery rolls back interrupted replacement. RosterTransaction218Test injects IOException and process interruption at ten boundaries and repeated failures. Low-storage filesystem/power-loss fsync semantics and alarm delivery require device checks. |
| ROSTER-02 | NEEDS DEVICE TEST | EcrewFirefox.clearLocal returns Boolean, verifies credential/profile deletion and reports Gecko failure. Logout preserves failure state; diagnostics has session-only retry. LocalCleanup218Test covers credential failure, browser exception, duplicate callbacks and retry; actual Gecko/Keystore failure injection outstanding; no false cleanup-complete message on failure. |
| ROSTER-03 | NEEDS DEVICE TEST | CrewImportPolicy blocks background and unconfirmed cross-crew imports before persistence; RosterImport UI asks explicit switch confirmation tied to existing crew. CrewImportPolicyTest covers first/same/different crew, background and stale confirmation. UI/notifications/alarm integration outstanding. |
| ROSTER-04 | FIXED | AcceptedPdf SHA-256 bounded to 20MiB, marks only successful accepts; integrated in WebView and Firefox. RosterTransaction218Test covers failed retry, duplicate suppression and equal contentHashCode/different bytes. |
| LAN-01 | NEEDS DEVICE TEST | MjpegServer absolute 2s incomplete-request deadline plus 1.5s idle read timeout; 100ms watchdog, bounded four sockets/workers and two streams retained. ServerSecurityTest slow-drip saturation regression; MjpegServerTest two viewers plus controls. Continuous hostile-LAN fairness/slow-write device stress outstanding. |
| PRIV-01 | NEEDS DEVICE TEST | Inventory and count/size limits documented; raw ZIP labeled unencrypted; RawCaptureCleanup provides optional deletion without touching offline roster, alarms or session (LocalCleanup218Test). Raw storage metadata logs omit keys/endpoints/values. Private raw captures remain unencrypted to preserve export functionality; no claim of public exposure. Gecko/export/restore integration remains a device gate. |
| API-01 | NEEDS DEVICE TEST | ClockSettingsActivity exported=false; Dream metadata points to narrow DreamClockSettingsActivity showing Clock style only, with no arbitrary category intent. check-audit-metadata.py validates manifest/metadata; system Dream navigation requires device check. |
| DIAG-01 | FIXED | AirRemote performance reports configured Compatibility maximum, actual Pose maximum 2, detected count separately; existing crop/source/timing/FPS/thermal/delegate fields retained. AirDiagnostics218Test verifies geometry is not displayed as probability; actual device output checklist. |
| DOC-01 | FIXED | README version-independent heading, stable vs candidate URLs and correct candidate verification wording; version.properties 1.12.13/218 and changelog updated; workflow title made version-independent without publication behavior change. check-audit-metadata.py. |
| NEW-01 | FIXED | Firefox had the same premature 32-bit PDF dedup as WebView (EcrewFirefox capturedPdf/parsePendingPdf). Both now use AcceptedPdf; same failed document can be retried. Same regression as ROSTER-04. |
| NEW-02 | NEEDS DEVICE TEST | RosterStore could report failed import after durable commit if a notification/alarm refresh threw. It now tracks committed state and logs post-import refresh failure separately. Device side-effect retry remains an integration gate; no rollback of committed data is claimed. |
| NEW-03 | NEEDS DEVICE TEST | Explicit Clear data ignored roster deletion/preference failures, and the compatibility WebView cleanup ignored cookie callback results. RosterStore.clear now aggregates failures; EcrewStorage verifies cookies/origins with a bounded completion timeout; UI reports partial cleanup and offers retry. LocalCleanup218Test verifies false/exception aggregation and continued cleanup. Native WebView deletion checks remain a device gate. |
