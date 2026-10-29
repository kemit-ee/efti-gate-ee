#!/usr/bin/env python3
"""efti-specific Ruuter DSL input-contract check.

The generic DSL checks (YAML parse, unknown step keys, dangling `next:`, empty DSL,
unresolved `[#constant]`, missing template targets, reachability) are done by Ruuter's own
`dsl-lint` (see `docker/dsl-tools/Dockerfile`, run in the `dsl-validate` CI job). This script
only carries the project convention `dsl-lint` doesn't know about:

  every converted route declares its inputs and enforces the required ones.

Mock DSLs (`/mock-platform/`, `/mock/`) are excluded — deliberate stubs.

Usage:
    python3 scripts/validate-dsl.py            # from the repository root
    python3 scripts/validate-dsl.py --root .

Exit 0 = clean, 1 = at least one failure.

------------------------------------------------------------------------------------------
Input contract
------------------------------------------------------------------------------------------
On `turnerrainer/ruuter:0.9.12-rc` (issue turnerrainer/Ruuter#75):

- `declaration.allowlist.body` structured entries: `required: true` → missing field is a
  `400`; `required: false` (or unset) → optional. Body `type:` is wire-enforced (`400` on
  mismatch). `additive: true` keeps undeclared fields visible; `strict: true` rejects them.
- `allowlist.headers` / `.params`: filter/OpenAPI only (presence not wire-enforced), and the
  guard chain now runs on the *raw* request so a route allowlist can't strip a header its
  guard reads.
- `allowlist.required_one_of` per section: OR-of-alternatives; also works on guards.
- Legacy flat `allowed_body: [...]`: every listed field required (no metadata slot).

A `validate_input:` / `check_input:` switch step is still the way to return a *domain* error
code (`MISSING_SUBSET`, `FORBIDDEN_SUBSET`, `BAD_REQUEST_GENERAL` with a helpful `detail`)
rather than the engine's generic `{"error": "Field missing: X"}`.

This check, for every non-guard route file under CONVERTED_PREFIXES (minus the health probes):
  a. a `declaration:` with a non-empty `description`;
  b. if it reads `incoming.body`, the body contract is enforced somehow — a
     `validate_input` / `check_input` step, or `allowed_body` / `allowlist.body`;
  c. no orphan declared body field — every one is read in the file.

Every ReSql file is also checked to be non-empty and to carry a header comment that parses as
YAML: ReSql parses each declaration at startup and refuses to start on the first invalid one
(a stray `word: ` inside a multi-line `description` is enough).
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import sys

import yaml

RUUTER_GLOBS = ("DSL/Ruuter/**/*.yml", "DSL/Ruuter-xroad/**/*.yml")
SQL_DIRS = ("DSL/Resql", "DSL/Liquibase")

# Projects whose routes must satisfy the input-contract check.
CONVERTED_PREFIXES: tuple[str, ...] = (
    "DSL/Ruuter-xroad/xroad/",
    "DSL/Ruuter/efti/",
    "DSL/Ruuter/admin/",
    "DSL/Ruuter/auth/",
    "DSL/Ruuter/platforms/",
    "DSL/Ruuter/ops/",
    "DSL/Ruuter/services/",
)

# Route files that legitimately take no request input.
INPUT_EXEMPT_SUFFIXES = ("/health/live.yml", "/health/ready.yml")


def is_mock(path: str) -> bool:
    return "/mock-platform/" in path or "/mock/" in path


def is_guard(path: str) -> bool:
    return os.path.basename(path) == ".guard.yml"


def ruuter_files() -> list[str]:
    return sorted(p for g in RUUTER_GLOBS for p in glob.glob(g, recursive=True) if not is_mock(p))


def _declared_body_fields(decl: dict) -> set[str]:
    fields: set[str] = set()
    allowed = decl.get("allowed_body")
    if isinstance(allowed, list):
        fields |= {f for f in allowed if isinstance(f, str) and f != "xml"}
    allowlist = decl.get("allowlist")
    if isinstance(allowlist, dict) and isinstance(allowlist.get("body"), list):
        for entry in allowlist["body"]:
            if isinstance(entry, dict) and isinstance(entry.get("field"), str):
                fields.add(entry["field"])
    return fields


def check_input_contract(paths: list[str]) -> list[str]:
    errors: list[str] = []
    for path in paths:
        if is_guard(path) or not any(path.startswith(p) for p in CONVERTED_PREFIXES):
            continue
        if any(path.endswith(s) for s in INPUT_EXEMPT_SUFFIXES):
            continue

        raw = open(path, encoding="utf-8").read()
        try:
            data = yaml.safe_load(raw)
        except yaml.YAMLError as exc:
            errors.append(f"{path}: YAML parse error: {exc}")
            continue
        if not isinstance(data, dict):
            errors.append(f"{path}: not a mapping")
            continue

        decl = data.get("declaration") if isinstance(data.get("declaration"), dict) else {}
        if not str(decl.get("description", "")).strip():
            errors.append(f"{path}: no declaration.description")

        reads_body = "incoming.body" in raw
        step_names = {k for k in data if k != "declaration"}
        has_validation = any(re.search(r"validate_input|check_input", n) for n in step_names)
        allowlist = decl.get("allowlist") if isinstance(decl.get("allowlist"), dict) else {}
        engine_enforced = bool(decl.get("allowed_body")) or bool(allowlist.get("body"))
        if reads_body and not has_validation and not engine_enforced:
            errors.append(
                f"{path}: reads incoming.body but the body contract is unenforced "
                "(no validate_input / check_input step and no allowed_body / allowlist.body)"
            )

        for field in sorted(_declared_body_fields(decl)):
            if not re.search(rf"incoming\.body(\??\.|\[['\"]){re.escape(field)}\b", raw) \
               and f"incoming.body.{field}" not in raw:
                errors.append(f"{path}: declares body field `{field}` but never reads it")
    return errors


def check_sql_non_empty() -> tuple[list[str], int]:
    errors: list[str] = []
    total = 0
    for directory in SQL_DIRS:
        if not os.path.isdir(directory):
            continue
        for path in sorted(glob.glob(os.path.join(directory, "**", "*.sql"), recursive=True)):
            total += 1
            if os.path.getsize(path) == 0:
                errors.append(f"{path}: empty SQL file")
                continue
            header = re.match(r"\s*/\*(.*?)\*/", open(path, encoding="utf-8").read(), re.S)
            if header is None:
                continue
            try:
                yaml.safe_load(header.group(1))
            except yaml.YAMLError as error:
                errors.append(f"{path}: header is not valid YAML (ReSql refuses to start): {str(error).splitlines()[0]}")
    return errors, total


XROAD_GUARD = "DSL/Ruuter-xroad/xroad/.guard.yml"
XROAD_GUARD_COPY = "DSL/Ruuter-xroad/xroad/GET/provide/v1/.guard.yml"


def check_xroad_guard_copy() -> list[str]:
    # GET/provide/.guard.yml is public (override_ancestors) for the OpenAPI route, which also drops the
    # project guard for GET/provide/v1/**; the copy restores it, so the two must not drift.
    def body(path: str) -> str:
        text = open(path, encoding="utf-8").read()
        return text[text.index("\ndeclaration:"):].replace("  override_ancestors: true\n", "")

    for path in (XROAD_GUARD, XROAD_GUARD_COPY):
        if not os.path.isfile(path):
            return [f"{path}: missing"]
    if body(XROAD_GUARD) != body(XROAD_GUARD_COPY):
        return [f"{XROAD_GUARD_COPY}: differs from {XROAD_GUARD} beyond override_ancestors; the authenticated GET routes would run a different guard"]
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description="efti Ruuter DSL input-contract check")
    parser.add_argument("--root", default=".", help="Repository root (default: current directory)")
    args = parser.parse_args()
    os.chdir(args.root)

    paths = ruuter_files()
    if not paths:
        print("FAIL  no Ruuter DSL files found — is --root the repository root?")
        return 1

    contract_errors = check_input_contract(paths) + check_xroad_guard_copy()
    sql_errors, sql_total = check_sql_non_empty()

    failed = False
    if contract_errors:
        failed = True
        print(f"FAIL  input contract ({len(contract_errors)})")
        for error in contract_errors:
            print(f"        {error}")
    else:
        print(f"OK    input contract satisfied for {', '.join(CONVERTED_PREFIXES)}")

    if sql_errors:
        failed = True
        print(f"FAIL  SQL files ({len(sql_errors)})")
        for error in sql_errors:
            print(f"        {error}")
    else:
        print(f"OK    {sql_total} SQL files non-empty, headers valid YAML")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
