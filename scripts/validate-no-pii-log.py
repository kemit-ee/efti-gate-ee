#!/usr/bin/env python3
"""Fail when a Kotlin log call interpolates a raw message body (#322).

AS4 bodies carry eFTI payload with personal data; logs are stored at a lower trust tier than the
database. Log a fingerprint (sha256, size, content type) instead. This is a regression guard for the
obvious patterns, not a taint analysis.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOG_CALL = re.compile(r"\b(?:log|logger(?:<[^>]*>)?\(\))\s*\.\s*(?:trace|debug|info|warn|error)\s*\(")
# A raw body, decoded or not, or a variable that holds message content, interpolated into a log call.
FORBIDDEN = re.compile(
    r"decodeToString\(\)"
    r"|\bString\(\s*\w*(?:[bB]ody|[pP]ayload|[dD]ecrypted|[aA]ttachment)\w*\s*(?:,[^)]*)?\)"
    r"|\bRaw content\b"
    r"|\$\{?\w*(?:[rR]aw[bB]ody|[bB]odyBytes|[pP]ayloadXml|[dD]ecrypted|[aA]ttachment\w*[bB]ytes)\b(?!\.(?:size|length|sha256Hex)\b)"
    r"|\$\{?(?:xml|body|payload|response[bB]ody|resBody)\b(?!\.(?:size|length|toByteArray\(\)\.size)\b)"
    r"|\.(?:toString|joinToString)\(\)\s*\)?\s*$"
    r"|\$\{?\w+\.(?:message|localizedMessage)\b"
)
ALLOW = "pii-ok"


def statements(text):
    """Yield (line_number, source) for each log call, up to its closing parenthesis. A log call whose
    line(s) carry a `// pii-ok: <reason>` comment is a reviewed exception and is skipped."""
    lines = text.split("\n")
    for m in LOG_CALL.finditer(text):
        depth, i = 1, m.end()
        while i < len(text) and depth:
            depth += {"(": 1, ")": -1}.get(text[i], 0)
            i += 1
        first, last = text.count("\n", 0, m.start()), text.count("\n", 0, i)
        if ALLOW in "\n".join(lines[first:last + 1]):
            continue
        yield first + 1, text[m.start():i]


def main():
    bad = []
    for path in sorted((ROOT / "code").glob("*/src/**/*.kt")):
        for line, stmt in statements(path.read_text()):
            if FORBIDDEN.search(stmt):
                bad.append(f"{path.relative_to(ROOT)}:{line}: {' '.join(stmt.split())[:120]}")
    if bad:
        print("Log calls that may write a raw message body:", *bad, sep="\n  ", file=sys.stderr)
        return 1
    print("no raw-body log calls found")
    return 0


if __name__ == "__main__":
    sys.exit(main())
