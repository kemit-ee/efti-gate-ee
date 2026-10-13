#!/usr/bin/env python3
"""Validation rules of scripts/registry-to-json.py (ADR-011 §3: the converter is the validator).

Every case starts from a copy of the committed registry/ and breaks one thing. Requires PyYAML.
Run: python3 tests/registry/test_registry_to_json.py
"""

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("registry_to_json", ROOT / "scripts" / "registry-to-json.py")
converter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(converter)


class RegistryToJsonTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.source = self.tmp / "registry"
        shutil.copytree(ROOT / "registry", self.source)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def write(self, kind, name, text):
        (self.source / kind / name).write_text(text)

    def assert_rejected(self, kind, message):
        with self.assertRaises(converter.RegistryError) as raised:
            converter.load(kind, self.source)
        self.assertIn(message, str(raised.exception))

    def test_committed_registry_is_valid(self):
        for kind in converter.REGISTRIES:
            converter.load(kind, self.source)

    def test_unquoted_numeric_registry_code_is_rejected(self):
        self.write("authorities", "x.yml", "id: x\nname: X\nregistryCode: 70000099\nsubsets: [EU01]\n")
        self.assert_rejected("authorities", "registryCode must be a string")

    def test_norway_country_code_must_be_quoted(self):
        self.write("gates", "EU-NO.yml", "id: EU-NO\ncountryCode: NO\neDeliveryUrl: https://no.example/msh\nstatus: ONLINE\n")
        self.assert_rejected("gates", "countryCode must be a string")

    def test_quoted_norway_country_code_is_accepted(self):
        self.write("gates", "EU-NO.yml", "id: EU-NO\ncountryCode: 'NO'\neDeliveryUrl: https://no.example/msh\nstatus: ONLINE\n")
        self.assertIn("EU-NO", [g["id"] for g in converter.load("gates", self.source)])

    def test_gate_url_must_be_absolute_http(self):
        self.write("gates", "EU-X.yml", "id: EU-X\ncountryCode: EE\neDeliveryUrl: htp//x\nstatus: ONLINE\n")
        self.assert_rejected("gates", "eDeliveryUrl must be an absolute http(s) URL")

    def test_platform_url_must_be_absolute_http(self):
        self.write("platforms", "p.yml", "id: p\nbaseUrl: /relative\nstatus: ONLINE\n")
        self.assert_rejected("platforms", "baseUrl must be an absolute http(s) URL")

    def test_header_values_must_be_strings(self):
        self.write("platforms", "p.yml", "id: p\nbaseUrl: https://p.example\nstatus: ONLINE\nheaders:\n  X-N: 1\n")
        self.assert_rejected("platforms", "headers must be a mapping of strings to strings")

    def test_duplicate_subsets_are_rejected(self):
        self.write("authorities", "x.yml", "id: x\nname: X\nregistryCode: '1'\nsubsets: [EU01, EU01]\n")
        self.assert_rejected("authorities", "subsets contains duplicates")

    def test_shared_api_key_is_rejected_without_printing_it(self):
        self.write("platforms", "p.yml", "id: p\nbaseUrl: https://p.example\nstatus: ONLINE\napiKey: mock-secret-key\n")
        with self.assertRaises(converter.RegistryError) as raised:
            converter.load("platforms", self.source)
        self.assertIn("the same apiKey is used by several entries", str(raised.exception))
        self.assertNotIn("mock-secret-key", str(raised.exception))

    def test_ids_differing_only_in_case_are_duplicates(self):
        self.write("gates", "EU-X.yml", "id: EU-X\ncountryCode: EE\neDeliveryUrl: https://x.example\nstatus: ONLINE\n")
        self.write("gates", "eu-x.yml", "id: eu-x\ncountryCode: EE\neDeliveryUrl: https://x.example\nstatus: ONLINE\n")
        if len(list((self.source / "gates").glob("*.yml"))) < 4:
            self.skipTest("case-insensitive file system: EU-X.yml and eu-x.yml are the same file")
        self.assert_rejected("gates", "duplicate id(s) ['eu-x']")

    def test_non_yml_file_is_rejected(self):
        self.write("gates", "EU-Y.yaml", "id: EU-Y\n")
        self.assert_rejected("gates", "only <id>.yml files are allowed")


if __name__ == "__main__":
    unittest.main()
