#!/usr/bin/env python3
"""Verify the actual APKs, including dex isolation and native ABIs, before publication."""
import pathlib
import sys
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
for path in (lite, full):
    with zipfile.ZipFile(path) as apk:
        for name in ('res/font/inter_regular.ttf', 'res/font/inter_medium.ttf', 'res/font/inter_semibold.ttf', 'res/raw/inter_ofl.txt'):
            assert name in apk.namelist(), f'{path}: missing bundled menu font/license {name}'
        assert b'SIL OPEN FONT LICENSE' in apk.read('res/raw/inter_ofl.txt')
print('Verified: lite universal without Gecko; full arm64 with Gecko and extension')

