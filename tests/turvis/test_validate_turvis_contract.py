#!/usr/bin/env python3
"""scripts/validate-turvis-contract.py must catch SPEC/DSL drift (issue #306).

Every case copies SPEC.md, the mock DSL and its scenarios into a temp tree, breaks exactly one thing and
expects a finding. Requires PyYAML. Run: python3 tests/turvis/test_validate_turvis_contract.py
"""

import contextlib
import importlib.util
import io
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("validate_turvis_contract", ROOT / "scripts" / "validate-turvis-contract.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ContractDriftTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        (self.tmp / "docs" / "specs" / "turvis").mkdir(parents=True)
        shutil.copy(ROOT / "docs" / "specs" / "turvis" / "SPEC.md", self.tmp / "docs" / "specs" / "turvis" / "SPEC.md")
        shutil.copytree(ROOT / "DSL" / "Ruuter-turvis-mock", self.tmp / "DSL" / "Ruuter-turvis-mock")
        shutil.copytree(ROOT / "DSL-turvis-mock-tests", self.tmp / "DSL-turvis-mock-tests")
        checker.SPEC = self.tmp / "docs" / "specs" / "turvis" / "SPEC.md"
        checker.DSL = self.tmp / "DSL" / "Ruuter-turvis-mock" / "turvis" / "POST" / "v1"
        checker.TESTS = self.tmp / "DSL-turvis-mock-tests"

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def run_checker(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            code = checker.main()
        return code, err.getvalue()

    def edit(self, path, old, new, count=1):
        text = path.read_text()
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, count))

    def test_committed_contract_and_mock_agree(self):
        code, err = self.run_checker()
        self.assertEqual(0, code, err)

    def test_dsl_field_dropped_from_the_allowlist(self):
        self.edit(checker.DSL / "signatures" / "xmldsig.yml", "      - field: sign_xpaths\n        type: array\n        required: true\n", "")
        code, err = self.run_checker()
        self.assertEqual(1, code)
        self.assertIn("required request fields differ", err)
        self.assertIn("'sign_xpaths' is not in the DSL allowlist", err)

    def test_dsl_field_made_optional(self):
        self.edit(checker.DSL / "encryptions" / "xmlenc.yml", "      - field: profile\n        type: string\n        required: true", "      - field: profile\n        type: string\n        required: false")
        code, err = self.run_checker()
        self.assertEqual(1, code)
        self.assertIn("required request fields differ", err)

    def test_dsl_field_type_changed(self):
        self.edit(checker.DSL / "decryptions" / "xmlenc.yml", "      - field: key_handle\n        type: string", "      - field: key_handle\n        type: array")
        code, err = self.run_checker()
        self.assertEqual(1, code)
        self.assertIn("request field 'key_handle' type differs", err)

    def test_dsl_field_not_in_the_spec(self):
        self.edit(checker.DSL / "verifications" / "xmldsig.yml", "      - field: trace_id\n        type: string\n        required: false", "      - field: trace_id\n        type: string\n        required: false\n      - field: extra\n        type: string\n        required: false")
        code, err = self.run_checker()
        self.assertEqual(1, code)
        self.assertIn("DSL allowlist field 'extra' is not in the SPEC request schema", err)

    def test_response_field_removed_from_returns(self):
        self.edit(checker.DSL / "verifications" / "xmldsig.yml", "    - field: ebms_routing\n      type: object\n", "")
        code, err = self.run_checker()
        self.assertEqual(1, code)
        self.assertIn("accept response fields differ", err)

    def test_reason_code_status_changed_in_the_dsl(self):
        path = checker.DSL / "signatures" / "xmldsig.yml"
        self.edit(path, '    reason_code: "KEY_UNAVAILABLE"\n    detail: "mock: transient, retry safe"\n    audit_ref: "${audit_ref}"\n  status: 503', '    reason_code: "KEY_UNAVAILABLE"\n    detail: "mock: transient, retry safe"\n    audit_ref: "${audit_ref}"\n  status: 500')
        self.edit(path, '    status: 503\n    verdict: "reject"\n    reason_code: "KEY_UNAVAILABLE"', '    status: 500\n    verdict: "reject"\n    reason_code: "KEY_UNAVAILABLE"')
        code, err = self.run_checker()
        self.assertEqual(1, code)
        self.assertIn("reason code KEY_UNAVAILABLE (503) has no reject step with that status", err)

    def test_reason_code_added_to_the_spec_table(self):
        self.edit(checker.SPEC, "| `TURVIS_INTERNAL` | 500 | all |", "| `NEW_CODE` | 422 | verifications | New. |\n| `TURVIS_INTERNAL` | 500 | all |")
        code, err = self.run_checker()
        self.assertEqual(1, code)
        self.assertIn("reason code NEW_CODE (422) has no reject step", err)
        self.assertIn("has no `reason code - verify ... -> 422 NEW_CODE` scenario", err)

    def test_scenario_removed(self):
        path = checker.TESTS / "turvis.test.yml"
        text = path.read_text()
        head, *blocks = text.split("\n  - name: ")
        kept = [b for b in blocks if not b.startswith("reason code - encrypt reject-payload-too-large")]
        self.assertEqual(len(blocks) - 1, len(kept))
        path.write_text("\n  - name: ".join([head] + kept))
        code, err = self.run_checker()
        self.assertEqual(1, code)
        self.assertIn("reason code PAYLOAD_TOO_LARGE (413) has no `reason code - encrypt ... -> 413 PAYLOAD_TOO_LARGE` scenario", err)

    def test_documented_override_verdict_without_a_dispatch_condition(self):
        self.edit(checker.DSL / "decryptions" / "xmlenc.yml", "`reject-ski-mismatch`,", "`reject-ski-mismatch`, `reject-made-up`,")
        code, err = self.run_checker()
        self.assertEqual(1, code)
        self.assertIn("documents override verdict reject-made-up but dispatch has no condition", err)


if __name__ == "__main__":
    unittest.main()
