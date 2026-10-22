#!/usr/bin/env python3
"""A tripwire for new XML parser sites, Turvis call sites and xml-mapper callers (#307).

The perimeter trust model (docs/architecture/security-and-compliance/perimeter-trust-model.md) rests on a short, known
list of places that parse XML or talk to Turvis. A change that adds one has to be seen and reviewed, so it is listed in
scripts/xml-surface-allowlist.txt (kind | path | hit count | fingerprint | reason).

This is a grep, not a taint analysis. It catches the common spellings (the type name, an import alias, klite's secure
factories, DI-injected parsers, a `.client.sign(` call without the type name, a `/turvis/` URL, a hard-coded xml-mapper
host) and nothing cleverer (reflection, a parser hidden in a dependency, another language). Code review stays the
control; this makes the common mistake loud. Per file and kind it records the number of hit lines and a fingerprint of
their stripped text, and fails with file:line when

  * a file has a hit of a kind that has no entry (new site),
  * the count or the fingerprint changed (a site was added, removed or changed there, also a one-in-one-out swap),
  * an entry no longer matches anything (stale audit sheet).

A moved line keeps its fingerprint. Kinds:
  xml-parser       a JVM/JS XML parser, factory, validator or signature API in code/ (tests excluded)
  turvis-call      a `.decrypt(`/`.verify(`/`.encrypt(`/`.sign(` call or a `/turvis/v1` URL in code/ (tests excluded)
  turvis-dsl       a "/turvis/v1" URL in a Ruuter DSL other than the Turvis mock itself
  xml-mapper-call  a Ruuter DSL that calls xml-mapper (it parses peer-authored business XML, trust-model path 6)
"""
import hashlib
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ALLOWLIST = ROOT / "scripts" / "xml-surface-allowlist.txt"

# No \b before the factory names: klite's secureDocumentBuilderFactory() must match too.
XML_PARSER = re.compile(
    r"(?:\bXmlParser\b|DocumentBuilderFactory|\bDocumentBuilder\b|SAXParserFactory|\bSAXParser\b|XMLInputFactory|XMLOutputFactory|"
    r"\bXMLReader\b|XMLStreamReader|XMLEventReader|TransformerFactory|SchemaFactory|SchemaValidator|Unmarshaller|JAXBContext|"
    r"XMLSignatureFactory|XPathFactory|\bXmlMapper\b|DOMParser|ElementTree|\blxml\b|xml\.dom|xml\.sax|Regex\(\s*\"<)"
)
TURVIS_CALL = re.compile(r"\.(?:decrypt|verify|encrypt|sign)\(|/turvis/v\d")
TURVIS_URL = re.compile(r"/turvis/v\d")
XML_MAPPER = re.compile(r"XML_MAPPER|xml-mapper:|:8082\b")
SOURCE_GLOBS = ("*/src/**/*.kt", "*/src/**/*.java", "*/src/**/*.ts", "*/src/**/*.svelte", "*.kts", "*/*.kts")
SKIPPED_DIRS = ("/test/", "/node_modules/", "/build/", "/.gradle/")


def rel(path, root):
    return path.relative_to(root).as_posix()


def code_files(root):
    seen = set()
    for pattern in SOURCE_GLOBS:
        for path in sorted((root / "code").glob(pattern)):
            posix = path.as_posix()
            if path.is_file() and not any(d in posix for d in SKIPPED_DIRS) and ".test." not in path.name and ".spec." not in path.name and path not in seen:
                seen.add(path)
                yield path


def fingerprint(lines):
    return hashlib.sha1("\n".join(sorted(line.strip() for line in lines)).encode()).hexdigest()[:10]


def scan(root):
    """Return {(kind, relative path): [(line number, stripped text)]}."""
    hits = {}

    def add(kind, path, pattern):
        for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").split("\n"), 1):
            if pattern.search(line):
                hits.setdefault((kind, rel(path, root)), []).append((number, line.strip()))

    for path in code_files(root):
        add("xml-parser", path, XML_PARSER)
        add("turvis-call", path, TURVIS_CALL)
    for dsl in sorted(root.glob("DSL/Ruuter*/**/*.y*ml")):
        if "Ruuter-turvis-mock" in dsl.parts:
            continue
        add("turvis-dsl", dsl, TURVIS_URL)
        add("xml-mapper-call", dsl, XML_MAPPER)
    return hits


def load_allowlist(path):
    entries = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|", 4)]
        if len(parts) != 5 or not parts[2].isdigit() or not re.fullmatch(r"[0-9a-f]{10}", parts[3]) or not parts[4]:
            raise SystemExit(f"{path.name}:{number}: expected 'kind | path | count | fingerprint | reason'")
        entries[(parts[0], parts[1])] = (int(parts[2]), parts[3], parts[4], number)
    return entries


def check(root, allowlist_path):
    hits = scan(root)
    entries = load_allowlist(allowlist_path)
    problems = []
    for key, found in sorted(hits.items()):
        kind, path = key
        numbers = [n for n, _ in found]
        print_form = f"{kind} | {path} | {len(found)} | {fingerprint([t for _, t in found])} | <reason>"
        if key not in entries:
            problems.append(f"{path}:{numbers[0]}: new {kind} site without an allowlist entry (lines {numbers}); add: {print_form}")
        else:
            count, fp, _reason, _line = entries[key]
            if count != len(found) or fp != fingerprint([t for _, t in found]):
                problems.append(f"{path}:{numbers[0]}: {kind} hits changed (entry: {count} hit(s) {fp}; now {len(found)} hit(s) {fingerprint([t for _, t in found])}, "
                                f"lines {numbers}); review and update the allowlist")
    for key, (_count, _fp, _reason, number) in sorted(entries.items()):
        if key not in hits:
            problems.append(f"{allowlist_path.name}:{number}: stale entry, no {key[0]} hit in {key[1]}")
    return problems


def main():
    if "--print" in sys.argv:
        for (kind, path), found in sorted(scan(ROOT).items()):
            print(f"{kind} | {path} | {len(found)} | {fingerprint([t for _, t in found])} | <reason>   # lines {[n for n, _ in found]}")
        return 0
    problems = check(ROOT, ALLOWLIST)
    if problems:
        print("XML surface changed without an allowlist entry (scripts/xml-surface-allowlist.txt, docs/architecture/"
              "security-and-compliance/perimeter-trust-model.md):", *problems, sep="\n  ", file=sys.stderr)
        return 1
    print(f"validate-xml-surface: {len(scan(ROOT))} allowlisted site(s), no new parser, Turvis or xml-mapper site (a tripwire, not a proof)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
