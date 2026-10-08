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
    <out>/health.json             {status, gates, platforms, authorities}

It is also the **validator**: a malformed file stops the build (and therefore the deployment), so
nothing unvalidated can ever be served. Run it with no `--out` in CI to validate without writing.

Two things worth knowing:

* **Absent optional keys are omitted** from the output, rather than written as `null`. Empty
  collections are *not* absent — `subsets: []` means "entitled to nothing" and stays.
* **Per-entity files are named with the lower-cased id.** A static file server is case-sensitive,
  but ids were case-insensitive when they lived in a CITEXT column, so consumers lower-case the id
  before requesting. The canonical `id` is still what the documents and the arrays contain.

Requires PyYAML.
"""

import argparse
import json
import re
import sys
from pathlib import Path

COUNTRY_CODE_RE = re.compile(r"^[A-Z]{2}$")
SUBSET_RE = re.compile(r"^EU0[1-7]$")
PEM_RE = re.compile(r"^-----BEGIN CERTIFICATE-----\n(?:[A-Za-z0-9+/=]+\n)+-----END CERTIFICATE-----\n?$")


class RegistryError(Exception):
    """Anything that must fail the build."""


def check_certs(entity, source):
    for field in ("eDeliveryCert", "tlsCert"):
        value = entity.get(field)
        if value is not None and not PEM_RE.match(value):
            raise RegistryError(f"{source}: {field} is not a PEM certificate (expected -----BEGIN/END CERTIFICATE-----)")


def check_gate(entity, source):
    if not COUNTRY_CODE_RE.match(entity["countryCode"]):
        raise RegistryError(f"{source}: countryCode must be two upper-case letters, got {entity['countryCode']!r}")
    check_certs(entity, source)


def check_platform(entity, source):
    if not isinstance(entity.get("headers", {}), dict):
        raise RegistryError(f"{source}: headers must be a mapping")
    check_certs(entity, source)
    api_key = entity.get("apiKey")
    if api_key is not None and (not isinstance(api_key, str) or not api_key):
        raise RegistryError(f"{source}: apiKey must be a non-empty string when present")


def check_authority(entity, source):
    subsets = entity["subsets"]
    if not isinstance(subsets, list) or not all(isinstance(s, str) and SUBSET_RE.match(s) for s in subsets):
        raise RegistryError(f"{source}: subsets must be a list of EU01..EU07 codes, got {subsets!r}")


# Per registry: required fields, optional fields, allowed `status` values (None = the type has no
# status field at all), and the type-specific validation hook.
REGISTRIES = {
    "gates": {
        "required": ("id", "countryCode", "eDeliveryUrl", "status"),
        "optional": ("eDeliveryCert", "tlsCert"),
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

    if entity["id"] != source.stem:
        raise RegistryError(f"{source}: id {entity['id']!r} does not match the file name (must be {source.stem!r})")

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

    ids = [e["id"] for e in entities]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    if duplicates:
        raise RegistryError(f"{directory}: duplicate id(s) {duplicates}")
    return entities


def write(out_dir, kind, entities):
    out = out_dir / kind
    out.mkdir(parents=True, exist_ok=True)
    document = json.dumps(entities, indent=2, ensure_ascii=False) + "\n"
    (out_dir / f"{kind}.json").write_text(document)
    for entity in entities:
        (out / f"{entity['id'].lower()}.json").write_text(json.dumps(entity, indent=2, ensure_ascii=False) + "\n")
    return len(entities)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--source", default="registry", type=Path, help="registry source directory (default: registry)")
    parser.add_argument("--out", type=Path, help="output directory; omit to validate only")
    args = parser.parse_args()

    if not args.source.is_dir():
        print(f"registry-to-json: FAILED: {args.source} is not a directory", file=sys.stderr)
        return 1

    try:
        registries = {kind: load(kind, args.source) for kind in REGISTRIES}
    except RegistryError as e:
        print(f"registry-to-json: FAILED: {e}", file=sys.stderr)
        return 1

    counts = {kind: len(entities) for kind, entities in registries.items()}
    if args.out:
        for kind, entities in registries.items():
            write(args.out, kind, entities)
        (args.out / "health.json").write_text(
            json.dumps({"status": "ok", **counts}, indent=2) + "\n"
        )
        print("registry-to-json: wrote " + ", ".join(f"{kind}={n}" for kind, n in counts.items()) + f" to {args.out}")
    else:
        print("registry-to-json: validated " + ", ".join(f"{kind}={n}" for kind, n in counts.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
