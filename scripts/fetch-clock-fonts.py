#!/usr/bin/env python3
"""Bundle official, pinned B612 fonts. Missing or modified fonts fail the build."""
import hashlib
import pathlib
import urllib.request

COMMIT = '48ac6ba67ecab8123e8e36d6aa05367db0c7b638'
FONTS = {
    'B612-Regular.ttf': ('b612_regular.ttf', '05748970fad8c582d5467880cc1247f052e9acb9'),
    'B612-Bold.ttf': ('b612_bold.ttf', '0f9ed6c732f4d4ed2f1f06369fd7234ed2972397'),
    'B612Mono-Regular.ttf': ('b612_mono.ttf', '32bbdeee5fe56a706c9ed7655e1ac0234eefa0d5'),
}
root = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/res/font'
root.mkdir(parents=True, exist_ok=True)
for source, (name, expected) in FONTS.items():
    target = root / name
    data = target.read_bytes() if target.exists() else urllib.request.urlopen(
        f'https://raw.githubusercontent.com/polarsys/b612/{COMMIT}/fonts/ttf/{source}', timeout=60).read()
    digest = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    if digest != expected or data[:4] != b'\0\1\0\0':
        raise RuntimeError(f'Missing, invalid or changed official font: {source}')
    target.write_bytes(data)
    print(f'Verified official font: {name} ({len(data)} bytes)')
