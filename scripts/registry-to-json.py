#!/usr/bin/env python3
"""Convert the YAML registry into the JSON documents the gate actually consumes (ADR-011).

`registry/{gates,platforms,authorities}/<id>.yml` is the human-authored source of truth. Consumers
must not see YAML, and the served form must be static files (nginx, no application server), so this
converter runs at **image build time** and emits:

    <out>/gates.json              all gates, one array, ordered by id
    <out>/gates/<id>.json         one gate                 (id lower-cased — see below)
    <out>/platforms.json          all platforms, one array
    <out>/platforms/<id>.json     one platform
    <out>/authorities.json        all authorities, one array
    <out>/authorities/<id>.json   one authority
    <out>/health.json             {status, gates, platforms, authorities, trustViewGeneration}
    <trust-out>/turvis-trust.json the Turvis trust view (ADR-011 amendment, SPEC section 7.2)
    <trust-out>/health.json       {status, generation, parties, trustAnchors}

It is also the **validator**: a malformed file stops the build (and therefore the deployment), so
nothing unvalidated can ever be served. Run it with no `--out` in CI to validate without writing.
The Turvis trust view is built (and therefore validated) on every run, with or without `--out`, so a
registry the view cannot represent never produces a registry image either.

Things worth knowing:

* **The trust view is allowlist-only.** It is assembled field by field from `registry/gates/*.yml`
  and `registry/trust-anchors/*.pem`; platform entries, `apiKey`, `headers`, `tlsCert` and URLs have
  no code path into it, and `assert_allowlist` re-checks the finished document.
* **`eDeliveryCert` stays optional.** A gate without one is left out of the view (exactly as
  edelivery leaves it out of the AS4 party map); an existing certificate that does not parse, has
  expired or is not valid yet fails the build.

* **Absent optional keys are omitted** from the output, rather than written as `null`. Empty
  collections are *not* absent — `subsets: []` means "entitled to nothing" and stays.
* **Per-entity files are named with the lower-cased id.** A static file server is case-sensitive,
  but ids were case-insensitive when they lived in a CITEXT column, so consumers lower-case the id
  before requesting. The canonical `id` is still what the documents and the arrays contain.

Requires PyYAML and cryptography.
"""

import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

COUNTRY_CODE_RE = re.compile(r"^[A-Z]{2}$")
SUBSET_RE = re.compile(r"^EU0[1-7]$")
PEM_RE = re.compile(r"^-----BEGIN CERTIFICATE-----\n(?:[A-Za-z0-9+/=]+\n)+-----END CERTIFICATE-----\n?$")
ANCHOR_ALIAS_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
# Ids are compared case-insensitively (PartyId, the DSL lower-casing); a non-ASCII id such as a dotless i would fold onto an ASCII one.
ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

# Keys of a gate that only the Turvis trust view needs. They are validated with the gate but are not
# part of the registry documents edelivery and the DSL read (edelivery's loader rejects unknown keys).
TRUST_ONLY_KEYS = {"gates": ("allowedChainAnchors",)}

# The complete set of keys that may appear in turvis-trust.json (SPEC section 7.2). Anything else
# fails the build.
VIEW_KEYS = ("schema_version", "generation", "source_revision", "trust_anchors", "parties")
PARTY_KEYS = ("party_id", "status", "verified_identity", "cert_pem", "cert_ski", "cert_subject_dn", "valid_until", "allowed_chain_anchors")
ANCHOR_KEYS = ("alias", "cert_pem")
TRUST_SCHEMA_VERSION = "1.0"


class RegistryError(Exception):
    """Anything that must fail the build."""


def check_strings(entity, source, fields):
    # YAML turns unquoted `70000099` into an int, `NO` into False and `0123` into an octal int; the
    # DSL compares these fields with `===` against strings, so a non-string would silently never match.
    for field in fields:
        value = entity.get(field)
        if value is not None and not isinstance(value, str):
            raise RegistryError(f"{source}: {field} must be a string (quote it in YAML), got {type(value).__name__} {value!r}")


def check_url(entity, source, field):
    parts = urlsplit(entity[field])
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise RegistryError(f"{source}: {field} must be an absolute http(s) URL, got {entity[field]!r}")


def check_certs(entity, source):
    for field in ("eDeliveryCert", "tlsCert"):
        value = entity.get(field)
        if value is not None and not PEM_RE.match(value):
            raise RegistryError(f"{source}: {field} is not a PEM certificate (expected -----BEGIN/END CERTIFICATE-----)")


def check_gate(entity, source):
    if not COUNTRY_CODE_RE.match(entity["countryCode"]):
        raise RegistryError(f"{source}: countryCode must be two upper-case letters, got {entity['countryCode']!r}")
    check_url(entity, source, "eDeliveryUrl")
    check_certs(entity, source)
    anchors = entity.get("allowedChainAnchors")
    if anchors is not None:
        if not isinstance(anchors, list) or not all(isinstance(a, str) and ANCHOR_ALIAS_RE.match(a) for a in anchors):
            raise RegistryError(f"{source}: allowedChainAnchors must be a list of trust-anchor aliases, got {anchors!r}")
        if len({a.lower() for a in anchors}) != len(anchors):
            raise RegistryError(f"{source}: allowedChainAnchors contains duplicates: {anchors!r}")


def check_platform(entity, source):
    headers = entity.get("headers", {})
    if not isinstance(headers, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in headers.items()):
        raise RegistryError(f"{source}: headers must be a mapping of strings to strings")
    check_url(entity, source, "baseUrl")
    check_certs(entity, source)
    api_key = entity.get("apiKey")
    if api_key is not None and (not isinstance(api_key, str) or not api_key):
        raise RegistryError(f"{source}: apiKey must be a non-empty string when present")


def check_authority(entity, source):
    subsets = entity["subsets"]
    if not isinstance(subsets, list) or not all(isinstance(s, str) and SUBSET_RE.match(s) for s in subsets):
        raise RegistryError(f"{source}: subsets must be a list of EU01..EU07 codes, got {subsets!r}")
    if len(set(subsets)) != len(subsets):
        raise RegistryError(f"{source}: subsets contains duplicates: {subsets!r}")


STRING_FIELDS = ("id", "countryCode", "eDeliveryUrl", "baseUrl", "status", "eDeliveryCert", "tlsCert", "apiKey", "name", "registryCode")

# Per registry: required fields, optional fields, allowed `status` values (None = the type has no
# status field at all), and the type-specific validation hook.
REGISTRIES = {
    "gates": {
        "required": ("id", "countryCode", "eDeliveryUrl", "status"),
        "optional": ("eDeliveryCert", "tlsCert", "allowedChainAnchors"),
        "statuses": ("ONLINE", "DISABLED"),
        "check": check_gate,
    },
    "platforms": {
        "required": ("id", "baseUrl", "status"),
        "optional": ("headers", "eDeliveryCert", "tlsCert", "apiKey"),
        "statuses": ("ONLINE", "DISABLED"),
        "check": check_platform,
    },
    "authorities": {
        "required": ("id", "name", "registryCode", "subsets"),
        "optional": (),
        "statuses": None,
        "check": check_authority,
    },
}


def validate(kind, registry, source, entity):
    if not isinstance(entity, dict):
        raise RegistryError(f"{source}: must be a YAML mapping")

    known = registry["required"] + registry["optional"]
    unknown = sorted(set(entity) - set(known))
    if unknown:
        raise RegistryError(f"{source}: unknown key(s) {unknown}; known keys are {sorted(known)}")

    missing = [f for f in registry["required"] if entity.get(f) in (None, "")]
    if missing:
        raise RegistryError(f"{source}: missing required key(s) {missing}")

    check_strings(entity, source, [f for f in STRING_FIELDS if f in known])

    if entity["id"] != source.stem:
        raise RegistryError(f"{source}: id {entity['id']!r} does not match the file name (must be {source.stem!r})")

    if not ID_RE.match(entity["id"]):
        raise RegistryError(f"{source}: id must be ASCII letters, digits, '.', '_' or '-' (max 64 characters): {entity['id']!r}")

    if registry["statuses"] is not None and entity["status"] not in registry["statuses"]:
        raise RegistryError(f"{source}: status must be one of {list(registry['statuses'])}, got {entity['status']!r}")

    registry["check"](entity, source)

    # Absent and explicitly-null optional keys are dropped; empty collections are meaningful and stay.
    return {f: entity[f] for f in known if entity.get(f) is not None}


def load(kind, source_dir):
    registry = REGISTRIES[kind]
    directory = source_dir / kind
    if not directory.is_dir():
        raise RegistryError(f"{directory} is missing — the registry source must contain {kind}/")

    import yaml

    stray = sorted(p.name for p in directory.iterdir() if p.suffix != ".yml" and not p.name.startswith("."))
    if stray:
        raise RegistryError(f"{directory}: only <id>.yml files are allowed, found {stray}")

    entities = []
    # Ordered by id (the file stem), not by file name: '-' sorts before '.', so ordering by file name
    # would place `auth-xroad-tm-no-eu02.yml` before `auth-xroad-tm.yml` and break the "ordered by id"
    # contract that list consumers rely on for stable paging.
    for path in sorted(directory.glob("*.yml"), key=lambda p: p.stem.lower()):
        try:
            entity = yaml.safe_load(path.read_text())
        except yaml.YAMLError as e:
            raise RegistryError(f"{path}: invalid YAML: {e}") from e
        entities.append(validate(kind, registry, path, entity))

    # Case-insensitive: per-entity files are written under the lower-cased id, and ids are matched
    # case-insensitively everywhere (PartyId, the DSL), so `EU-X` and `eu-x` are the same entity.
    ids = [e["id"].lower() for e in entities]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    if duplicates:
        raise RegistryError(f"{directory}: duplicate id(s) {duplicates} (ids are case-insensitive)")

    # The platform guard answers 403 when one X-Api-Key matches several platforms, so a shared key
    # would lock both out at runtime. The key itself is never printed.
    owners = {}
    for e in entities:
        if e.get("apiKey") is not None:
            owners.setdefault(e["apiKey"], []).append(e["id"])
    shared = sorted(ids for ids in owners.values() if len(ids) > 1)
    if shared:
        raise RegistryError(f"{directory}: the same apiKey is used by several entries {shared}")
    return entities


def parse_certificate(pem, what, now):
    """Parse a PEM certificate and refuse an expired or not-yet-valid one; `what` names it in the error."""
    from cryptography import x509

    try:
        certificate = x509.load_pem_x509_certificate(pem.encode())
    except ValueError as e:
        raise RegistryError(f"{what}: certificate does not parse: {e}") from e
    if certificate.not_valid_before_utc > now:
        raise RegistryError(f"{what}: certificate is not valid before {certificate.not_valid_before_utc:%Y-%m-%dT%H:%M:%SZ}")
    if certificate.not_valid_after_utc <= now:
        raise RegistryError(f"{what}: certificate expired on {certificate.not_valid_after_utc:%Y-%m-%dT%H:%M:%SZ}")
    return certificate


def canonical_pem(certificate):
    """The certificate re-encoded as PEM, so line wrapping and trailing newlines in the source file
    cannot change the view or its `generation`."""
    from cryptography.hazmat.primitives import serialization

    return certificate.public_bytes(serialization.Encoding.PEM).decode()


def require_ca(certificate, what):
    """A trust anchor must be a CA certificate; a leaf placed there would anchor nothing meaningful."""
    from cryptography import x509

    try:
        constraints = certificate.extensions.get_extension_for_class(x509.BasicConstraints).value
    except x509.ExtensionNotFound:
        constraints = None
    if constraints is None or not constraints.ca:
        raise RegistryError(f"{what}: trust anchor is not a CA certificate (BasicConstraints CA:TRUE required)")


def shared_certificate_warnings(parties):
    """Two parties pinned to one certificate cannot be told apart by their signature (the dev fixture
    EU-EE/EU-MOCK does exactly that, SPEC section 13.11). Not a build failure: the SPEC's list of
    failures does not include it and the committed dev registry would no longer build."""
    owners = {}
    for party in parties:
        owners.setdefault(party["cert_ski"], []).append(party["party_id"])
    return [
        f"parties {sorted(ids)} share one certificate (cert_ski {ski}); under pinning a signature cannot tell them apart"
        for ski, ids in sorted(owners.items())
        if len(ids) > 1
    ]


def load_trust_anchors(source_dir, now):
    """`registry/trust-anchors/<alias>.pem` — the only place `.pem` files are allowed. Optional."""
    directory = source_dir / "trust-anchors"
    if not directory.exists():
        return []
    if not directory.is_dir():
        raise RegistryError(f"{directory} must be a directory")

    stray = sorted(p.name for p in directory.iterdir() if p.suffix != ".pem" and not p.name.startswith("."))
    if stray:
        raise RegistryError(f"{directory}: only <alias>.pem files are allowed, found {stray}")

    anchors = []
    for path in sorted(directory.glob("*.pem"), key=lambda p: p.stem.lower()):
        if not ANCHOR_ALIAS_RE.match(path.stem):
            raise RegistryError(f"{path}: the file name (minus .pem) is the anchor alias and must match {ANCHOR_ALIAS_RE.pattern}")
        text = path.read_text()
        if not PEM_RE.match(text):
            raise RegistryError(f"{path}: not a PEM certificate (expected -----BEGIN/END CERTIFICATE-----)")
        certificate = parse_certificate(text, str(path), now)
        require_ca(certificate, str(path))
        anchors.append({"alias": path.stem, "cert_pem": canonical_pem(certificate)})

    aliases = [a["alias"].lower() for a in anchors]
    duplicates = sorted({a for a in aliases if aliases.count(a) > 1})
    if duplicates:
        raise RegistryError(f"{directory}: duplicate anchor alias(es) {duplicates} (aliases are case-insensitive)")
    return anchors


def build_party(gate, anchors_by_alias, now):
    from cryptography import x509

    source = f"gates/{gate['id']}"
    certificate = parse_certificate(gate["eDeliveryCert"], f"{source}: eDeliveryCert", now)
    try:
        ski = certificate.extensions.get_extension_for_class(x509.SubjectKeyIdentifier).value
    except x509.ExtensionNotFound:
        ski = x509.SubjectKeyIdentifier.from_public_key(certificate.public_key())

    chain_anchors = gate.get("allowedChainAnchors", [])
    unknown = sorted(a for a in chain_anchors if a.lower() not in anchors_by_alias)
    if unknown:
        raise RegistryError(f"{source}: allowedChainAnchors names no file in trust-anchors/: {unknown}")

    return {
        "party_id": gate["id"],
        "status": gate["status"],
        "verified_identity": gate["id"],
        "cert_pem": canonical_pem(certificate),
        "cert_ski": ski.digest.hex(),
        "cert_subject_dn": certificate.subject.rfc4514_string(),
        "valid_until": f"{certificate.not_valid_after_utc:%Y-%m-%dT%H:%M:%SZ}",
        "allowed_chain_anchors": [anchors_by_alias[a.lower()] for a in chain_anchors],
    }


def assert_allowlist(view):
    """Fail on any key outside the allowlist, wherever it sits in the document."""
    def check(level, document, allowed):
        extra = sorted(set(document) - set(allowed))
        if extra:
            raise RegistryError(f"turvis-trust.json: {level} has key(s) outside the allowlist: {extra}")

    check("document", view, VIEW_KEYS)
    for party in view["parties"]:
        check(f"party {party.get('party_id')!r}", party, PARTY_KEYS)
    for anchor in view["trust_anchors"]:
        check(f"trust anchor {anchor.get('alias')!r}", anchor, ANCHOR_KEYS)


def build_trust_view(gates, trust_anchors, now, source_revision="unknown"):
    """The Turvis trust view (SPEC section 7.2): an allowlist projection of the gate registry.

    A gate without `eDeliveryCert` is left out (edelivery leaves it out of the AS4 party map too, so
    both sides deny it). `DISABLED` gates with a certificate stay in. `generation` is the SHA-256 of
    the canonical `parties` + `trust_anchors`; the source revision is informational only.
    """
    anchors_by_alias = {a["alias"].lower(): a["alias"] for a in trust_anchors}
    parties = [build_party(g, anchors_by_alias, now) for g in sorted(gates, key=lambda g: g["id"].lower()) if g.get("eDeliveryCert")]

    identities = [p["verified_identity"].lower() for p in parties]
    duplicates = sorted({i for i in identities if identities.count(i) > 1})
    if duplicates:
        raise RegistryError(f"turvis-trust.json: verified_identity is duplicated: {duplicates}")

    anchors = [{"alias": a["alias"], "cert_pem": a["cert_pem"]} for a in sorted(trust_anchors, key=lambda a: a["alias"].lower())]
    canonical = json.dumps({"parties": parties, "trust_anchors": anchors}, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    view = {
        "schema_version": TRUST_SCHEMA_VERSION,
        "generation": "sha256:" + hashlib.sha256(canonical.encode()).hexdigest(),
        "source_revision": source_revision,
        "trust_anchors": anchors,
        "parties": parties,
    }
    assert_allowlist(view)
    return view


def public_entity(kind, entity):
    """The entity as the registry documents carry it, without keys only the trust view uses."""
    hidden = TRUST_ONLY_KEYS.get(kind, ())
    return {k: v for k, v in entity.items() if k not in hidden}


def write(out_dir, kind, entities):
    out = out_dir / kind
    out.mkdir(parents=True, exist_ok=True)
    entities = [public_entity(kind, e) for e in entities]
    document = json.dumps(entities, indent=2, ensure_ascii=False) + "\n"
    (out_dir / f"{kind}.json").write_text(document)
    for entity in entities:
        (out / f"{entity['id'].lower()}.json").write_text(json.dumps(entity, indent=2, ensure_ascii=False) + "\n")
    return len(entities)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--source", default="registry", type=Path, help="registry source directory (default: registry)")
    parser.add_argument("--out", type=Path, help="output directory; omit to validate only")
    parser.add_argument("--trust-out", type=Path, help="output directory for turvis-trust.json (the Turvis trust view)")
    parser.add_argument(
        "--source-revision",
        default=os.environ.get("SOURCE_REVISION", "unknown"),
        help="git revision the artifacts are built from, informational (default: $SOURCE_REVISION or 'unknown')",
    )
    args = parser.parse_args()

    if not args.source.is_dir():
        print(f"registry-to-json: FAILED: {args.source} is not a directory", file=sys.stderr)
        return 1

    now = datetime.now(timezone.utc)
    try:
        registries = {kind: load(kind, args.source) for kind in REGISTRIES}
        trust_anchors = load_trust_anchors(args.source, now)
        view = build_trust_view(registries["gates"], trust_anchors, now, args.source_revision)
    except RegistryError as e:
        print(f"registry-to-json: FAILED: {e}", file=sys.stderr)
        return 1

    for warning in shared_certificate_warnings(view["parties"]):
        print(f"registry-to-json: WARNING: {warning}", file=sys.stderr)

    counts = {kind: len(entities) for kind, entities in registries.items()}
    trust_summary = f"turvis-trust parties={len(view['parties'])} trustAnchors={len(view['trust_anchors'])} generation={view['generation']}"
    if args.out:
        for kind, entities in registries.items():
            write(args.out, kind, entities)
        (args.out / "health.json").write_text(
            json.dumps({"status": "ok", **counts, "trustViewGeneration": view["generation"]}, indent=2) + "\n"
        )
        print("registry-to-json: wrote " + ", ".join(f"{kind}={n}" for kind, n in counts.items()) + f" to {args.out}")
    else:
        print("registry-to-json: validated " + ", ".join(f"{kind}={n}" for kind, n in counts.items()))
    if args.trust_out:
        args.trust_out.mkdir(parents=True, exist_ok=True)
        (args.trust_out / "turvis-trust.json").write_text(json.dumps(view, indent=2, ensure_ascii=False) + "\n")
        (args.trust_out / "health.json").write_text(
            json.dumps({"status": "ok", "generation": view["generation"], "parties": len(view["parties"]), "trustAnchors": len(view["trust_anchors"])}, indent=2) + "\n"
        )
        print(f"registry-to-json: wrote {trust_summary} to {args.trust_out}")
    else:
        print(f"registry-to-json: validated {trust_summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
