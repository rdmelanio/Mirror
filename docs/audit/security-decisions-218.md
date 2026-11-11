# Security boundaries and outstanding decisions

## Signing (SEC-01)
Accepted/deferred by owner. No key, alias, password, certificate, application ID or signing pipeline change is permitted in this remediation. Do not copy signing material into this report.

## Camera transport (SEC-04): still exposed on a trusted LAN
Current camera viewer traffic on 8080 is HTTP, including video, bearer authentication and optional browser Basic authentication. Hashing tokens/passwords at rest does not protect them in transit. Google TV Remote v2 TLS on 6466/6467 is a separate protocol and does not protect camera HTTP. No demonstrated interception is claimed.

A safe migration requires a persistent per-install camera TLS identity, discovery advertising a separate TLS endpoint, and an authenticated first-pair trust ceremony binding that identity to the phone-visible code. Never auto-trust an unknown certificate. Existing tokens must continue working against their pinned camera, and an identity mismatch must fail closed with explicit re-pair instructions. Keep existing HTTP sources available as explicitly labeled legacy LAN sources; third-party IP Webcam certificates require their own explicit trust policy. Do not send bearer/Basic credentials across redirects. Real TV certificate/provider compatibility, first-pair authentication and recovery after device restore need integration validation before enabling a new default. LocalCameraTls and an injected MjpegServer TLS factory provide independently tested support: exact certificate pins, validity checks, mismatch rejection, no redirects and no HTTP downgrade when pinned (CameraTls218Test). No production pin enrollment or camera TLS identity is silently created; existing connections continue using legacy HTTP. Production secure camera transport is BLOCKED pending enrollment/identity integration and those checks; the existing HTTP path is not declared secure.

## Release process (SEC-06)
No workflow publication behavior, branch protection, visibility, tags, assets or releases were changed. The local candidate title was made version-independent to prevent documentation drift; permissions and publication gates remain unchanged. Current build.yml still has broad contents:write and mutable action tags. Owner steps (not applied):

1. Enable a main ruleset requiring pull requests and successful verify jobs for both flavors, lint, model/flavor and APK integrity checks; prohibit force pushes/deletion, including administrators unless an emergency bypass is deliberately assigned.
2. Approve splitting verify (contents:read) and publish (contents:write) jobs. Publish must depend on every verify gate, use artifacts from the same commit, preserve build-N numbering and candidate/stable semantics, and fail if tag or asset already exists.
3. Pin checkout/setup-java/setup-android/setup-gradle/upload-artifact/release actions to reviewed full commit SHAs with version comments; verify upstream release provenance before changing pins.
4. Require owner approval on a release environment before publication. Preserve the existing signing certificate checks and public names mirror.apk / mirror-phone.apk.

SEC-06 remains BLOCKED by the owner's explicit prohibition on unapproved release-behavior changes. No administrative protection is claimed applied.

## Private eCrew data (PRIV-01)
Private no-backup storage is not public storage. Inventory: roster/latest.pdf, previous.pdf, roster.json; roster/capture.log (bounded 1000 lines); roster/schedule-data/localStorage.json (5 MiB input bound), CrewSchedule.html (1 MiB), hashed endpoint JSON (20 endpoints, <2 MiB input each); ecrew-gecko-profile (cookies, localStorage, cached session data); ecrew-login.enc (Android Keystore AES-GCM, exact HTTPS eCrew site); ecrew-gecko.yaml (runtime configuration). Deliberate ZIP export roster/captured-schedule-data.zip is unencrypted and shared with a temporary FileProvider grant. Cache roster-logs contains deliberate diagnostic exports. Offline PDFs/JSON are kept until explicit clear; raw captures are bounded by count/size but have no age expiry. The login file is excluded from diagnostics and exports.

Local session clear must not erase offline roster or alarms. Explicit Clear data intentionally deletes roster data; browser-clear failure must remain visible and retryable. The optional Delete raw schedule captures action removes only raw schedule files and their ZIP; LocalCleanup218Test verifies preservation of offline data. Metadata logging omits raw keys/endpoints/values. Raw files remain private and unencrypted; Gecko restore/export device checks remain outstanding; do not claim all privacy risks closed. UI now explicitly labels the raw ZIP unencrypted.
