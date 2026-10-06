# Phone-side TV launch

This feature sends only `input keyevent 224; am start -W -n com.mirror.app/.core.LauncherActivity`
to the user's paired TV. It does not alter TV code, app naming or camera/network streaming.

Android wireless debugging uses two endpoints: a temporary pairing port and a
separate, changing secure connection port. Pairing authenticates a six-digit code
with TLS-exporter-bound SPAKE2 and transfers Mirror's RSA public key. The TV GUID
returned through the encrypted exchange is retained for `_adb-tls-connect._tcp`
identity matching. Discovery accepts only that GUID (with the standard optional
`adb-` prefix and connection suffix); it never selects the first arbitrary ADB
service. A configured LAN-only IPv4 endpoint is the fallback. Adbd authorizes the
saved client certificate; self-signed TV TLS certificates may rotate, so this uses
the Android ADB trust model, rather than normal web PKIX validation or certificate
pinning. There is no legacy port-5555/plaintext fallback or arbitrary command UI.

The RSA identity is encrypted by an Android Keystore AES/GCM key in the app's
no-backup directory. Pairing codes are not saved or logged. App backup is disabled.
Forget removes local credentials; remove Mirror Clock from the TV paired-device
list too to revoke the TV's authorization. The lock screen only exposes the
already-configured fixed wake/launch action; TV setup requires the normal settings
activity and cannot unlock the phone. No device credentials appear on the clock
or in notifications.

Discovery lasts at most six seconds; socket connect is bounded to four seconds,
reads to twelve seconds, and an independent twenty-second socket-close deadline
also bounds stalls. One operation runs at a time on a separate executor. Discovery
and its short multicast lock stop on every completion; sockets/TLS streams close
on success, failure and timeout. There is no periodic scan, persistent connection,
foreground service or wake lock. The TV's own enabled debugging/standby services
can still consume power independently of this app.

## Open-source components and replacement

Sources copied into `app/src/main/java/io/github/muntashirakon`:

- SPAKE2-Java 2.2.1, commit `7615ddd680b990e14513ebb66eac4cb0dbf82464`:
  <https://github.com/MuntashirAkon/spake2-java>. All twelve pure Java sources from
  `java/src/main/java` are unmodified, available under LGPL-3.0. No JNI sources or
  binaries are used. `licenses/spake2-LGPL-3.0.txt` and `licenses/GPL-3.0.txt`.
- LibADB Android 3.1.1, commit `c849886ebc6d48e7b46d967e78a6bb65c90c3b74`:
  <https://github.com/MuntashirAkon/libadb-android>. AndroidPubkey, PairingAuthCtx,
  StringCompat and ByteArrayNoThrowOutputStream. Apache-2.0 option of the upstream
  dual license. Only class visibility is widened for the first two; the byte-array
  stream is reduced to the constructor/overrides used by AndroidPubkey.
  `licenses/libadb-Apache-2.0.txt`.
- Bouncy Castle 1.81, Maven Central bcprov/bcpkix/bctls-jdk18on, MIT-style license:
  <https://github.com/bcgit/bc-java/tree/r1rv81>. `licenses/bouncycastle.html`.

Full notices/licenses are bundled in About → TV launch · open-source licenses.
To replace/rebuild the LGPL portion, edit/replace the provided Java sources, fetch
the pinned B612 fonts with `python3 scripts/fetch-clock-fonts.py`, then run
`./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease` with the
Android SDK and a signing key configured as documented for this repository.
The CI release remains one APK with no `.so` libraries. No global Android crypto
provider is installed or modified. The added TLS cryptography only runs on a tap
or explicit settings pairing/test action; the camera path does not call it.

## Verification and physical-device checks

JVM tests exercise real loopback TLS 1.3 with client-certificate authentication,
exporter-bound SPAKE2/public-key exchange, fixed wake-before-launch commands,
ADB stream acknowledgments, already-running responses, plaintext rejection,
corrupt/oversized packet rejection, target identity filtering and preference
migration. CI also runs the existing camera/clock/roster/alarm tests, lint, font and
warning-loop checks, and signing/universal-APK/version verification.

The owner confirmed Bugjaeger can reconnect to their TCL C6K Android 14/V643 after
32 minutes in standby and that keyevent 224 reveals the launched Mirror app.
Mirror's own pairing must still be checked on the real phone/TV after installing,
including reconnect after a TV reboot and overnight standby. Those observations
are not replaced by loopback protocol tests.
