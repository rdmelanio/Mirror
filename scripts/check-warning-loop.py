"""Check the committed mono PCM loop before packaging (stdlib only)."""
import array
import hashlib
import math
import pathlib
import sys

path = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/airbus_master_warning.pcm'
raw = path.read_bytes()
assert hashlib.sha256(raw).hexdigest() == 'b368ca653732edd79d841bc25185a2c1217d8a4ab7ea2241a42ab4bb761b4e8c', 'Warning PCM changed or corrupted'
assert len(raw) == 44075 * 2, 'Expected two complete warning cycles'
x = array.array('h'); x.frombytes(raw)
if sys.byteorder != 'little': x.byteswap()
steps = sorted(abs(b-a) for a, b in zip(x, x[1:]))
assert abs(x[0]-x[-1]) <= steps[int(len(steps)*.99)], 'Audible discontinuity at loop boundary'
seam = list(x[-441:])+list(x[:441])
assert math.sqrt(sum(v*v for v in seam)/len(seam)) > 1000, 'Quiet gap at loop boundary'
assert max(map(abs, x)) < 32767, 'Clipped warning samples'
print('Verified seamless warning PCM: 44075 frames, 44100 Hz, no quiet wrap or clipping')
