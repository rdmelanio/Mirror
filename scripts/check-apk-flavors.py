#!/usr/bin/env python3
"""Verify the actual APKs, including dex isolation and native ABIs, before publication."""
import pathlib
import sys
import struct
import zipfile

lite, full = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(lite) as apk:
    assert not any(name.endswith('.so') for name in apk.namelist()), 'lite contains native code'
    assert not any(name.startswith('assets/ecrew/') for name in apk.namelist()), 'lite contains Firefox extension'
    for name in apk.namelist():
        if name.endswith('.dex'):
            assert b'Lorg/mozilla/geckoview/' not in apk.read(name), 'lite references Gecko classes'
with zipfile.ZipFile(full) as apk:
    libraries = [name for name in apk.namelist() if name.endswith('.so')]
    assert libraries and all(name.startswith('lib/arm64-v8a/') for name in libraries), libraries
    assert 'lib/arm64-v8a/libxul.so' in libraries, 'full missing Gecko engine'
    assert 'assets/ecrew/manifest.json' in apk.namelist(), 'full missing built-in extension'
    assert 'assets/ecrew/background.js' in apk.namelist(), 'full missing network capture'
    assert 'assets/ecrew/hooks.js' not in apk.namelist(), 'full contains page hooks'
    assert any(b'Lorg/mozilla/geckoview/GeckoSession;' in apk.read(name) for name in apk.namelist() if name.endswith('.dex'))
def postscript_names(data):
    if data[:4] != b'\x00\x01\x00\x00':
        return set()
    count = struct.unpack_from('>H', data, 4)[0]
    for index in range(count):
        tag, _, offset, length = struct.unpack_from('>4sIII', data, 12 + index * 16)
        if tag != b'name':
            continue
        _, records, storage = struct.unpack_from('>HHH', data, offset)
        result = set()
        for record in range(records):
            platform, encoding, language, name_id, size, start = struct.unpack_from('>HHHHHH', data, offset + 6 + record * 12)
            if name_id == 6:
                raw = data[offset + storage + start:offset + storage + start + size]
                result.add(raw.decode('utf-16-be' if platform in (0, 3) else 'mac_roman'))
        return result
    return set()

# AAPT may shorten resource paths. Inspect font identity and license bytes instead.
for path in (lite, full):
    with zipfile.ZipFile(path) as apk:
        fonts = set()
        licensed = False
        for entry in apk.infolist():
            if entry.filename.endswith('.ttf'):
                fonts.update(postscript_names(apk.read(entry)))
            elif entry.file_size < 16_000:
                data = apk.read(entry)
                licensed = licensed or (b'SIL OPEN FONT LICENSE' in data and b'rsms/inter' in data)
        required = {'Inter-Regular', 'Inter-Medium', 'Inter-SemiBold'}
        assert required <= fonts, f'{path}: missing bundled menu fonts {required - fonts}; found {fonts}'
        assert licensed, f'{path}: bundled Inter SIL OFL missing'
        print(path, 'verified Inter Regular/Medium/SemiBold and SIL OFL')
print('Verified: lite universal without Gecko; full arm64 with Gecko and extension')

