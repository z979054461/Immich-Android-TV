#!/usr/bin/env python3
"""Synthetic manifest fixtures only; no APK, credentials or network needed."""
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

from check_private_apk import inspect_manifest


class ReceiverPermissionAuditTest(unittest.TestCase):
    namespace = "{http://schemas.android.com/apk/res/android}"
    permission = "io.github.z979054461.immichtv.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    def manifest(self):
        root = ET.Element("manifest", package="io.github.z979054461.immichtv")
        ET.SubElement(root, "application", {
            self.namespace + "allowBackup": "false",
            self.namespace + "fullBackupContent": "false",
            self.namespace + "dataExtractionRules": "@xml/private_data_extraction_rules",
        })
        provider = ET.SubElement(root.find("application"), "provider", {
            self.namespace + "authorities": "io.github.z979054461.immichtv.updates",
            self.namespace + "exported": "false",
            self.namespace + "grantUriPermissions": "true",
        })
        ET.SubElement(provider, "meta-data", {
            self.namespace + "name": "android.support.FILE_PROVIDER_PATHS",
            self.namespace + "resource": "@xml/update_file_paths",
        })
        for name in ("android.permission.INTERNET", self.permission, "android.permission.REQUEST_INSTALL_PACKAGES"):
            ET.SubElement(root, "uses-permission", {self.namespace + "name": name})
        ET.SubElement(root, "permission", {
            self.namespace + "name": self.permission,
            self.namespace + "protectionLevel": "signature",
        })
        return root

    def inspect(self, root):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.xml"
            ET.ElementTree(root).write(path, encoding="utf-8")
            return inspect_manifest(path)

    def test_signature_symbolic_and_numeric_pass(self):
        for protection in ("signature", "2", "0x00000002"):
            with self.subTest(protection=protection):
                root = self.manifest()
                root.find("permission").set(self.namespace + "protectionLevel", protection)
                self.inspect(root)

    def test_missing_declaration_fails(self):
        root = self.manifest()
        root.remove(root.find("permission"))
        with self.assertRaisesRegex(ValueError, "declaration"):
            self.inspect(root)

    def test_missing_request_fails(self):
        root = self.manifest()
        root.remove(root.findall("uses-permission")[1])
        with self.assertRaisesRegex(ValueError, "request"):
            self.inspect(root)

    def test_weakened_or_expanded_protection_fails(self):
        for protection in ("", "normal", "dangerous", "signature|privileged", "0", "1", "0x12"):
            with self.subTest(protection=protection):
                root = self.manifest()
                root.find("permission").set(self.namespace + "protectionLevel", protection)
                with self.assertRaisesRegex(ValueError, "signature-only"):
                    self.inspect(root)

    def test_wrong_owner_or_unresolved_placeholder_fails(self):
        for permission in ("other.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                           "${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"):
            with self.subTest(permission=permission):
                root = self.manifest()
                root.find("permission").set(self.namespace + "name", permission)
                with self.assertRaisesRegex(ValueError, "declaration"):
                    self.inspect(root)

    def test_update_provider_cannot_be_exported_or_unscoped(self):
        for attribute, value in (("exported", "true"), ("grantUriPermissions", "false")):
            root = self.manifest()
            root.find("application/provider").set(self.namespace + attribute, value)
            with self.assertRaisesRegex(ValueError, "nonexported"):
                self.inspect(root)

    def test_update_permission_is_explicit(self):
        root = self.manifest()
        root.remove(root.findall("uses-permission")[-1])
        with self.assertRaisesRegex(ValueError, "installer permission"):
            self.inspect(root)

    def test_duplicate_declaration_fails(self):
        root = self.manifest()
        ET.SubElement(root, "permission", dict(root.find("permission").attrib))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.inspect(root)


if __name__ == "__main__":
    unittest.main()
