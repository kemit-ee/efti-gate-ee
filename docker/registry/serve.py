#!/usr/bin/env python3
"""The registry service — serves the git registry folder as JSON over HTTP (ADR-015).

Reads `registry/{gates,platforms,authorities}/*.json` (mounted read-only), validates every file, and
serves them two ways:

  GET /gates.json            all gates, as one array   (Ruuter has no loops, so list/by-code
  GET /platforms.json        all platforms, one array   lookups need a whole-registry document)
  GET /authorities.json      all authorities, one array
  GET /gates/<id>.json       one gate, or 404
  GET /platforms/<id>.json   one platform, or 404
  GET /authorities/<id>.json one authority, or 404
  GET /health                liveness + entity counts

It does no filtering, no auth and no lookups: `status` checks, the platform `X-Api-Key` comparison and
the authority registry-code ambiguity rule all live in the Ruuter DSL, which owns the gate's policy.

Registry content is loaded and validated **once at startup**, and a bad file stops the process, so
compose refuses to start the stack on a broken registry. Edit a file, then
`docker compose restart registry` to apply it.

SECURITY: this is an internal-only service. Platform entries carry their live `apiKey` in plaintext
(the ADR-004 "store only a hash" rule was dropped — see ADR-015), so this port must never be exposed
beyond the Compose network, exactly like ReSQL's.
"""

import json
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REGISTRY_DIR = Path(os.environ.get("REGISTRY_DIR", "/registry"))
PORT = int(os.environ.get("REGISTRY_PORT", "8080"))

COUNTRY_CODE_RE = re.compile(r"^[A-Z]{2}$")
SUBSET_RE = re.compile(r"^EU0[1-7]$")
PEM_RE = re.compile(r"^-----BEGIN CERTIFICATE-----\n(?:[A-Za-z0-9+/=]+\n)+-----END CERTIFICATE-----\n?$")


class RegistryError(Exception):
    """Anything that must stop the registry (and therefore the stack) from starting."""


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
        raise RegistryError(f"{source}: headers must be a JSON object")
    check_certs(entity, source)
    api_key = entity.get("apiKey")
    if api_key is not None and (not isinstance(api_key, str) or not api_key):
        raise RegistryError(f"{source}: apiKey must be a non-empty string when present")


def check_authority(entity, source):
    subsets = entity["subsets"]
    if not isinstance(subsets, list) or not all(isinstance(s, str) and SUBSET_RE.match(s) for s in subsets):
        raise RegistryError(f"{source}: subsets must be a list of EU01..EU07 codes, got {subsets!r}")


# Per registry: required fields, optional fields, the allowed `status` values (None = no status
# field; an authority exists or its file does not), and the extra validation hook.
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
        raise RegistryError(f"{source}: must contain a JSON object")

    known = registry["required"] + registry["optional"]
    unknown = sorted(set(entity) - set(known))
    if unknown:
        raise RegistryError(f"{source}: unknown field(s) {unknown}; known fields are {sorted(known)}")

    missing = [f for f in registry["required"] if entity.get(f) in (None, "")]
    if missing:
        raise RegistryError(f"{source}: missing required field(s) {missing}")

    if entity["id"] != source.stem:
        raise RegistryError(f"{source}: id {entity['id']!r} does not match the file name (must be {source.stem!r})")

    if registry["statuses"] is not None and entity["status"] not in registry["statuses"]:
        raise RegistryError(f"{source}: status must be one of {list(registry['statuses'])}, got {entity['status']!r}")

    registry["check"](entity, source)

    # Fill optional fields explicitly so consumers always see the same key set.
    return {f: entity.get(f) for f in known}


def load(kind):
    registry = REGISTRIES[kind]
    directory = REGISTRY_DIR / kind
    if not directory.is_dir():
        raise RegistryError(f"{directory} is missing — the registry folder must be mounted read-only at {REGISTRY_DIR}")

    entities = []
    # Sort by id (the file stem), not by file name: '-' sorts before '.', so ordering by file name
    # would place `auth-xroad-tm-no-eu02.json` before `auth-xroad-tm.json` and break the "ordered by
    # id" contract that list consumers rely on for stable paging.
    for path in sorted(directory.glob("*.json"), key=lambda p: p.stem.lower()):
        try:
            entity = json.loads(path.read_text())
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as e:
            raise RegistryError(f"{path}: cannot read or parse: {e}") from e
        entities.append(validate(kind, registry, path, entity))

    ids = [e["id"] for e in entities]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    if duplicates:
        raise RegistryError(f"{directory}: duplicate id(s) {duplicates}")
    return entities


class Registry:
    """The loaded registry. Ids are matched case-insensitively, as the database's CITEXT columns were."""

    def __init__(self):
        entities = {kind: load(kind) for kind in REGISTRIES}
        self.documents = {kind: json.dumps(items, indent=2).encode() for kind, items in entities.items()}
        self.by_id = {
            kind: {e["id"].lower(): json.dumps(e, indent=2).encode() for e in items}
            for kind, items in entities.items()
        }
        self.counts = {kind: len(items) for kind, items in entities.items()}

    def document(self, kind):
        return self.documents.get(kind)

    def entity(self, kind, entity_id):
        return self.by_id.get(kind, {}).get(entity_id.lower())


class Handler(BaseHTTPRequestHandler):
    registry: Registry = None
    protocol_version = "HTTP/1.1"

    def respond(self, status, body, content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def error(self, status, message):
        self.respond(status, json.dumps({"error": message}).encode())

    def do_GET(self):
        path = self.path.split("?", 1)[0].rstrip("/")
        parts = [p for p in path.split("/") if p]

        if parts == ["health"]:
            return self.respond(200, json.dumps({"status": "ok", **self.registry.counts}).encode())

        if len(parts) == 1 and parts[0].endswith(".json"):
            document = self.registry.document(parts[0][: -len(".json")])
            return self.respond(200, document) if document else self.error(404, f"Unknown registry: {parts[0]}")

        if len(parts) == 2 and parts[0] in REGISTRIES:
            name = parts[1]
            entity = self.registry.entity(parts[0], name[: -len(".json")] if name.endswith(".json") else name)
            return self.respond(200, entity) if entity else self.error(404, f"No such {parts[0][:-1]}: {name}")

        self.error(404, f"Not found: {self.path}")

    def do_HEAD(self):
        self.do_GET()

    def log_message(self, fmt, *args):
        sys.stderr.write("registry: %s\n" % (fmt % args))


def main():
    registry = Registry()
    Handler.registry = registry
    print(
        "registry: loaded " + ", ".join(f"{kind}={n}" for kind, n in registry.counts.items()) + f" from {REGISTRY_DIR}",
        flush=True,
    )
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    try:
        main()
    except RegistryError as e:
        print(f"registry: FAILED: {e}", file=sys.stderr, flush=True)
        sys.exit(1)
