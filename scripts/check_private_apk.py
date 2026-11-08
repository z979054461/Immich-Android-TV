#!/usr/bin/env python3
"""Inspect a built APK and merged manifest; this does not replace device testing."""
import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

p = argparse.ArgumentParser()
p.add_argument('apk', type=Path)
p.add_argument('--merged-manifest', type=Path, required=True)
a = p.parse_args()
namespace = '{http://schemas.android.com/apk/res/android}'
root = ET.parse(a.merged_manifest).getroot()
assert root.get('package') == 'io.github.z979054461.immichtv', 'Wrong application ID'
app = root.find('application')
assert app is not None
assert app.get(namespace + 'allowBackup') == 'false', 'Backup must remain disabled'
assert app.get(namespace + 'debuggable', 'false') == 'false', 'Release APK must not be debuggable'
assert app.get(namespace + 'fullBackupContent') == 'false'
for node in root.iter():
    name = node.get(namespace + 'name', '')
    assert not any(s in name for s in ['firebase', 'billingclient', 'BILLING', 'AD_ID', 'WRITE_EPG_DATA', 'HomeScreenChannelReceiver']), name
permissions = [n.get(namespace + 'name') for n in root.findall('uses-permission')]
assert not any('READ_EXTERNAL_STORAGE' in x or 'WRITE_EXTERNAL_STORAGE' in x or 'CAMERA' in x or 'RECORD_AUDIO' in x for x in permissions)
with ZipFile(a.apk) as z:
    dex = b''.join(z.read(n) for n in z.namelist() if re.match(r'classes\d*\.dex$', n))
    assert dex, 'No DEX in APK'
    for forbidden in [b'Lcom/google/firebase/', b'Lcom/android/billingclient/', b'immich.giejay.nl', b'?apiKey=', b'&apiKey=', b'AuthenticationClient;', b'AuthByPhoneFragment;']:
        assert forbidden not in dex, 'Prohibited runtime signature: ' + forbidden.decode()
print('PASS: APK runtime signatures, application ID, manifest privacy flags and permissions checked')
print('Not a claim of device packet capture or real Immich server compatibility')
