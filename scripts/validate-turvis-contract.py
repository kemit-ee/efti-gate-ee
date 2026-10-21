#!/usr/bin/env python3
"""Detect drift between docs/specs/turvis/SPEC.md and the Turvis mock DSL (issue #306).

SPEC section 4 holds the JSON Schemas of the four Turvis endpoints, section 5 the reason-code table;
DSL/Ruuter-turvis-mock/turvis/POST/v1/** implements them and DSL-turvis-mock-tests/ exercises them.
Ruuter's `allowlist` cannot express every schema detail, so this script checks what it can, and
fails (exit 1, one line per finding) when SPEC and DSL are edited separately:

  1. request: the SPEC `required` fields and the DSL `allowlist.body` fields marked `required: true`
     are the same set; every SPEC property is declared in the allowlist with the same JSON type,
     and the allowlist declares nothing the SPEC does not have;
  2. response: the properties of the SPEC accept response and the DSL `returns:` fields are the same set;
  3. reason codes: every code the SPEC section 5 table assigns to an endpoint (or to `all`) appears in
     that route as a reject step with the SPEC's HTTP status, and has a `reason code - ...` scenario
     with that status in DSL-turvis-mock-tests/;
  4. every mock override verdict in a route header comment has a dispatch condition (no dead verdict).

Requires PyYAML. Run from the repository root:  python3 scripts/validate-turvis-contract.py
"""

import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "docs" / "specs" / "turvis" / "SPEC.md"
DSL = ROOT / "DSL" / "Ruuter-turvis-mock" / "turvis" / "POST" / "v1"
TESTS = ROOT / "DSL-turvis-mock-tests"

# SPEC endpoint path -> (DSL file, label used in the `reason code - <label> ...` scenario names, section 5 name)
ROUTES = {
    "/turvis/v1/verifications/xmldsig": ("verifications/xmldsig.yml", "verify", "verifications"),
    "/turvis/v1/signatures/xmldsig": ("signatures/xmldsig.yml", "sign", "signatures"),
    "/turvis/v1/encryptions/xmlenc": ("encryptions/xmlenc.yml", "encrypt", "encryptions"),
    "/turvis/v1/decryptions/xmlenc": ("decryptions/xmlenc.yml", "decrypt", "decryptions"),
}

JSON_TYPES = {"string": "string", "array": "array", "object": "object", "number": "number", "integer": "number", "boolean": "boolean"}


def spec_sections(text):
    """endpoint path -> section text of its `### 4.n` heading."""
    out = {}
    pattern = re.compile(r"^### 4\.\d+ `POST (/turvis/v1/[a-z]+/[a-z]+)`\n(.*?)(?=^### |^---$|\Z)", re.S | re.M)
    for match in pattern.finditer(text):
        out[match.group(1)] = match.group(2)
    return out


def schema_after(section, heading):
    """First ```json block after the given `####` heading, parsed."""
    start = section.index(heading)
    block = re.search(r"```json\n(.*?)```", section[start:], re.S)
    if not block:
        raise ValueError(f"no json block after {heading!r}")
    return json.loads(block.group(1))


def reason_code_rows(text):
    """section 5 table rows: (code, status, endpoints)."""
    start = text.index("## 5. Reason code enum")
    end = text.index("## 6.", start)
    rows = []
    for line in text[start:end].splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 4 and re.fullmatch(r"`[A-Z_]+`", cells[0]):
            rows.append((cells[0].strip("`"), int(cells[1]), [e.strip() for e in cells[2].split(",")]))
    return rows


def dsl_reject_steps(doc):
    """(reason_code, status) of every step of a route that returns a reject."""
    found = set()
    for step in doc.values():
        if isinstance(step, dict) and isinstance(step.get("return"), dict) and "reason_code" in step["return"]:
            found.add((step["return"]["reason_code"], step["return"]["status"]))
    return found


def main():
    findings = []
    spec_text = SPEC.read_text()
    sections = spec_sections(spec_text)
    rows = reason_code_rows(spec_text)

    scenarios = []
    for path in sorted(TESTS.glob("*.test.yml")):
        scenarios += yaml.safe_load(path.read_text())["tests"]
    scenario_names = [s["name"] for s in scenarios]

    for endpoint, (dsl_file, label, section_name) in ROUTES.items():
        where = f"{endpoint} ({dsl_file})"
        if endpoint not in sections:
            findings.append(f"{where}: no `### 4.n` section in SPEC.md")
            continue
        doc = yaml.safe_load((DSL / dsl_file).read_text())
        declaration = doc["declaration"]

        request = schema_after(sections[endpoint], "#### Request body schema")
        allow = {f["field"]: f for f in declaration["allowlist"]["body"]}
        spec_required = set(request.get("required", []))
        dsl_required = {name for name, f in allow.items() if f.get("required")}
        if spec_required != dsl_required:
            findings.append(f"{where}: required request fields differ, SPEC {sorted(spec_required)} vs DSL {sorted(dsl_required)}")
        for name, prop in request["properties"].items():
            if name not in allow:
                findings.append(f"{where}: SPEC request field {name!r} is not in the DSL allowlist")
            elif JSON_TYPES.get(prop.get("type")) != allow[name].get("type"):
                findings.append(f"{where}: request field {name!r} type differs, SPEC {prop.get('type')} vs DSL {allow[name].get('type')}")
        for name in allow:
            if name not in request["properties"]:
                findings.append(f"{where}: DSL allowlist field {name!r} is not in the SPEC request schema")

        response = schema_after(sections[endpoint], "#### Response — accept (200 OK)")
        spec_props = set(response["properties"])
        dsl_returns = {f["field"] for f in declaration.get("returns", [])}
        if spec_props != dsl_returns:
            findings.append(f"{where}: accept response fields differ, SPEC {sorted(spec_props)} vs DSL returns {sorted(dsl_returns)}")

        implemented = dsl_reject_steps(doc)
        for code, status, endpoints in rows:
            if "all" not in endpoints and section_name not in endpoints:
                continue
            if (code, status) not in implemented:
                findings.append(f"{where}: reason code {code} ({status}) has no reject step with that status")
            if not any(n.startswith(f"reason code - {label} ") and n.endswith(f"-> {status} {code}") for n in scenario_names):
                findings.append(f"{where}: reason code {code} ({status}) has no `reason code - {label} ... -> {status} {code}` scenario")

        header = (DSL / dsl_file).read_text().split("declaration:", 1)[0]
        dispatch = json.dumps(doc["dispatch"])
        for verdict in sorted(set(re.findall(r"`(reject-[a-z-]+)`", header))):
            if f"'{verdict}'" not in dispatch:
                findings.append(f"{where}: header documents override verdict {verdict} but dispatch has no condition for it")

    for finding in findings:
        print(f"validate-turvis-contract: {finding}", file=sys.stderr)
    if findings:
        print(f"validate-turvis-contract: FAILED, {len(findings)} finding(s)", file=sys.stderr)
        return 1
    print(f"validate-turvis-contract: SPEC and DSL agree ({len(ROUTES)} routes, {len(rows)} reason codes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
