# Mirror 1.12.11 (216) — pose-guided candidate

This candidate targets the OnePlus 6T front camera with Mirror Clock visible. Compilation and deterministic tests are software evidence only. The native instrumentation smoke test is prepared for arm64 Android but cannot run on this cloud host. No OnePlus 6T FPS, thermal, locked-screen, or 12-foot recognition measurements were available during cloud development.

## Architecture

`AirVisionEngine` has a preferred `AirPoseEngine` and a preserved `AirGestureEngine`. Legacy mode selects the compatibility engine. Advanced settings allow explicit selection and one or two pose subjects. Both remain on the existing single inference worker and single owned frame lease.

The pose engine uses MediaPipe Tasks Vision 0.10.32 with Google's float16 version-1 Pose Landmarker Lite and Hand Landmarker task bundles. `air-models.json` records the exact official storage.googleapis.com URLs, byte counts and SHA-256 digests. The fetch script verifies TLS, digest, size and ZIP integrity; the full build verifies digests again. The new models are fetched before CI builds and packaged only in full assets. Lite checks reject all task files and MediaPipe/native code.

Pose updates use a 256-pixel source image, run less often than hand processing, and slow further under thermal pressure. Upper-body shoulders, elbows and wrists predict expanded hand search regions. Missing lower-body joints do not reject a seated upper body. Fresh hand observations refine crops; owner crops are smoothed and expand during misses. Periodic wide discovery and household bursts retain opportunities for claims. When pose is unavailable, recent hand crops and full-frame hand scans remain available.

Each crop samples the original copied NV21 frame through `AirPixels.convert`; it never samples a previously downscaled full-frame RGB image. Rotation is resolved during source sampling, then crop landmarks are remapped into upright full-frame coordinates before the existing mirror correction and navigation boundary. CameraX requests up to 1920×1080 analysis with lower-resolution fallback and reports actual delivery size. The exact-capacity aligned direct-RGB path and Bitmap fallback remain shared by both engines.

Hand Landmarker uses IMAGE mode because changing crops and different people must not share VIDEO tracker state. The compatibility recognizer also uses IMAGE mode for safe changing crops. Geometry supplies pointing, thumbs, victory, palm, fist and existing optional shortcut poses. Geometry scores represent rule evidence, not calibrated model probabilities. Temporal acceptance uses confidence-weighted evidence, bounded unknown-frame bridging, deliberate Home dwell and command latches. Legacy native categories remain available through the compatibility engine; a second complete hand model does not run on every pose-engine frame.

GPU is optional and remains unmeasured on Snapdragon 845. Initialization/runtime failure falls back to CPU; failure of the pose engine falls back to the compatibility recognizer. Model creation and cleanup occur on the owning worker. No performance target is represented as measured success.

## Recovery and household behavior

An active session survives missing hands and temporary transport failure. Recovery sampling remains elevated for up to 12 seconds rather than 2.4 seconds. Old rival positions cannot indefinitely veto a fresh single-controller scene: after a bounded competition-free period and fresh stable observations, plausible repositioning can recover without an open palm. Current competing people or a recent known different body association block that shortcut; body associations also expire rather than becoming permanent identity records. These are temporary spatial associations, never biometric identity.

The joystick drops old movement/repeat state on interruption, suppresses commands while fresh observations establish a neutral reference, and then accepts new excursions. Stable shoulder coordinates allow torso-relative movement; losing or regaining that reference triggers safe recentering. Direction mappings remain unchanged.

Initial and deliberate takeover claims retain the one-second, multi-observation household policy. Ordinary recovery does not emit TRANSFERRED. The one-minute default inactivity timeout and configured 30/60/120/300-second choices remain. Mere visibility does not keep a session alive. Tracking loss clears queued commands; reconnection still clears transport queues.

## Feedback, diagnostics and camera

The existing authenticated feedback protocol, TV rendering, 4-second green activity timer, bounded 10-second amber timer, 2-second expiration notice and 1.5-second transfer notice remain. Existing protocol/overlay regression tests cover heartbeat repetition and mixed-version fallback. No new transport or permanent overlay was introduced.

Copy Diagnostics includes the active engine/delegate, actual source size, crop pixel sizes, body/wrist/hand counts, recognition stage, recovery duration, delivery/effective FPS, thermal state, drops and bounded p50/p95 conversion/pose/hand/decision latency. Its ring buffer holds no images, landmarks, credentials or identity records.

The camera service retains its foreground-camera type and partial wake lock. A frame watchdog distinguishes no frames while service remains active, reports screen state, and bounds rebind attempts. Android/OxygenOS may still prevent camera operation while locked; the visible Clock mode remains the primary supported operating mode. No privacy restriction is bypassed.

## Validation and hardware work remaining

Regression coverage includes stale ambiguity, displaced single-controller recovery, known competing bodies, neutral recentering without phantom movement, geometry/handedness/directions, crop remapping, seated partial-body regions, uncertain-wrist expansion and scheduling. Existing session, household transfer, transport, direct-buffer, overlay, Clock, roster, alerts and flavor tests remain in the validation gates.

OnePlus validation remains necessary for native model execution, actual 1080p availability, 4/8/12-foot gesture precision, body association through overlap/occlusion, false claims, seated wrist visibility, effective hand FPS, p50/p95 latency, CPU versus GPU, thermal/battery behavior, Clock responsiveness, TV execution and OxygenOS lock-screen behavior. The existing opt-in on-device calibration benchmark and Copy Diagnostics are available; no retest of v1.12.10 is required.
