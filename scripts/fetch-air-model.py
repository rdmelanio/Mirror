#!/usr/bin/env python3
"""Fetch Google's versioned official Gesture Recognizer bundle, preserving TLS verification."""
import pathlib
import urllib.request
import zipfile
import io
root = pathlib.Path(__file__).resolve().parents[1]
url = 'https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task'
data = urllib.request.urlopen(url, timeout=90).read(32 * 1024 * 1024 + 1)
assert 1_000_000 < len(data) < 32 * 1024 * 1024
with zipfile.ZipFile(io.BytesIO(data)) as bundle:
    assert {'hand_landmarker.task', 'hand_gesture_recognizer.task'} <= set(bundle.namelist())
path = root / 'app/src/full/assets/gesture_recognizer.task'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_bytes(data)
print('Official MediaPipe model saved:', path, len(data), 'bytes')
