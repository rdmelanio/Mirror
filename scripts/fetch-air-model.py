#!/usr/bin/env python3
"""Fetch pinned official Google model bundles with TLS and SHA-256 verification."""
import hashlib, json, pathlib, urllib.request, zipfile, io
root = pathlib.Path(__file__).resolve().parents[1]
for model in json.loads((root / 'docs/air-models.json').read_text()):
    data = urllib.request.urlopen(model['url'], timeout=90).read(32 * 1024 * 1024 + 1)
    assert len(data) == model['bytes'] and hashlib.sha256(data).hexdigest() == model['sha256'], model['file']
    with zipfile.ZipFile(io.BytesIO(data)) as bundle:
        assert bundle.testzip() is None
    path = root / 'app/src/full/assets' / model['file']
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    print('Verified official model:', model['file'], model['sha256'])
