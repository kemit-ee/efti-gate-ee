#!/usr/bin/env bash
#
# Sync a theme/epic markdown file's <!-- issue-body:begin --> ... <!-- issue-body:end -->
# block into the body of its corresponding GitHub issue.
#
# Usage:
#   scripts/sync-epic-to-issue.sh <markdown-path>
#   scripts/sync-epic-to-issue.sh --all
#   scripts/sync-epic-to-issue.sh --dry-run <markdown-path>
#
# The mapping markdown-path → owner/repo + issue-number lives in
# scripts/epic-issue-map.tsv (tab-separated).
#
# Relative spec links (../specs/..., ../architecture/...) inside the extracted
# block are rewritten to absolute github.com URLs against the BRANCH env var
# (default: feature/planning) of the BASE_REPO env var
# (default: kemit-ee/efti-gate-ee) so the rendered issue body links to the
# canonical spec source — not to the test repo.
#
# Requires: gh, awk, sed.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAP_FILE="${REPO_ROOT}/scripts/epic-issue-map.tsv"
BASE_REPO="${BASE_REPO:-kemit-ee/efti-gate-ee}"
BRANCH="${BRANCH:-feature/planning}"
BLOB_PREFIX="https://github.com/${BASE_REPO}/blob/${BRANCH}"

DRY_RUN=0
ALL=0
TARGETS=()

usage() {
  sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --all)     ALL=1; shift ;;
    -h|--help) usage ;;
    -*)        echo "unknown flag: $1" >&2; usage ;;
    *)         TARGETS+=("$1"); shift ;;
  esac
done

if [[ $ALL -eq 1 ]]; then
  while IFS=$'\t' read -r md _ _; do
    [[ -z "$md" || "${md:0:1}" == "#" ]] && continue
    TARGETS+=("$md")
  done < "$MAP_FILE"
fi

if [[ ${#TARGETS[@]} -eq 0 ]]; then
  echo "error: no markdown target(s) supplied. Use --all or pass a path." >&2
  usage
fi

lookup_target() {
  local md="$1"
  awk -F'\t' -v md="$md" '
    !/^#/ && NF >= 3 && $1 == md { print $2 "\t" $3; found=1; exit }
    END { if (!found) exit 1 }
  ' "$MAP_FILE"
}

extract_block() {
  local md="$1"
  awk '
    /<!-- issue-body:begin -->/ { in_block=1; next }
    /<!-- issue-body:end -->/   { in_block=0; next }
    in_block { print }
  ' "$md"
}

rewrite_links() {
  # Rewrite relative paths inside the extracted block to absolute GitHub URLs.
  # The block can come from docs/epics/ (2 dirs deep) — its relative refs to
  # ../specs/... and ../architecture/... must resolve relative to the source
  # file's directory.
  local src_dir="$1"
  local prefix
  case "$src_dir" in
    docs/epics) prefix="${BLOB_PREFIX}/docs" ;;
    *)
      echo "error: source dir '$src_dir' is not docs/epics — extend rewrite_links()" >&2
      return 1
      ;;
  esac
  sed -E "s|\]\(\.\./([^)]+)\)|](${prefix}/\1)|g; s|\]\(\./([^)]+)\)|](${BLOB_PREFIX}/${src_dir}/\1)|g"
}

sync_one() {
  local md="$1"

  if [[ ! -f "$REPO_ROOT/$md" ]]; then
    echo "error: file not found: $md" >&2
    return 1
  fi

  local owner_repo issue_number
  if ! IFS=$'\t' read -r owner_repo issue_number < <(lookup_target "$md"); then
    echo "error: $md is not in $MAP_FILE — add an entry first." >&2
    return 1
  fi

  local src_dir
  src_dir="$(dirname "$md")"

  local body
  body="$(extract_block "$REPO_ROOT/$md" | rewrite_links "$src_dir")"

  if [[ -z "$body" ]]; then
    echo "error: $md has no <!-- issue-body:begin --> ... <!-- issue-body:end --> block." >&2
    return 1
  fi

  # Provenance footer so anyone reading the issue knows it is a generated mirror.
  local footer
  footer=$'\n\n---\n'"_Synced from [\`${md}\`](${BLOB_PREFIX}/${md}) by \`scripts/sync-epic-to-issue.sh\`. Edit the markdown source, not the issue body — manual edits will be overwritten on the next sync._"
  body="${body}${footer}"

  if [[ $DRY_RUN -eq 1 ]]; then
    echo "=== DRY-RUN: ${owner_repo}#${issue_number}  <-  ${md} ==="
    echo "$body"
    echo "=== END DRY-RUN ==="
    return 0
  fi

  echo ">> ${owner_repo}#${issue_number}  <-  ${md}"
  printf '%s' "$body" | gh issue edit "$issue_number" -R "$owner_repo" --body-file -
}

for md in "${TARGETS[@]}"; do
  sync_one "$md"
done
