#!/usr/bin/env python3
"""Sync a theme/sub-area markdown file's <!-- issue-body:* --> block into the
body of its corresponding GitHub issue.

Usage:
    scripts/sync-epic-to-issue.py <markdown-path>
    scripts/sync-epic-to-issue.py --all
    scripts/sync-epic-to-issue.py --dry-run <markdown-path>

The mapping markdown-path -> owner/repo + issue-number lives in
scripts/epic-issue-map.tsv (tab-separated).

Sync zones:
  - The script regenerates everything between <!-- issue-body:begin --> and
    <!-- issue-body:end --> EXCEPT the Acceptance Criteria section, which is
    owned by the GitHub issue once the issue exists (checkbox state, sub-issue
    promotion, AC editing in the UI).
  - On --bootstrap, the AC section is included (first-time issue creation).
  - Without --bootstrap, the script preserves the existing AC section in the
    issue and overwrites only the upper zone (story + spec-anchors +
    architecture link).

Relative spec links inside the extracted block are resolved against the
source-file's directory and rewritten to absolute github.com URLs against
the BRANCH env var (default: feature/planning) of the BASE_REPO env var
(default: kemit-ee/efti-gate-ee).

Requires: gh CLI (logged in).
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MAP_FILE = REPO_ROOT / "scripts" / "epic-issue-map.tsv"
BASE_REPO = os.environ.get("BASE_REPO", "kemit-ee/efti-gate-ee")
BRANCH = os.environ.get("BRANCH", "feature/planning")
BLOB_PREFIX = f"https://github.com/{BASE_REPO}/blob/{BRANCH}"

ISSUE_BODY_BEGIN = "<!-- issue-body:begin -->"
ISSUE_BODY_END = "<!-- issue-body:end -->"
AC_HEADING = re.compile(r"^##\s+Acceptance Criteria\b", re.MULTILINE)
LINK_RE = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")


def die(msg: str) -> "NoReturn":  # noqa: F821
    sys.stderr.write(f"error: {msg}\n")
    sys.exit(1)


def read_manifest() -> list[tuple[str, str, int]]:
    rows: list[tuple[str, str, int]] = []
    for raw in MAP_FILE.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            die(f"malformed manifest line: {raw!r}")
        md, owner_repo, num = parts[0], parts[1], int(parts[2])
        rows.append((md, owner_repo, num))
    return rows


def lookup(md: str, manifest: list[tuple[str, str, int]]) -> tuple[str, int]:
    for entry_md, owner_repo, num in manifest:
        if entry_md == md:
            return owner_repo, num
    die(f"{md} is not in {MAP_FILE.relative_to(REPO_ROOT)} — add an entry first.")


def extract_block(md_path: Path) -> str:
    text = md_path.read_text()
    begin = text.find(ISSUE_BODY_BEGIN)
    end = text.find(ISSUE_BODY_END)
    if begin == -1 or end == -1 or end < begin:
        die(f"{md_path} has no {ISSUE_BODY_BEGIN} ... {ISSUE_BODY_END} block.")
    return text[begin + len(ISSUE_BODY_BEGIN) : end].strip("\n")


def strip_ac_section(body: str) -> str:
    """Return body with the Acceptance Criteria section (and everything
    after it) removed. Leaves a trailing blank line."""
    m = AC_HEADING.search(body)
    if not m:
        return body
    return body[: m.start()].rstrip() + "\n"


def fetch_issue_body(owner_repo: str, num: int) -> str:
    proc = subprocess.run(
        ["gh", "issue", "view", str(num), "-R", owner_repo, "--json", "body", "--jq", ".body"],
        check=True,
        capture_output=True,
        text=True,
    )
    return proc.stdout


def existing_ac_section(body: str) -> str | None:
    """Return the AC section text (heading inclusive) from an existing issue
    body, or None if not present."""
    m = AC_HEADING.search(body)
    if not m:
        return None
    # AC section runs from the heading to the provenance footer (---) or EOF.
    tail = body[m.start() :]
    # Drop the provenance footer (we re-add it below).
    footer_idx = tail.rfind("\n\n---\n")
    if footer_idx != -1:
        tail = tail[:footer_idx]
    return tail.rstrip() + "\n"


def rewrite_links(body: str, src_dir: Path) -> str:
    def fix(m: re.Match[str]) -> str:
        text, target = m.group(1), m.group(2)
        if target.startswith(("http://", "https://", "#", "mailto:")):
            return m.group(0)
        # Resolve target against the source-file's directory, relative to repo root.
        try:
            abs_path = (src_dir / target).resolve()
            rel = abs_path.relative_to(REPO_ROOT).as_posix()
        except ValueError:
            return m.group(0)  # leave absolute / outside-repo paths alone
        return f"[{text}]({BLOB_PREFIX}/{rel})"

    return LINK_RE.sub(fix, body)


def provenance_footer(md: str) -> str:
    return (
        "\n\n---\n"
        f"_Synced from [`{md}`]({BLOB_PREFIX}/{md}) by `scripts/sync-epic-to-issue.py`. "
        "Edit the markdown source for the upper zone (story / spec-anchors / architecture link); "
        "the Acceptance Criteria section below is owned by the issue once created — sub-task promotion, "
        "checkbox state, and AC text edits in the GitHub UI are preserved across syncs._"
    )


def sync_one(
    md: str,
    manifest: list[tuple[str, str, int]],
    *,
    dry_run: bool,
    bootstrap: bool,
    force_ac: bool,
) -> None:
    md_path = REPO_ROOT / md
    if not md_path.is_file():
        die(f"file not found: {md}")
    owner_repo, num = lookup(md, manifest)

    block = extract_block(md_path)
    rewritten = rewrite_links(block, md_path.parent)

    if bootstrap or force_ac:
        body = rewritten + provenance_footer(md)
    else:
        upper = strip_ac_section(rewritten)
        try:
            existing = fetch_issue_body(owner_repo, num)
            ac_section = existing_ac_section(existing)
        except subprocess.CalledProcessError as exc:
            die(f"failed to fetch existing issue {owner_repo}#{num}: {exc.stderr.strip()}")
        if ac_section is None:
            # Issue exists but carries no AC section yet (e.g. placeholder body) —
            # treat as bootstrap for AC: take it from the markdown.
            m = AC_HEADING.search(rewritten)
            ac_section = rewritten[m.start():] if m else ""
        body = upper.rstrip() + "\n\n" + ac_section.lstrip() + provenance_footer(md)

    if dry_run:
        print(f"=== DRY-RUN: {owner_repo}#{num}  <-  {md} (bootstrap={bootstrap}, force_ac={force_ac}) ===")
        print(body)
        print("=== END DRY-RUN ===")
        return

    print(f">> {owner_repo}#{num}  <-  {md}  (bootstrap={bootstrap}, force_ac={force_ac})")
    subprocess.run(
        ["gh", "issue", "edit", str(num), "-R", owner_repo, "--body-file", "-"],
        input=body,
        text=True,
        check=True,
    )


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("paths", nargs="*", help="Markdown file(s) to sync. Use --all to sync every entry in the manifest.")
    p.add_argument("--all", action="store_true", help="Sync every entry listed in the manifest.")
    p.add_argument("--dry-run", action="store_true", help="Print the would-be issue body; do not call gh.")
    p.add_argument("--bootstrap", action="store_true", help="Include the AC section verbatim (use on first-time issue creation).")
    p.add_argument("--force-ac", action="store_true", help="Overwrite the AC section with the markdown's version, wiping issue-side state. Destructive.")
    args = p.parse_args()

    manifest = read_manifest()
    if args.all:
        targets = [m for (m, _, _) in manifest]
    else:
        targets = args.paths
    if not targets:
        p.error("no markdown target supplied (use --all or pass a path)")

    for md in targets:
        sync_one(md, manifest, dry_run=args.dry_run, bootstrap=args.bootstrap, force_ac=args.force_ac)


if __name__ == "__main__":
    main()
