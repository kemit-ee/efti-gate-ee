#!/usr/bin/env python3
"""Unit tests for scripts/validate-no-pii-log.py: raw bodies in log calls and bodyOrThrow() must fail (#322, #357)."""
import contextlib
import importlib.util
import io
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
spec = importlib.util.spec_from_file_location("validate_no_pii_log", ROOT / "scripts" / "validate-no-pii-log.py")
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


class ValidateNoPiiLog(unittest.TestCase):
    def run_on(self, source):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "code" / "x" / "src" / "A.kt"
            path.parent.mkdir(parents=True)
            path.write_text(source)
            original = v.ROOT
            v.ROOT = pathlib.Path(tmp)
            try:
                with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                    return v.main()
            finally:
                v.ROOT = original

    def test_clean_source_passes(self):
        self.assertEqual(0, self.run_on('val x = http.post(u, b).bodyOrThrowStatusOnly()\nlog.info("done audit_ref=$ref")\n'))

    def test_klite_bodyOrThrow_fails_in_any_use(self):
        for src in ["val x = res.bodyOrThrow()\n", "import klite.http.bodyOrThrow\n", "}.bodyOrThrow()\n"]:
            with self.subTest(src):
                self.assertEqual(1, self.run_on(src))

    def test_a_reviewed_bodyOrThrow_is_allowed_with_a_reason(self):
        self.assertEqual(0, self.run_on("val x = res.bodyOrThrow() // pii-ok: a test double\n"))

    def test_a_response_body_variable_in_a_log_call_fails(self):
        for src in ['log.warn("x ${reason ?: resBody}")\n', 'log.warn("x $peerAnswer")\n', 'log.warn("x " + res.body())\n']:
            with self.subTest(src):
                self.assertEqual(1, self.run_on(src))

    def test_size_and_length_of_a_body_are_fine(self):
        self.assertEqual(0, self.run_on('log.warn("x ${resBody.length} ${res.body().length}")\n'))


if __name__ == "__main__":
    unittest.main()
