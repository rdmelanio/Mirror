#!/usr/bin/env python3
import pathlib
import re

props = dict(line.split('=', 1) for line in pathlib.Path('version.properties').read_text().splitlines() if '=' in line)
certs = []
for apk in ['mirror.apk', 'mirror-phone.apk']:
    info = pathlib.Path(apk + '-info.txt').read_text()
    for key, prop in [('versionName', 'mirrorVersionName'), ('versionCode', 'mirrorVersionCode')]:
        assert re.search(rf"{key}='([^']+)'", info).group(1) == props[prop], (apk, key)
    assert re.search(r"package: name='([^']+)'", info).group(1) == 'com.mirror.app'
    certs.append(re.search(r'Signer #1 certificate SHA-256 digest: (.+)', pathlib.Path(apk + '-signature.txt').read_text()).group(1))
assert certs[0] == certs[1], 'flavors must retain identical signing'
print('Both APKs verified:', props['mirrorVersionName'], props['mirrorVersionCode'], 'same applicationId and certificate')
