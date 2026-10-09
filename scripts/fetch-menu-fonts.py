#!/usr/bin/env python3
"""Bundle the official Inter 4.1 static TTFs and validate the SIL OFL release."""
import io
import pathlib
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
FONT_DIR = ROOT / 'app/src/main/res/font'
FONTS = {'Inter-Regular.ttf': 'inter_regular.ttf', 'Inter-Medium.ttf': 'inter_medium.ttf', 'Inter-SemiBold.ttf': 'inter_semibold.ttf'}
URL = 'https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip'
data = urllib.request.urlopen(URL, timeout=90).read(64 * 1024 * 1024 + 1)
if len(data) > 64 * 1024 * 1024:
    raise RuntimeError('Inter release exceeds size limit')
with zipfile.ZipFile(io.BytesIO(data)) as archive:
    license_files = [name for name in archive.namelist() if pathlib.PurePosixPath(name).name in ('LICENSE.txt', 'OFL.txt')]
    license_text = next((archive.read(name).decode('utf8') for name in license_files if 'SIL OPEN FONT LICENSE' in archive.read(name).decode('utf8')), None)
    if license_text is None:
        raise RuntimeError('Official Inter SIL OFL license missing')
    (ROOT / 'licenses/Inter-OFL.txt').write_text(license_text)
    (ROOT / 'app/src/main/res/raw/inter_ofl.txt').write_text(license_text)
    FONT_DIR.mkdir(parents=True, exist_ok=True)
    for source, target in FONTS.items():
        matches = [name for name in archive.namelist() if pathlib.PurePosixPath(name).name == source]
        if len(matches) != 1:
            raise RuntimeError(f'Official static font not found uniquely: {source}')
        font = archive.read(matches[0])
        if font[:4] != b'\x00\x01\x00\x00' or len(font) > 4 * 1024 * 1024:
            raise RuntimeError(f'Invalid Inter font: {source}')
        (FONT_DIR / target).write_bytes(font)
        print(f'Bundled Inter 4.1: {target} ({len(font)} bytes)')
