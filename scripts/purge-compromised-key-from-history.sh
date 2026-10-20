#!/usr/bin/env bash
# Purge the compromised AS4 private key from git history (#320 subtask 2, tracked in #312).
#
# The key is committed twice: code/certs/own.key (PEM) and code/certs/own.p12 (PKCS#12, the same key,
# password-protected with the well-known default). Both must go. The script never pushes.
#
#   inventory [OUT]   read-only; run in any clone. Lists every history path/blob of the key files and
#                     writes their blob ids to OUT (default: compromised-blobs.txt) for later verification.
#   purge DIR         clones the remote into a fresh mirror at DIR, rewrites it with git-filter-repo and
#                     verifies it. Needs git-filter-repo. Nothing is pushed; the push commands are printed.
#   verify BLOBS      run inside a (rewritten) repo: no commit touches the paths and no listed blob exists.
#
# Preconditions (#320/#312): identity rotated first, #313 GO recorded, open PR authors informed.
set -euo pipefail

PATHS=(code/certs/own.key code/certs/own.p12)
REMOTE_URL=${REMOTE_URL:-git@github.com:kemit-ee/efti-gate-ee.git}

die() { echo "error: $*" >&2; exit 1; }

touching_commits() { git rev-list --all -- "${PATHS[@]}"; }

blob_ids() {
  git rev-list --objects --all | awk -v a="${PATHS[0]}" -v b="${PATHS[1]}" '$2==a||$2==b {print $1}' | sort -u
}

cmd_inventory() {
  local out=${1:-compromised-blobs.txt}
  echo "commits touching ${PATHS[*]}:"
  git log --all --format='  %h %ad %an  %s' --date=short -- "${PATHS[@]}"
  blob_ids > "$out"
  echo "blob ids written to $out ($(wc -l < "$out" | tr -d ' ') blobs)"
  echo "refs that must be rewritten or removed:"
  git for-each-ref --contains "$(touching_commits | tail -1)" --format='  %(refname)' 2>/dev/null || true
  echo "public-key fingerprint of the committed certificate (publish this as the revoked pin):"
  local cert
  cert=$(mktemp)
  if git show HEAD:code/certs/own.crt > "$cert" 2>/dev/null; then
    openssl x509 -in "$cert" -noout -fingerprint -sha256
  else
    echo "  code/certs/own.crt is not at HEAD; read it from history before the purge"
  fi
  rm -f "$cert"
}

cmd_verify() {
  local blobs=${1:?usage: verify BLOBS_FILE}
  [ -f "$blobs" ] || die "no such file: $blobs"
  local bad=0
  if [ -n "$(touching_commits)" ]; then
    echo "FAIL: commits still touch ${PATHS[*]}:"; touching_commits; bad=1
  fi
  while read -r id; do
    if git cat-file -e "$id" 2>/dev/null; then echo "FAIL: blob $id still present"; bad=1; fi
  done < "$blobs"
  [ "$bad" -eq 0 ] || exit 1
  echo "OK: no commit touches the key paths and none of the $(wc -l < "$blobs" | tr -d ' ') recorded blobs exist"
}

cmd_purge() {
  local dir=${1:?usage: purge DIR}
  command -v git-filter-repo >/dev/null || die "git-filter-repo not installed (brew install git-filter-repo)"
  [ ! -e "$dir" ] || die "$dir already exists; the rewrite must start from a fresh clone"
  local blobs
  blobs="$(cd "$(dirname "$dir")" && pwd)/$(basename "$dir")-blobs.txt"
  git clone --mirror "$REMOTE_URL" "$dir"
  (
    cd "$dir"
    blob_ids > "$blobs"
    [ -s "$blobs" ] || die "no key blobs found in $REMOTE_URL; nothing to purge"
    args=(--invert-paths)
    for p in "${PATHS[@]}"; do args+=(--path "$p"); done
    if git filter-repo --help 2>&1 | grep -q -- --sensitive-data-removal; then
      args+=(--sensitive-data-removal)
    fi
    git filter-repo "${args[@]}"
    git reflog expire --expire=now --all
    git gc --prune=now --aggressive
    cmd_verify "$blobs"
    cat <<MSG

Rewritten mirror: $dir   (blob ids: $blobs)
NOT pushed. After the coordination in #312/#320 is done, from $dir:
  git push --force --all  origin
  git push --force --tags origin
Then, still required by hand:
  - GitHub support: purge cached views, refs/pull/* and forks (git push cannot rewrite refs/pull/*)
  - GitLab mirror ($(git -C "$OLDPWD" remote get-url gitlab 2>/dev/null || echo gitlab)): same rewrite
  - every clone and open PR branch is re-created from the rewritten history (git fetch; git rebase --onto)
  - local worktree refs such as refs/codex/* and refs/gitlab/* in existing clones are deleted or re-cloned
MSG
  )
}

case "${1:-}" in
  inventory) shift; cmd_inventory "$@" ;;
  purge)     shift; cmd_purge "$@" ;;
  verify)    shift; cmd_verify "$@" ;;
  *) sed -n '2,15p' "$0"; exit 2 ;;
esac
