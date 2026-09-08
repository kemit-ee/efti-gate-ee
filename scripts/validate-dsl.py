#!/usr/bin/env python3
"""Static validation of the Ruuter DSL and the SQL files.

Sonar does not understand the Ruuter DSL, so this is the only check that catches a broken
flow before the image is built. Checks:

  1. every Ruuter YAML file parses;
  2. no `next` reference dangles;
  3. the declaration block is one Rust Ruuter (0.9.x-rc) will accept;
  4. no SQL file is empty;
  5. INPUT CONTRACT (see docs below) — every converted route declares its inputs and
     enforces the required ones with a `validate_input` / `check_input` step.

(A prior port also flagged unreachable steps and assign-order hazards; both are noisy
against this repo's switch-fall-through style and were dropped — add back deliberately.)

Mock DSL files (any path containing `/mock-platform/` or `/mock/`) are excluded: deliberate stubs.

Usage:
    python3 scripts/validate-dsl.py            # from the repository root
    python3 scripts/validate-dsl.py --root .

Exit code 0 = all checks passed, 1 = at least one failure.

------------------------------------------------------------------------------------------
Input contract (check 6)
------------------------------------------------------------------------------------------
Empirically, `turnerrainer/ruuter:0.9.10-rc` treats a present `declaration.allowlist` block
(any of body/header/params, and the flat `declaration.allowed_body`) as STRICT: every listed
field is MANDATORY (missing -> HTTP 500 in a synthetic `declare` step, before any DSL step;
`required: false` is ignored), and any field NOT listed is silently stripped. Header/param
stripping happens before the guard runs, so an `allowlist.header` omitting a header the guard
reads breaks the guard. No "optional field", no type enforcement.

So the real, well-shaped enforcement lives in a `validate_input:` (or `check_input:`) switch
step returning `400 BAD_REQUEST_GENERAL` / `MISSING_REQUIRED_HEADER` (see docs/specs/errors.json).
`allowlist.body` is used only on routes where every body field is always required.

This check applies to route files under CONVERTED_PREFIXES (grown one project per PR). For
those files it requires:
  a. a `declaration:` block with a non-empty `description`;
  b. if the file reads `incoming.body` at all, a step named `validate_input` or `check_input`;
  c. no orphan `allowlist`/`allowed_body` entry — every declared body field is read in the file.
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import sys

import yaml

RUUTER_GLOB = "DSL/Ruuter/**/*.yml"
SQL_DIRS = ("DSL/Resql", "DSL/Liquibase")

# Projects whose routes must satisfy the input-contract check (check 6). Extend one entry
# per conversion PR until every project is listed.
CONVERTED_PREFIXES: tuple[str, ...] = (
    "DSL/Ruuter/xroad/",
)

# Route files that legitimately take no request input.
INPUT_EXEMPT_SUFFIXES = ("/health/live.yml", "/health/ready.yml")


def is_mock(path: str) -> bool:
    return "/mock-platform/" in path or "/mock/" in path


def ruuter_files() -> list[str]:
    return sorted(p for p in glob.glob(RUUTER_GLOB, recursive=True) if not is_mock(p))


def is_guard(path: str) -> bool:
    return os.path.basename(path) == ".guard.yml"


def collect_next_refs(obj) -> set[str]:
    refs: set[str] = set()
    if isinstance(obj, dict):
        for key, value in obj.items():
            if key == "next" and isinstance(value, str):
                refs.add(value)
            else:
                refs |= collect_next_refs(value)
    elif isinstance(obj, list):
        for item in obj:
            refs |= collect_next_refs(item)
    return refs




def check_parses(paths: list[str]) -> tuple[list[str], dict[str, dict]]:
    errors: list[str] = []
    parsed: dict[str, dict] = {}
    for path in paths:
        try:
            with open(path, encoding="utf-8") as handle:
                data = yaml.safe_load(handle)
        except yaml.YAMLError as exc:
            errors.append(f"{path}: {exc}")
            continue
        if isinstance(data, dict):
            parsed[path] = data
    return errors, parsed


def check_flow(parsed: dict[str, dict]) -> list[str]:
    """Every `next:` target names a real step in the same file (or `end`)."""
    errors: list[str] = []
    for path, data in parsed.items():
        steps = {k: v for k, v in data.items() if k != "declaration"}
        all_refs: set[str] = set()
        for body in steps.values():
            all_refs |= collect_next_refs(body) - {"end"}
        dangling = sorted(r for r in all_refs if r not in steps)
        if dangling:
            errors.append(f"{path}: dangling next references: {dangling}")
    return errors


def check_declaration(parsed: dict[str, dict]) -> list[str]:
    """The declaration block as Rust Ruuter (0.9.x-rc) will accept it.

    `version` must be a string (1.0 unquoted is a YAML float and the field is typed).
    `call:` is only valid as `call: declare` — anything else is read as a step and the
    container fails to boot.
    """
    errors: list[str] = []
    for path, data in parsed.items():
        decl = data.get("declaration")
        if not isinstance(decl, dict):
            continue
        call = decl.get("call")
        if call is not None and call != "declare":
            errors.append(f"{path}: declaration has `call: {call}` — only `call: declare` is valid")
        version = decl.get("version")
        if version is not None and not isinstance(version, str):
            errors.append(f"{path}: declaration version {version!r} is not a string — quote it")
    return errors


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


def check_input_contract(parsed: dict[str, dict]) -> list[str]:
    errors: list[str] = []
    for path, data in parsed.items():
        if is_guard(path) or not any(path.startswith(p) for p in CONVERTED_PREFIXES):
            continue
        if any(path.endswith(s) for s in INPUT_EXEMPT_SUFFIXES):
            continue

        raw = open(path, encoding="utf-8").read()
        decl = data.get("declaration")

        if not isinstance(decl, dict) or not str(decl.get("description", "")).strip():
            errors.append(f"{path}: no declaration.description")

        reads_body = "incoming.body" in raw
        step_names = {k for k in data if k != "declaration"}
        has_validation = any(re.search(r"validate_input|check_input", n) for n in step_names)
        if reads_body and not has_validation:
            errors.append(f"{path}: reads incoming.body but has no `validate_input` / `check_input` step")

        for field in sorted(_declared_body_fields(decl if isinstance(decl, dict) else {})):
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
    return errors, total


def main() -> int:
    parser = argparse.ArgumentParser(description="Static Ruuter DSL / SQL validation")
    parser.add_argument("--root", default=".", help="Repository root (default: current directory)")
    args = parser.parse_args()
    os.chdir(args.root)

    paths = ruuter_files()
    if not paths:
        print("FAIL  no Ruuter DSL files found — is --root the repository root?")
        return 1

    parse_errors, parsed = check_parses(paths)
    ok = not parse_errors
    flow_errors = check_flow(parsed) if ok else []
    decl_errors = check_declaration(parsed) if ok else []
    contract_errors = check_input_contract(parsed) if ok else []
    sql_errors, sql_total = check_sql_non_empty()

    failed = False

    def report(errs, fail_label, ok_label):
        nonlocal failed
        if errs:
            failed = True
            print(f"FAIL  {fail_label} ({len(errs)})")
            for error in errs:
                print(f"        {error}")
        elif ok:
            print(f"OK    {ok_label}")

    if parse_errors:
        failed = True
        print(f"FAIL  Ruuter YAML parse ({len(parse_errors)})")
        for error in parse_errors:
            print(f"        {error}")
    else:
        print(f"OK    {len(paths)} Ruuter YAML files parse (mock files excluded)")

    report(flow_errors, "Ruuter DSL flow", f"{len(parsed)} flows: no dangling next references")
    report(decl_errors, "declaration block not Rust Ruuter compatible",
           f"{len(parsed)} declaration blocks: call is declare-only, version is a string")
    report(contract_errors, "input contract",
           f"input contract satisfied for {', '.join(CONVERTED_PREFIXES)}")

    if sql_errors:
        failed = True
        print(f"FAIL  empty SQL files ({len(sql_errors)})")
        for error in sql_errors:
            print(f"        {error}")
    else:
        print(f"OK    {sql_total} SQL files non-empty")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
