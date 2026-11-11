# Validation and release integrity — 1.12.13 / 218

Local source HEAD: `368f0b9ea96221c3dee56e45add4ef1dde26b9e2` plus the supplied uncommitted patches. Remote main was fetched before edits and rechecked on 2026-11-11: unchanged from the audit baseline. No push, tag, release upload, asset overwrite or repository administration occurred.

## Authoritative final run

Executed from /workspace/Mirror using the cached JDK/Android SDK/Gradle environment:

```sh
JAVA_HOME=/workspace/.cache/jdk ANDROID_HOME=/workspace/.cache/android-sdk GRADLE_USER_HOME=/workspace/.cache/gradle ./gradlew --offline --max-workers=4 --console=plain :app:testLiteDebugUnitTest :app:testFullDebugUnitTest :app:lintLiteRelease :app:lintFullRelease :app:assembleLiteRelease :app:assembleFullRelease
```

Result: BUILD SUCCESSFUL, 3m 56s. Log: `/workspace/work/audit218-release-gates.log`.

| Gate | Actual result |
|---|---|
| Lite JVM | 341 tests, 0 failures, 0 errors, 0 skipped |
| Full JVM | 351 tests, 0 failures, 0 errors, 0 skipped |
| Lite lint | 0 errors, 229 warnings |
| Full lint | 0 errors, 261 warnings |
| Lite/full release assembly | Both succeeded |
| eCrew extension | 36 passed, 0 failed/skipped |
| Theme/rendering guard | Passed; exact baseline hashes updated only for intentionally repaired CameraService, SourceAuth and TvActivity |
| Warning audio | Passed: seamless 44075-frame / 44100-Hz PCM |
| Metadata/Dream boundary | check-audit-metadata.py passed |
| Official models/fonts | fetch-air-model.py, fetch-clock-fonts.py, fetch-menu-fonts.py verified/downloaded official assets; final APK font checks passed |
| Flavor isolation | Lite universal, no Gecko/MediaPipe/native model payload; full arm64 with Gecko/extension/models |
| APK identity | Both com.mirror.app, 1.12.13 / 218 |
| APK signatures | Both verified; identical existing release certificate |
| Whitespace | git diff --check passed |
| Signing source | app/build.gradle.kts, gradle.properties and keystore unchanged |

No test was disabled, skipped or weakened to obtain these results. The HTTP brute-force test now regenerates a code after its successful one-use assertion, reflecting the repaired semantics. Earlier intermediate lint report quick-fix rendering exceptions came from changing source during that earlier build; the authoritative run above used frozen source and contains no such exceptions. Lint warnings remain; zero lint errors does not mean zero warnings.

Additional commands executed:

```sh
node --test scripts/check-ecrew-extension.cjs
python3 scripts/check-phone-theme.py
python3 scripts/check-warning-loop.py
python3 scripts/check-audit-metadata.py
python3 scripts/fetch-air-model.py
python3 scripts/fetch-clock-fonts.py
python3 scripts/fetch-menu-fonts.py
python3 scripts/check-apk-flavors.py /workspace/work/mirror-1.12.13/mirror.apk /workspace/work/mirror-1.12.13/mirror-phone.apk
```

APKs were copied to a new local directory, retaining public filenames. For each APK, Android build-tools/36.0.0/apksigner `verify --verbose --print-certs` and `aapt dump badging` produced the corresponding signature/info files. `python3 /workspace/Mirror/scripts/check-release-apks.py` was run from `/workspace/work/mirror-1.12.13`, which contains the APKs, inspection files and version.properties. It passed application ID, version and existing certificate pin checks. No artifact was published.

| Local artifact | SHA-256 |
|---|---|
| mirror.apk | de1f5dd8e67343ca09baf0513a67acfa477924a2dc9c8bf0eced13b162af06ef |
| mirror-phone.apk | e6291512ced90f56e71b2fcab372e5a3d025b73d7501cfbfb7d561eae54a39aa |

Existing release certificate SHA-256: `a33ac25448fd6b437c2b87456aef3c859d00775060d55f452482746142cc8a75`. This is a public certificate fingerprint, not signing-key material.

## Release gate and remaining work

This is an unpublished remediation candidate, **not cleared for release**. SEC-01 remains owner-accepted/deferred. SEC-04 has tested pinned-TLS support but still requires authenticated certificate enrollment, persistent camera identity integration and compatible rollout; existing HTTP remains unencrypted and explicitly labeled legacy. SEC-06 publishing/administrative hardening requires owner approval and has not been applied. No production secure default or CI permission change is claimed.

`adb devices` returned no attached devices. CameraX, Gecko/Keystore failure paths, actual TV pairing/navigation races, wake/lock behavior, wrong-crew confirmation/notifications, power-loss filesystem behavior, alarms, installation-over-existing data, low-RAM playback and battery/thermal/latency require the real-device plan. No distance, battery or model-performance improvement is claimed measured. Follow docs/audit/device-validation-218.md; any data loss, certificate mismatch, stale/phantom command, false cleanup success or failed automated gate is a release no-go.

## Exact changed files

- `.github/workflows/build.yml`
- `CHANGELOG.md`
- `README.md`
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
- `app/src/main/java/com/mirror/app/phone/roster/EcrewScheduleData.kt`
- `app/src/main/java/com/mirror/app/phone/roster/EcrewStorage.kt`
- `app/src/main/java/com/mirror/app/phone/roster/LocalLogoutCleanup.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RawCaptureCleanup.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RosterFetcher.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RosterStore.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RosterTransaction.kt`
- `app/src/main/java/com/mirror/app/phone/roster/RosterUi.kt`
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
- `app/src/test/java/com/mirror/app/phone/roster/LocalCleanup218Test.kt`
- `app/src/test/java/com/mirror/app/phone/roster/RosterTransaction218Test.kt`
- `app/src/test/java/com/mirror/app/tv/ViewerPairing218Test.kt`
- `app/src/testFull/java/com/mirror/app/phone/CameraTls218Test.kt`
- `docs/audit/device-validation-218.md`
- `docs/audit/remediation-218.md`
- `docs/audit/security-decisions-218.md`
- `docs/audit/validation-218.md`
- `gradle/wrapper/gradle-wrapper.properties`
- `scripts/check-audit-metadata.py`
- `scripts/phone-protected-baseline.json`
- `version.properties`
