# Supplied master warning recording

The Mirror owner supplied `airbus_master_warn.mp3` and authorized its use in Mirror.
Source SHA-256: `00a62e11f9f2270775083c051c1e26bf57dd9a3492667e3e5909f314738597a2`.

The decoded recording is 5.617 seconds long at 44.1 kHz and has an approximately
two-second fade-in. A steady section contains a measured two-cycle period of
44075 frames (0.999433 seconds). The prepared mono signed 16-bit little-endian
PCM preserves this period, chooses a source zero crossing, and blends the last
20 ms with the source immediately preceding the start. This removes the fade-in
and smooths the cyclic splice without adding a silent tail or changing cadence.

Preparation: decode the supplied source with ffmpeg as described in
`scripts/prepare-warning-loop.py`, then run that script (requires NumPy).
`scripts/check-warning-loop.py` verifies the committed asset's hash, frame count,
non-silent wrap, boundary step and absence of clipping before every Actions build.

Asset SHA-256: `b368ca653732edd79d841bc25185a2c1217d8a4ab7ea2241a42ab4bb761b4e8c`.

The default warning uses AudioTrack MODE_STATIC hardware buffer loop points,
not MediaPlayer restart callbacks. The service's bounded playback timer and Stop
release the track, audio focus and playback wake lock. Custom files retain the
existing player path; users can switch to the prepared supplied recording in
Departure alarms. The source recording was provided by the owner; it is not an
original recording made by this project.
