#!/usr/bin/env python3
"""Palette/accessibility and source-isolation checks for phone menus."""
import hashlib
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
def read(path):
    return (ROOT / path).read_text()
phone = 'app/src/main/java/com/mirror/app/phone/'
resources = ET.fromstring(read('app/src/main/res/values/phone_theme.xml'))
colors = {c.attrib['name']: c.text for c in resources.findall('color')}
expected = {'background_start': '#0B1220', 'background_end': '#111A2E', 'surface': '#D9172238', 'border': '#14FFFFFF', 'primary': '#E8EEF8', 'secondary': '#9AA8BF', 'divider': '#12FFFFFF', 'accent': '#38BDF8', 'pressed': '#0EA5E9', 'disabled': '#5538BDF8', 'on_accent': '#04111F', 'success': '#34D399', 'caution': '#FFB000', 'error': '#F87171'}
for name, value in expected.items():
    assert colors['glass_' + name].upper() == value, name

def rgb(value):
    return tuple(int(value[i:i + 2], 16) / 255 for i in (1, 3, 5))
def luminance(color):
    channel = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in color]
    return sum(v * w for v, w in zip(channel, (.2126, .7152, .0722)))
def contrast(a, b):
    light, dark = sorted((luminance(a), luminance(b)), reverse=True)
    return (light + .05) / (dark + .05)
for background in ('background_start', 'background_end'):
    bg = rgb(expected[background]); surface = tuple(.85 * a + .15 * b for a, b in zip(rgb('#172238'), bg))
    for foreground in ('primary', 'secondary', 'accent', 'success', 'caution', 'error'):
        for behind in (bg, surface):
            ratio = contrast(rgb(expected[foreground]), behind)
            assert ratio >= 4.5, (foreground, background, ratio)
for button in ('accent', 'pressed'):
    assert contrast(rgb(expected['on_accent']), rgb(expected[button])) >= 4.5

menus = ['PhoneActivity.kt', 'PhoneSettingsActivity.kt', 'ClockSettingsPanel.kt', 'DepartureSettingsActivity.kt', 'ClockCalendarSettingsActivity.kt', 'PhoneControlsPanel.kt', 'TvLaunchPanel.kt', 'RosterSourceSelector.kt', 'MenuDiagnosticsPanel.kt', 'roster/RosterUi.kt']
for path in menus:
    source = read(phone + path)
    assert not re.search(r'(?:#|0xFF)00E040|Color\.GREEN', source, re.I), path
    assert not re.search(r'Color\.(?:BLACK|WHITE|GRAY|LTGRAY|DKGRAY)|0x[0-9a-f]{6,8}|#[0-9a-f]{6,8}', source, re.I), (path, 'menu palette must be centralized')
    assert 'getFont(R.font.b612' not in source, path
    if 'Activity :' in source or 'Activity()' in source:
        assert 'PhoneTheme.install(this)' in source, path
for path in ('ClockSettingsPanel.kt', 'DepartureSettingsActivity.kt'):
    assert 'RosterSourceSelector.add(activity, content)' in read(phone + path)
view = read(phone + 'ClockView.kt')
assert view.index('DepartureSourcePolicy.Source.ECREW') < view.index('ClockCalendar.hasPermission(context)')
calendar = read(phone + 'ClockCalendar.kt')
assert calendar.index('DepartureRosterSource.select(context, settings)') < calendar.index('if (!hasPermission(context))')
assert 'full: Boolean = true' in read(phone + 'DepartureRosterSource.kt')
selector = read(phone + 'RosterSourceSelector.kt')
assert 'else arrayOf("Calendar")' in selector
assert 'ClockSettings.load(a).copy(departureSource' in selector
assert 'if (PhoneTheme.developer(a))' in read(phone + 'MenuDiagnosticsPanel.kt')
theme = read(phone + 'PhoneTheme.kt')
for component in ('MaterialSwitch(c)', 'MaterialButton(c)', 'MaterialToolbar(a)', 'R.font.inter_regular', 'R.font.inter_medium', 'c.dp(56)', 'c.dp(16)', 'setDuration(150)', 'fontFeatureSettings = "tnum"'):
    assert component in theme, component
assert 'R.font.inter_semibold' in read(phone + 'roster/RosterUi.kt')
assert 'ClockActivity", "RosterAlarmActivity", "RosterChangesActivity' in theme

# Baseline hashes deliberately isolate TV, camera behavior and alarm/annunciator rendering.
# Whitespace-only trailing newlines are immaterial (source connector copies can add one).
for path, expected_hash in json.loads(read('scripts/phone-protected-baseline.json')).items():
    file = ROOT / path
    if not file.exists():
        if '--allow-missing' in sys.argv:
            continue
        raise AssertionError('Protected source missing: ' + path)
    raw = file.read_bytes()
    variants = [raw, raw[:-1] if raw.endswith(b'\n') else raw, raw.rstrip(b'\n') + b'\n']
    assert any(hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == expected_hash for data in variants), 'Protected rendering/behavior changed: ' + path
# Clock data changes are allowed; the complete layout/drawing/touch implementation is fixed.
marker = '        val density = resources.displayMetrics.density'
assert hashlib.sha256(view[view.index(marker):].rstrip().encode()).hexdigest() == json.loads(read('scripts/phone-clock-drawing-baseline.json'))['sha256'], 'Clock drawing changed'
print('Phone theme: centralized palette, text contrast >=4.5:1, Material components, shared source and protected clock/alarm/TV/camera checks passed')
