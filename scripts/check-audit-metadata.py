#!/usr/bin/env python3
"""Check version-independent release wording and narrow Android Dream integration."""
import pathlib,re,xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[1]
a='{http://schemas.android.com/apk/res/android}'
manifest=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
activities={x.get(a+'name'):x for x in manifest.findall('application/activity')}
assert activities['.phone.ClockSettingsActivity'].get(a+'exported')=='false'
assert activities['.phone.DreamClockSettingsActivity'].get(a+'exported')=='true'
dream=ET.parse(root/'app/src/main/res/xml/mirror_clock_dream.xml').getroot()
assert dream.get(a+'settingsActivity')=='com.mirror.app/com.mirror.app.phone.DreamClockSettingsActivity'
readme=(root/'README.md').read_text()
assert '# Mirror\n' in readme and 'releases/latest/download/mirror.apk' in readme
assert 'releases/latest/download/mirror-phone.apk' in readme
assert 'Candidates do not replace the stable release' in readme
workflow=(root/'.github/workflows/build.yml').read_text()
assert 'name: Mirror candidate — build ${{ github.run_number }}' in workflow
assert 'make_latest: false' in workflow and 'prerelease: true' in workflow
assert not re.search(r'^# Mirror \d',readme,re.M)
print('Version-independent metadata, stable URLs and narrow Dream settings verified')

assert re.search(r"^permissions:\n  contents: read$",workflow,re.M), 'Verification must have read-only default repository access'
publish=workflow.split('  publish:',1)[1]
assert '    needs: build' in publish and '      contents: write' in publish
assert workflow.count('contents: write')==1, 'Only publication may write repository contents'
for line in workflow.splitlines():
    if 'uses:' in line:
        assert re.search(r'@[0-9a-f]{40} #',line), 'Mutable action reference: '+line
assert 'Verify downloaded candidate before publishing' in publish
print('Read-only verification, dependent publication and immutable action references verified')
