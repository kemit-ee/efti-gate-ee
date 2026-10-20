#!/usr/bin/env python3
"""Validation rules of scripts/registry-to-json.py (ADR-011 §3: the converter is the validator).

Every case starts from a copy of the committed registry/ and breaks one thing. Requires PyYAML.
Run: python3 tests/registry/test_registry_to_json.py
"""

import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("registry_to_json", ROOT / "scripts" / "registry-to-json.py")
converter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(converter)

# Certificates are generated per test: the committed dev fixtures expire (2027-08-25) and must not
# be what the view rules are tested against.
ISSUED = datetime(2026, 10, 1, tzinfo=timezone.utc)


def make_cert(common_name, org=None, not_after=datetime(2027, 10, 20, tzinfo=timezone.utc), ca=False, not_before=None):
    key = ec.generate_private_key(ec.SECP256R1())
    attributes = [x509.NameAttribute(NameOID.COMMON_NAME, common_name)]
    if org:
        attributes.insert(0, x509.NameAttribute(NameOID.ORGANIZATION_NAME, org))
    name = x509.Name(attributes)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(not_before or min(ISSUED, not_after - timedelta(days=30)))
        .not_valid_after(not_after)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
        .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )
    return certificate.public_bytes(serialization.Encoding.PEM).decode()


def gate_yaml(id, cert, extra=""):
    indented = "".join(f"  {line}\n" for line in cert.splitlines())
    return f"id: {id}\ncountryCode: EE\neDeliveryUrl: https://x.example/msh\nstatus: ONLINE\neDeliveryCert: |\n{indented}{extra}"


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

    def test_pem_file_is_still_rejected_in_a_registry_directory(self):
        self.write("gates", "EU-Y.pem", make_cert("EU-Y"))
        self.assert_rejected("gates", "only <id>.yml files are allowed")

    def test_allowed_chain_anchors_must_be_a_list_of_aliases(self):
        self.write("gates", "EU-X.yml", gate_yaml("EU-X", make_cert("EU-X"), extra="allowedChainAnchors: root\n"))
        self.assert_rejected("gates", "allowedChainAnchors must be a list of trust-anchor aliases")

    def test_allowed_chain_anchors_never_reach_the_registry_documents(self):
        self.write("gates", "EU-X.yml", gate_yaml("EU-X", make_cert("EU-X"), extra="allowedChainAnchors: [root]\n"))
        loaded = {g["id"]: g for g in converter.load("gates", self.source)}
        self.assertEqual(["root"], loaded["EU-X"]["allowedChainAnchors"])
        self.assertNotIn("allowedChainAnchors", converter.public_entity("gates", loaded["EU-X"]))


class CommandLineTest(unittest.TestCase):
    """The script as the Dockerfiles run it: files written, generation shared between the two outputs."""

    def run_script(self, *args, source=ROOT / "registry"):
        return subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "registry-to-json.py"), "--source", str(source), *args],
            capture_output=True, text=True,
        )

    def test_out_and_trust_out_share_one_generation(self):
        with tempfile.TemporaryDirectory() as tmp:
            out, trust = Path(tmp) / "registry", Path(tmp) / "trust"
            done = self.run_script("--out", str(out), "--trust-out", str(trust), "--source-revision", "rev-abc123")
            self.assertEqual(0, done.returncode, done.stderr)
            view = json.loads((trust / "turvis-trust.json").read_text())
            self.assertEqual("rev-abc123", view["source_revision"])
            self.assertEqual(view["generation"], json.loads((out / "health.json").read_text())["trustViewGeneration"])
            self.assertEqual(view["generation"], json.loads((trust / "health.json").read_text())["generation"])
            self.assertFalse((trust / "platforms.json").exists())
            self.assertNotIn("turvis-trust.json", [p.name for p in out.iterdir()])

    def test_validation_only_run_builds_the_view_and_writes_nothing(self):
        done = self.run_script()
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("validated turvis-trust parties=", done.stdout)

    def test_failing_view_fails_the_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "registry"
            shutil.copytree(ROOT / "registry", source)
            expired = make_cert("EU-X", not_after=datetime(2020, 1, 1, tzinfo=timezone.utc))
            (source / "gates" / "EU-X.yml").write_text(gate_yaml("EU-X", expired))
            done = self.run_script(source=source)
            self.assertEqual(1, done.returncode)
            self.assertIn("certificate expired", done.stderr)


class TrustViewTest(unittest.TestCase):
    """The Turvis trust view (ADR-011 amendment, SPEC section 7.2), built from crafted gates."""

    NOW = datetime(2026, 10, 20, tzinfo=timezone.utc)

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.source = self.tmp / "registry"
        shutil.copytree(ROOT / "registry", self.source)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def gate(self, id, cert, status="ONLINE", **extra):
        gate = {"id": id, "countryCode": "EE", "eDeliveryUrl": "https://x.example/msh", "status": status, **extra}
        if cert is not None:
            gate["eDeliveryCert"] = cert
        return gate

    def view(self, gates, anchors=()):
        return converter.build_trust_view(gates, list(anchors), self.NOW)

    def test_committed_registry_builds_a_view(self):
        gates = converter.load("gates", self.source)
        anchors = converter.load_trust_anchors(self.source, datetime.now(timezone.utc))
        view = converter.build_trust_view(gates, anchors, datetime.now(timezone.utc))
        self.assertEqual({g["id"] for g in gates if g.get("eDeliveryCert")}, {p["party_id"] for p in view["parties"]})

    def test_no_credential_or_url_field_reaches_the_view(self):
        gate = self.gate(
            "EU-X", make_cert("EU-X"), tlsCert=make_cert("tls"),
            apiKey="s3cret-api-key", headers={"X-Api-Key": "s3cret-header"},
        )
        text = json.dumps(self.view([gate]))
        for forbidden in ("apiKey", "headers", "tlsCert", "eDeliveryUrl", "baseUrl", "s3cret", "x.example", "http"):
            self.assertNotIn(forbidden, text)

    def test_no_platform_data_can_reach_the_view(self):
        platform_key = next(p["apiKey"] for p in converter.load("platforms", self.source) if p.get("apiKey"))
        gates = converter.load("gates", self.source)
        self.assertNotIn(platform_key, json.dumps(converter.build_trust_view(gates, [], datetime.now(timezone.utc))))

    def test_keys_outside_the_allowlist_fail_the_build(self):
        view = self.view([self.gate("EU-X", make_cert("EU-X"))])
        view["parties"][0]["apiKey"] = "x"
        with self.assertRaisesRegex(converter.RegistryError, "outside the allowlist"):
            converter.assert_allowlist(view)
        view = self.view([self.gate("EU-X", make_cert("EU-X"))])
        view["internal"] = True
        with self.assertRaisesRegex(converter.RegistryError, "outside the allowlist"):
            converter.assert_allowlist(view)

    def test_party_fields_come_from_the_certificate(self):
        pem = make_cert("EU-X", org="DGITM")
        party = self.view([self.gate("EU-X", pem)])["parties"][0]
        self.assertEqual(
            {"party_id": "EU-X", "status": "ONLINE", "verified_identity": "EU-X", "cert_pem": pem,
             "cert_subject_dn": "CN=EU-X,O=DGITM", "valid_until": "2027-10-20T00:00:00Z", "allowed_chain_anchors": []},
            {k: v for k, v in party.items() if k != "cert_ski"},
        )
        self.assertRegex(party["cert_ski"], r"^[0-9a-f]{40}$")

    def test_generation_is_deterministic_and_ignores_the_revision(self):
        gates = [self.gate("EU-A", make_cert("EU-A")), self.gate("EU-B", make_cert("EU-B"))]
        first = converter.build_trust_view(gates, [], self.NOW, "git:aaaa")
        second = converter.build_trust_view(list(reversed(gates)), [], self.NOW, "git:bbbb")
        self.assertEqual(first["generation"], second["generation"])
        self.assertEqual(first["parties"], second["parties"])
        self.assertRegex(first["generation"], r"^sha256:[0-9a-f]{64}$")
        self.assertEqual("git:aaaa", first["source_revision"])

    def test_generation_changes_with_the_content(self):
        a = self.view([self.gate("EU-A", make_cert("EU-A"))])
        self.assertNotEqual(a["generation"], self.view([self.gate("EU-A", make_cert("EU-A"), status="DISABLED")])["generation"])
        self.assertNotEqual(a["generation"], self.view([self.gate("EU-A", make_cert("EU-A", org="Other"))])["generation"])

    def test_disabled_gate_stays_in_the_view(self):
        view = self.view([self.gate("EU-X", make_cert("EU-X"), status="DISABLED")])
        self.assertEqual([("EU-X", "DISABLED")], [(p["party_id"], p["status"]) for p in view["parties"]])

    def test_gate_without_certificate_is_left_out_not_a_failure(self):
        view = self.view([self.gate("EU-A", make_cert("EU-A")), self.gate("EU-NOCERT", None), self.gate("EU-OFF", None, status="DISABLED")])
        self.assertEqual(["EU-A"], [p["party_id"] for p in view["parties"]])

    def test_expired_certificate_fails_the_build(self):
        for status in ("ONLINE", "DISABLED"):
            with self.assertRaisesRegex(converter.RegistryError, "EU-X: eDeliveryCert: certificate expired on 2026-10-19"):
                self.view([self.gate("EU-X", make_cert("EU-X", not_after=datetime(2026, 10, 19, tzinfo=timezone.utc)), status=status)])

    def test_not_yet_valid_certificate_fails_the_build(self):
        future = make_cert("EU-X", not_before=datetime(2026, 10, 21, tzinfo=timezone.utc))
        for status in ("ONLINE", "DISABLED"):
            with self.assertRaisesRegex(converter.RegistryError, "EU-X: eDeliveryCert: certificate is not valid before 2026-10-21"):
                self.view([self.gate("EU-X", future, status=status)])

    def test_certificate_valid_from_exactly_now_is_accepted(self):
        self.view([self.gate("EU-X", make_cert("EU-X", not_before=self.NOW))])

    def test_unparseable_certificate_fails_the_build(self):
        garbage = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
        with self.assertRaisesRegex(converter.RegistryError, "EU-X: eDeliveryCert: certificate does not parse"):
            self.view([self.gate("EU-X", garbage)])

    def test_duplicate_verified_identity_fails_the_build(self):
        with self.assertRaisesRegex(converter.RegistryError, r"verified_identity is duplicated: \['eu-x'\]"):
            self.view([self.gate("EU-X", make_cert("a")), self.gate("eu-x", make_cert("b"))])

    def test_pinning_is_the_default(self):
        self.assertEqual([], self.view([self.gate("EU-X", make_cert("EU-X"))])["parties"][0]["allowed_chain_anchors"])

    def test_chain_mode_is_per_party_data(self):
        root = make_cert("root-ca")
        gates = [self.gate("EU-A", make_cert("EU-A"), allowedChainAnchors=["Root-CA"]), self.gate("EU-B", make_cert("EU-B"))]
        view = self.view(gates, [{"alias": "Root-CA", "cert_pem": root}])
        by_id = {p["party_id"]: p for p in view["parties"]}
        self.assertEqual(["Root-CA"], by_id["EU-A"]["allowed_chain_anchors"])
        self.assertEqual([], by_id["EU-B"]["allowed_chain_anchors"])
        self.assertEqual([{"alias": "Root-CA", "cert_pem": root}], view["trust_anchors"])

    def test_reference_to_a_missing_anchor_alias_fails_the_build(self):
        with self.assertRaisesRegex(converter.RegistryError, r"allowedChainAnchors names no file in trust-anchors/: \['nope'\]"):
            self.view([self.gate("EU-X", make_cert("EU-X"), allowedChainAnchors=["nope"])])

    def anchors_dir(self):
        directory = self.source / "trust-anchors"
        directory.mkdir(exist_ok=True)
        return directory

    def test_no_trust_anchors_directory_is_fine(self):
        self.assertEqual([], converter.load_trust_anchors(self.source, self.NOW))

    def test_pem_is_allowed_under_trust_anchors(self):
        pem = make_cert("root-ca", ca=True)
        (self.anchors_dir() / "efti-root-ca-2026.pem").write_text(pem)
        self.assertEqual([{"alias": "efti-root-ca-2026", "cert_pem": pem}], converter.load_trust_anchors(self.source, self.NOW))

    def test_leaf_certificate_is_not_accepted_as_a_trust_anchor(self):
        (self.anchors_dir() / "leaf.pem").write_text(make_cert("leaf"))
        with self.assertRaisesRegex(converter.RegistryError, "trust anchor is not a CA certificate"):
            converter.load_trust_anchors(self.source, self.NOW)

    def test_parties_sharing_a_certificate_are_warned_about_not_rejected(self):
        shared = make_cert("shared")
        view = self.view([self.gate("EU-A", shared), self.gate("EU-B", shared), self.gate("EU-C", make_cert("c"))])
        warnings = converter.shared_certificate_warnings(view["parties"])
        self.assertEqual(1, len(warnings))
        self.assertIn("['EU-A', 'EU-B']", warnings[0])

    def test_non_pem_file_under_trust_anchors_is_rejected(self):
        (self.anchors_dir() / "root.yml").write_text("id: root\n")
        with self.assertRaisesRegex(converter.RegistryError, "only <alias>.pem files are allowed"):
            converter.load_trust_anchors(self.source, self.NOW)

    def test_invalid_or_expired_anchor_is_rejected(self):
        directory = self.anchors_dir()
        (directory / "root.pem").write_text("not a certificate\n")
        with self.assertRaisesRegex(converter.RegistryError, "not a PEM certificate"):
            converter.load_trust_anchors(self.source, self.NOW)
        (directory / "root.pem").write_text(make_cert("root", ca=True, not_after=datetime(2026, 1, 1, tzinfo=timezone.utc)))
        with self.assertRaisesRegex(converter.RegistryError, "certificate expired"):
            converter.load_trust_anchors(self.source, self.NOW)

    def test_not_yet_valid_anchor_is_rejected(self):
        (self.anchors_dir() / "root.pem").write_text(make_cert("root", ca=True, not_before=datetime(2026, 10, 21, tzinfo=timezone.utc)))
        with self.assertRaisesRegex(converter.RegistryError, "certificate is not valid before 2026-10-21"):
            converter.load_trust_anchors(self.source, self.NOW)

    def test_anchor_aliases_are_case_insensitive_duplicates(self):
        directory = self.anchors_dir()
        (directory / "Root.pem").write_text(make_cert("a", ca=True))
        (directory / "root.pem").write_text(make_cert("b", ca=True))
        if len(list(directory.glob("*.pem"))) < 2:
            self.skipTest("case-insensitive file system")
        with self.assertRaisesRegex(converter.RegistryError, "duplicate anchor alias"):
            converter.load_trust_anchors(self.source, self.NOW)


if __name__ == "__main__":
    unittest.main()
