# Changelog

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
