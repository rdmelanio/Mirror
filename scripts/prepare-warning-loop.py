"""Prepare the user-supplied master warning after decoding it to mono 44.1kHz s16le.
Usage: ffmpeg -i SOURCE.mp3 -ac 1 -ar 44100 -f s16le decoded.pcm
       python3 scripts/prepare-warning-loop.py decoded.pcm output.pcm
The steady section contains two complete measured warning cycles. Preserve its
cadence; blend only the tail with the source immediately preceding the start.
"""
import hashlib
import pathlib
import sys
import numpy as np

source = np.fromfile(sys.argv[1], dtype='<i2').astype(np.float64)
rate = 44100
anchor = 3 * rate
# Choose a positive zero crossing near the steady section's start.
crossings = np.flatnonzero((source[anchor:anchor+2000] <= 0) & (source[anchor+1:anchor+2001] > 0))
best = min(crossings, key=lambda n: abs(source[anchor+n+1]-source[anchor+n]))
start = anchor + int(best) + 1
length = 44075  # measured two-cycle period: 0.9994331066 s
loop = source[start:start+length].copy()
blend = int(rate * .020)
ramp = np.linspace(0, 1, blend)
loop[-blend:] = loop[-blend:] * (1-ramp) + source[start-blend:start] * ramp
pcm = np.rint(loop).astype('<i2')
assert abs(int(pcm[0]) - int(pcm[-1])) <= np.quantile(abs(np.diff(loop)), .99), 'Discontinuous loop boundary'
# Check there is no quiet dropout across the wrap, while retaining the natural cadence.
seam = np.concatenate((pcm[-441:], pcm[:441])).astype(float)
assert np.sqrt(np.mean(seam ** 2)) > 1000
pathlib.Path(sys.argv[2]).write_bytes(pcm.tobytes())
print({'frames': len(pcm), 'seconds': len(pcm)/rate, 'boundary_delta': int(pcm[0])-int(pcm[-1]),
       'seam_rms': round(float(np.sqrt(np.mean(seam ** 2))), 2), 'sha256': hashlib.sha256(pcm.tobytes()).hexdigest()})
