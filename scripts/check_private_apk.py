#!/usr/bin/env python3
"""Inspect APK bytes and XML decoded from THAT APK by SDK apkanalyzer.

CI must run `apkanalyzer manifest print "$APK"` successfully and pass its output
as --apk-manifest. The optional build manifest is only a consistency check.
This does not replace signature verification or device testing.
"""
import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inspect_manifest(path):
    namespace = '{http://schemas.android.com/apk/res/android}'
    root = ET.parse(path).getroot()
    require(root.tag == 'manifest', 'Expected a decoded Android manifest')
    require(root.get('package') == 'io.github.z979054461.immichtv', 'Wrong application ID')
    apps = root.findall('application')
    require(len(apps) == 1, 'Expected exactly one application')
    app = apps[0]
    require(app.get(namespace + 'allowBackup') == 'false', 'Backup must remain disabled')
    require(app.get(namespace + 'debuggable', 'false') == 'false', 'Release APK must not be debuggable')
    require(app.get(namespace + 'fullBackupContent') == 'false', 'Full backup must remain disabled')
    require(bool(app.get(namespace + 'dataExtractionRules')), 'Explicit data extraction rules required')
    for node in root.iter():
        name = node.get(namespace + 'name', '')
        require(not any(s in name for s in ['firebase', 'billingclient', 'BILLING', 'AD_ID',
                                          'WRITE_EPG_DATA', 'HomeScreenChannelReceiver']),
                'Prohibited manifest component/permission: ' + name)
    permissions = sorted(node.get(namespace + 'name', '') for node in root
                         if node.tag in ('uses-permission', 'uses-permission-sdk-23', 'uses-permission-sdk-m'))
    require(all(permissions), 'Unnamed permission')
    require('android.permission.INTERNET' in permissions, 'Missing Internet permission')
    require(not any(token in permission for permission in permissions
                    for token in ('READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE',
                                  'MANAGE_EXTERNAL_STORAGE', 'READ_MEDIA_', 'CAMERA', 'RECORD_AUDIO')),
            'Unexpected sensitive permission')
    receiver_permission = root.get('package') + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
    require(receiver_permission in permissions, 'Missing non-exported receiver permission request')
    declarations = [node for node in root.findall('permission')
                    if node.get(namespace + 'name') == receiver_permission]
    require(len(declarations) == 1, 'Missing or duplicate app-owned receiver permission declaration')
    protection = declarations[0].get(namespace + 'protectionLevel', '')
    # apkanalyzer normally prints the symbolic value; other SDK decoders may emit its integer.
    try:
        signature_only = int(protection, 0) == 2
    except ValueError:
        signature_only = protection == 'signature'
    require(signature_only, 'Non-exported receiver permission must remain signature-only')
    # Resource identifiers can differ between source XML and compiled APK XML;
    # compare only fields whose decoded meanings must match literally.
    return (root.get('package'), app.get(namespace + 'allowBackup'),
            app.get(namespace + 'debuggable', 'false'), app.get(namespace + 'fullBackupContent'),
            permissions, receiver_permission, signature_only)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--apk-manifest', type=Path, required=True,
                        help='XML emitted by a successful SDK apkanalyzer manifest print of this APK')
    parser.add_argument('--merged-manifest', type=Path,
                        help='Optional build-output manifest, checked only for consistency')
    args = parser.parse_args()
    actual = inspect_manifest(args.apk_manifest)
    if args.merged_manifest:
        require(actual == inspect_manifest(args.merged_manifest),
                'APK manifest differs from merged manifest privacy fields/permissions')
    with ZipFile(args.apk) as archive:
        require('AndroidManifest.xml' in archive.namelist(), 'APK contains no Android manifest')
        dex = b''.join(archive.read(name) for name in archive.namelist()
                       if re.fullmatch(r'classes\d*\.dex', name))
        require(bool(dex), 'No DEX in APK')
        for forbidden in [b'Lcom/google/firebase/', b'Lcom/android/billingclient/', b'immich.giejay.nl',
                          b'?apiKey=', b'&apiKey=', b'AuthenticationClient;', b'AuthByPhoneFragment;']:
            require(forbidden not in dex, 'Prohibited runtime signature: ' + forbidden.decode())
    print('PASS: APK runtime signatures and APK-decoded manifest application ID, privacy flags and permissions')
    print('Not a claim of signature validity, device installation, packet capture or real Immich compatibility')


if __name__ == '__main__':
    main()
