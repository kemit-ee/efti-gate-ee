#!/usr/bin/env python3
"""Fail when a new XML parser site, Turvis call site or xml-mapper caller appears without an allowlist entry (#307).

The perimeter trust model (docs/architecture/security-and-compliance/perimeter-trust-model.md) rests on a short,
known list of places that parse XML or talk to Turvis. A change that adds one has to be seen and reviewed, so it is
listed in scripts/xml-surface-allowlist.txt (kind | path | expected count | reason). The check is a whole-tree grep,
not a taint analysis: it counts matching lines per file and kind, and fails with file:line when

  * a file has a hit of a kind that has no entry (new site),
  * a file has more or fewer hits than its entry says (a site was added or removed there),
  * an entry no longer matches anything (stale audit sheet).

Kinds:
  xml-parser       a JVM XML parser/factory/validator/signature API in code/*/src (tests excluded)
  turvis-call      a call to one of the four TurvisClient operations, or a "/turvis/" URL literal, in code/*/src
  turvis-dsl       a "/turvis/" URL in a Ruuter DSL other than the Turvis mock itself
  xml-mapper-call  a Ruuter DSL that calls xml-mapper (it parses peer-authored business XML, trust-model path 6)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ALLOWLIST = ROOT / "scripts" / "xml-surface-allowlist.txt"

XML_PARSER = re.compile(
    r"\b(?:XmlParser\s*\(|DocumentBuilderFactory|DocumentBuilder\b|SAXParserFactory|SAXParser\b|XMLInputFactory|XMLOutputFactory|XMLReader|"
    r"XMLStreamReader|TransformerFactory|SchemaFactory|SchemaValidator|Unmarshaller|XMLSignatureFactory|XPathFactory)"
)
TURVIS_CALL = re.compile(r"\.(?:decrypt|verify|encrypt|sign)\(|[\"']/turvis/")
TURVIS_URL = re.compile(r"/turvis/")
XML_MAPPER = re.compile(r"XML_MAPPER")


def rel(path, root):
    return path.relative_to(root).as_posix()


def code_files(root):
    for path in sorted((root / "code").glob("*/src/**/*.kt")):
        yield path


def scan(root):
    """Return {(kind, relative path): [line numbers]}."""
    hits = {}

    def add(kind, path, pattern, skip_imports=False):
        for number, line in enumerate(path.read_text().split("\n"), 1):
            if skip_imports and line.lstrip().startswith("import "):
                continue
            if pattern.search(line):
                hits.setdefault((kind, rel(path, root)), []).append(number)

    for path in code_files(root):
        add("xml-parser", path, XML_PARSER, skip_imports=True)
        text = path.read_text()
        if "TurvisClient" in text:
            add("turvis-call", path, TURVIS_CALL)
        elif '"/turvis/' in text:
            add("turvis-call", path, TURVIS_CALL)
    for dsl in sorted(root.glob("DSL/Ruuter*/**/*.yml")):
        if "Ruuter-turvis-mock" in dsl.parts or any(p == "Ruuter-turvis-mock" for p in dsl.parts):
            continue
        add("turvis-dsl", dsl, TURVIS_URL)
        add("xml-mapper-call", dsl, XML_MAPPER)
    return hits


def load_allowlist(path):
    entries = {}
    for number, raw in enumerate(path.read_text().split("\n"), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|", 3)]
        if len(parts) != 4 or not parts[2].isdigit() or not parts[3]:
            raise SystemExit(f"{rel(path, ROOT) if path.is_relative_to(ROOT) else path}:{number}: expected 'kind | path | count | reason'")
        entries[(parts[0], parts[1])] = (int(parts[2]), parts[3], number)
    return entries


def check(root, allowlist_path):
    hits = scan(root)
    entries = load_allowlist(allowlist_path)
    problems = []
    for key, lines in sorted(hits.items()):
        kind, path = key
        if key not in entries:
            problems.append(f"{path}:{lines[0]}: new {kind} site without an allowlist entry ({len(lines)} hit(s), lines {lines})")
        elif entries[key][0] != len(lines):
            problems.append(f"{path}:{lines[0]}: {kind} hits changed from {entries[key][0]} to {len(lines)} (lines {lines}); review and update the allowlist")
    for key, (count, _reason, number) in sorted(entries.items()):
        if key not in hits:
            problems.append(f"{allowlist_path.name}:{number}: stale entry, no {key[0]} hit in {key[1]}")
    return problems


def main():
    if "--print" in sys.argv:
        for (kind, path), lines in sorted(scan(ROOT).items()):
            print(f"{kind} | {path} | {len(lines)} | lines {lines}")
        return 0
    problems = check(ROOT, ALLOWLIST)
    if problems:
        print("XML surface changed without an allowlist entry (scripts/xml-surface-allowlist.txt, docs/architecture/"
              "security-and-compliance/perimeter-trust-model.md):", *problems, sep="\n  ", file=sys.stderr)
        return 1
    print(f"validate-xml-surface: {len(scan(ROOT))} allowlisted site(s), no new parser, Turvis or xml-mapper site")
    return 0


if __name__ == "__main__":
    sys.exit(main())
