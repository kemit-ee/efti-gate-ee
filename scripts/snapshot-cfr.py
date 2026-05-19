#!/usr/bin/env python3
"""Snapshot the current CFR README.md files into versioned siblings.

Usage:
    scripts/snapshot-cfr.py <version>            # e.g. v1.0, v1.1
    scripts/snapshot-cfr.py --dry-run <version>

For every `README.md` under `docs/cfr/` (recursively, but only files literally
named README.md — not the v1.0.md / v1.1.md snapshots themselves), copy the
file to `<version>.md` in the same directory. The current README.md is
unaffected.

Intended workflow:
    1. Cut a release tag (e.g. v1.1) on `feature/planning`.
    2. Run `scripts/snapshot-cfr.py v1.1` immediately after the tag.
    3. Commit the new `vN.N.md` files; push.

The snapshot is a frozen copy of the AC catalogue at release time. The
README.md continues to evolve toward the next release.
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CFR_ROOT = REPO_ROOT / "docs" / "cfr"
VERSION_RE = re.compile(r"^v\d+(\.\d+)*(-[A-Za-z0-9.-]+)?$")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("version", help="Version label, e.g. v1.0, v1.1, v2.0-rc1.")
    p.add_argument("--dry-run", action="store_true", help="List would-be snapshot paths; do not copy.")
    args = p.parse_args()

    if not VERSION_RE.match(args.version):
        p.error(f"version must match {VERSION_RE.pattern!r} (e.g. v1.0, v1.1, v2.0-rc1)")

    snapshot_name = f"{args.version}.md"

    if not CFR_ROOT.is_dir():
        sys.stderr.write(f"error: {CFR_ROOT} does not exist\n")
        sys.exit(1)

    targets = [r for r in CFR_ROOT.rglob("README.md")]
    if not targets:
        sys.stderr.write(f"error: no README.md files found under {CFR_ROOT}\n")
        sys.exit(1)

    collisions = [t for t in targets if (t.parent / snapshot_name).exists()]
    if collisions:
        sys.stderr.write(f"error: {snapshot_name} already exists in:\n")
        for c in collisions:
            sys.stderr.write(f"  - {c.parent.relative_to(REPO_ROOT)}\n")
        sys.stderr.write("Refusing to overwrite. Use a new version label.\n")
        sys.exit(1)

    for src in sorted(targets):
        dst = src.parent / snapshot_name
        rel_src = src.relative_to(REPO_ROOT)
        rel_dst = dst.relative_to(REPO_ROOT)
        if args.dry_run:
            print(f"would copy: {rel_src} -> {rel_dst}")
            continue
        shutil.copy2(src, dst)
        print(f"copied: {rel_src} -> {rel_dst}")


if __name__ == "__main__":
    main()
