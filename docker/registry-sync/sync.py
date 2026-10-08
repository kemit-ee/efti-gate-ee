#!/usr/bin/env python3
"""ADR-014 — apply the git registry folder to the database, then verify it.

Reads `registry/{gates,platforms,authorities}/*.json` (mounted read-only), validates every file,
posts each registry as one batch to ReSql, refreshes the admin read models, and reads the three
registries back to confirm the database matches the folder.

Any problem — unreadable folder, invalid file, ReSql error, mismatched read-back — exits non-zero.
Compose starts `ruuter` and `edelivery` with
`depends_on: registry-sync: condition: service_completed_successfully`, so the stack refuses to
start with a half-applied or unverifiable registry rather than serving traffic from it.
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REGISTRY_DIR = Path(os.environ.get("REGISTRY_DIR", "/registry"))
RESQL_URL = os.environ.get("RESQL_URL", "http://resql:8090").rstrip("/")
TIMEOUT_SECONDS = float(os.environ.get("REGISTRY_SYNC_TIMEOUT", "30"))
ATTEMPTS = int(os.environ.get("REGISTRY_SYNC_ATTEMPTS", "10"))

COUNTRY_CODE_RE = re.compile(r"^[A-Z]{2}$")
SUBSET_RE = re.compile(r"^EU0[1-7]$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
PEM_RE = re.compile(r"^-----BEGIN CERTIFICATE-----\n(?:[A-Za-z0-9+/=]+\n)+-----END CERTIFICATE-----\n?$")


class RegistryError(Exception):
    """Anything that must stop the stack from starting."""


def check_gate(entity, source):
    if not COUNTRY_CODE_RE.match(entity["countryCode"]):
        raise RegistryError(f"{source}: countryCode must be two upper-case letters, got {entity['countryCode']!r}")
    for field in ("eDeliveryCert", "tlsCert"):
        value = entity.get(field)
        if value is not None and not PEM_RE.match(value):
            raise RegistryError(f"{source}: {field} is not a PEM certificate (expected -----BEGIN/END CERTIFICATE-----)")


def check_platform(entity, source):
    if not isinstance(entity.get("headers", {}), dict):
        raise RegistryError(f"{source}: headers must be a JSON object")
    for field in ("eDeliveryCert", "tlsCert"):
        value = entity.get(field)
        if value is not None and not PEM_RE.match(value):
            raise RegistryError(f"{source}: {field} is not a PEM certificate (expected -----BEGIN/END CERTIFICATE-----)")
    key_hash = entity.get("apiKeyHash")
    if key_hash is not None and not SHA256_RE.match(key_hash):
        raise RegistryError(
            f"{source}: apiKeyHash must be the lower-case hex SHA-256 of the platform's X-Api-Key "
            f"(64 chars), got {key_hash!r}"
        )


def check_authority(entity, source):
    subsets = entity.get("subsets")
    if not isinstance(subsets, list) or not all(isinstance(s, str) and SUBSET_RE.match(s) for s in subsets):
        raise RegistryError(f"{source}: subsets must be a list of EU01..EU07 codes, got {subsets!r}")


REGISTRIES = (
    {
        "kind": "gates",
        "directory": "gates",
        "required": ("id", "countryCode", "eDeliveryUrl", "status"),
        "optional": ("eDeliveryCert", "tlsCert"),
        "statuses": ("ONLINE", "DISABLED"),
        "check": check_gate,
    },
    {
        "kind": "platforms",
        "directory": "platforms",
        "required": ("id", "baseUrl", "status"),
        "optional": ("headers", "eDeliveryCert", "tlsCert", "apiKeyHash"),
        "statuses": ("ONLINE", "DISABLED"),
        "check": check_platform,
    },
    {
        "kind": "authorities",
        "directory": "authorities",
        "required": ("id", "name", "registryCode", "subsets", "status"),
        "optional": (),
        "statuses": ("ACTIVE", "DELETED"),
        "check": check_authority,
    },
)


def validate(registry, source, entity):
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

    if entity["status"] not in registry["statuses"]:
        raise RegistryError(f"{source}: status must be one of {list(registry['statuses'])}, got {entity['status']!r}")

    registry["check"](entity, source)

    # Fill the optional fields so the database sees an explicit NULL rather than a missing key.
    return {f: entity.get(f) for f in known}


def load(registry):
    directory = REGISTRY_DIR / registry["directory"]
    if not directory.is_dir():
        raise RegistryError(
            f"{directory} is missing — the registry folder must be mounted read-only at {REGISTRY_DIR}"
        )

    entities = [validate(registry, path, json.loads(_read(path))) for path in sorted(directory.glob("*.json"))]
    ids = [e["id"] for e in entities]
    if len(ids) != len(set(ids)):
        raise RegistryError(f"{directory}: duplicate id(s) {sorted({i for i in ids if ids.count(i) > 1})}")
    return entities


def _read(path):
    try:
        return path.read_text()
    except (OSError, UnicodeDecodeError) as e:
        raise RegistryError(f"{path}: cannot read: {e}") from e


def post(endpoint, payload):
    url = f"{RESQL_URL}/efti/{endpoint}"
    request = urllib.request.Request(
        url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            body = response.read()
    except urllib.error.HTTPError as e:
        raise RegistryError(
            f"POST {url} failed: {e.code} {e.read().decode(errors='replace')} "
            f"(resql error code {e.headers.get('X-Resql-Error-Code')!r}, "
            f"message {e.headers.get('X-Resql-Error-Message')!r})"
        ) from e
    except urllib.error.URLError as e:
        raise RegistryError(f"POST {url} failed: {e.reason}") from e
    return json.loads(body) if body else []


def post_with_retry(endpoint, payload):
    """ReSql is health-checked before this container starts, so a refused connection is a startup
    race, not a configuration error — retry briefly, then give up loudly."""
    for attempt in range(1, ATTEMPTS + 1):
        try:
            return post(endpoint, payload)
        except RegistryError as e:
            if attempt == ATTEMPTS:
                raise
            print(f"registry-sync: {e} — retrying ({attempt}/{ATTEMPTS})", flush=True)
            time.sleep(1.0)


def verify(registry, declared):
    expected = {e["id"].lower(): e["status"] for e in declared if e["status"] != "DELETED"}
    rows = post_with_retry(f"get_{registry['kind']}", {"limit": 1000, "offset": 0})
    actual = {row["id"].lower(): row["status"] for row in rows}

    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    wrong = sorted(i for i in set(expected) & set(actual) if expected[i] != actual[i])

    if missing or extra or wrong:
        raise RegistryError(
            f"{registry['kind']}: database does not match {REGISTRY_DIR / registry['directory']} — "
            f"missing={missing} unexpected={extra} wrong_status={wrong}"
        )
    return len(expected)


def main():
    loaded = {}
    for registry in REGISTRIES:
        loaded[registry["kind"]] = load(registry)
        print(f"registry-sync: {registry['directory']}: {len(loaded[registry['kind']])} file(s) validated", flush=True)

    for registry in REGISTRIES:
        kind = registry["kind"]
        entities = loaded[kind]
        [result] = post_with_retry(f"sync_{kind}", {"entities": json.dumps(entities)})
        print(
            f"registry-sync: {kind}: declared={result['declared']} "
            f"upserted={result['upserted']} tombstoned={result['deleted']}",
            flush=True,
        )

    post_with_retry("refresh_registry_lists", {})
    print("registry-sync: admin read models refreshed", flush=True)

    total = 0
    for registry in REGISTRIES:
        total += verify(registry, loaded[registry["kind"]])
    print(f"registry-sync: verified {total} live entit(ies) against the database", flush=True)


if __name__ == "__main__":
    try:
        main()
    except RegistryError as e:
        print(f"registry-sync: FAILED: {e}", file=sys.stderr, flush=True)
        sys.exit(1)
    except json.JSONDecodeError as e:
        print(f"registry-sync: FAILED: invalid JSON: {e}", file=sys.stderr, flush=True)
        sys.exit(1)
