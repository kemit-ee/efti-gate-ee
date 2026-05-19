# TODO

Outstanding work items that are tracked outside the per-file `## Changes` log and outside the per-CFR Acceptance Criteria checklists. Items here are things that should be done but aren't yet scheduled or assigned to a specific epic.

---

## Sync script — not yet automated

**Status:** manual today, GitHub Actions automation not yet implemented.

**Background.** [`scripts/sync-epic-to-issue.py`](scripts/sync-epic-to-issue.py) is a one-way push from theme / sub-area markdown (under [`docs/cfr/`](docs/cfr/)) to the corresponding GitHub issue body, driven by [`scripts/epic-issue-map.tsv`](scripts/epic-issue-map.tsv). It works correctly when invoked from a developer machine; the validation pass on the Theme 1 pilot (PR #25) was done by a human running the script locally.

**What's missing.** No CI job currently invokes the sync script. Concretely:

- On a push that modifies any file listed in `scripts/epic-issue-map.tsv` (or any file under `docs/cfr/`), no automatic issue-body update happens. Drift between markdown and issues is possible.
- `--bootstrap` (first-time issue creation) is also manual.
- `--force-ac` (AC catalogue overwrite) is also manual.

**What should exist.** A GitHub Actions workflow that:

1. Triggers on push to `feature/planning` (and to `main` once that becomes the integration branch).
2. Detects which markdown files under `docs/cfr/` were touched in the push.
3. For each touched file that has an entry in `scripts/epic-issue-map.tsv`, runs `scripts/sync-epic-to-issue.py <path>` (without `--bootstrap` and without `--force-ac` — the default two-zone sync that preserves the AC section in the issue body).
4. Surfaces failures (mapping missing, gh CLI error, network issue) as a workflow-run failure so they're visible on the PR / commit page.

**Out of scope for the same workflow (separate decisions).**

- **Auto-bootstrap** of issues for newly-added entries in the manifest. Probably manual: a human decides which repo and which issue number.
- **Auto-force-AC**: never. `--force-ac` wipes issue-side state and must be a deliberate human action.
- **Reverse sync** (issue → markdown). Not implemented at all yet; would be a separate script and a separate workflow.

**Acceptance criteria** (when implemented, the issue tracking this work should carry these):

- [ ] `.github/workflows/sync-cfr-to-issues.yml` (or equivalent) exists.
- [ ] Triggers on push to `feature/planning` (and `main`).
- [ ] Only runs for files listed in `scripts/epic-issue-map.tsv` (so unrelated edits don't churn).
- [ ] Uses a `GITHUB_TOKEN` (or a finer-grained PAT) with `issues: write` on `kemit-ee/efti-gate-ee`. (Operators running the sync against a sandbox of their own — keyed off the operator-local `scripts/epic-issue-map.tsv` — handle credentials at the developer-machine level; the workflow itself only operates against the canonical repo.)
- [ ] Skips files whose markdown has no `<!-- issue-body:begin --> ... <!-- issue-body:end -->` markers (instead of erroring).
- [ ] Workflow-run output is clear enough to diagnose a sync failure without re-running locally.
- [ ] Documented in `CONTRIBUTING.md` so contributors know the sync is automated and the issue body shouldn't be hand-edited in the upper zone.

---

## Two-zone sync — not yet validated under real sub-task promotion

**Status:** the two-zone behaviour of [`scripts/sync-epic-to-issue.py`](scripts/sync-epic-to-issue.py) (regenerate the upper zone — story / spec-anchors / architecture link — from markdown; preserve the AC section from the live issue body) has been verified only against AC bullets in their unchanged checkbox form (`- [ ]`). It has **not** been exercised against an AC bullet that's been promoted to a real GitHub sub-issue via the GitHub UI's "Convert to issue / sub-issue" affordance.

**Why this matters.** When an AC bullet is promoted to a sub-issue, GitHub rewrites the body line — typically into a `- [ ] #N` reference where `#N` is the sub-issue number, and the checkbox state mirrors the sub-issue's open/closed state. If the sync script's AC-extraction logic doesn't recognise this shape, the next sync could lose the sub-issue reference (the sub-issue itself survives because the parent/child link is stored on the issue's `Parent issue` field, but the body presentation desynchronises).

**What should happen** (a short, cheap test — not full automation):

1. Pick one test issue on `turnerrainer/eFTI` — `#2` (User Management and RBAC) is a good candidate.
2. In the GitHub UI, hover the first AC checkbox and use "Convert to issue / sub-issue".
3. Re-run `scripts/sync-epic-to-issue.py docs/cfr/identity-and-access/user_management_and_rbac.md` (no flags).
4. Verify: the promoted-sub-issue line in `#2`'s body still carries the `#N` reference; the corresponding sub-issue (in the project board) still shows as the parent's child; the checkbox state matches the sub-issue's open/closed state.

**Acceptance criteria** (when validated):

- [ ] At least one AC bullet on a test issue has been promoted to a sub-issue.
- [ ] `scripts/sync-epic-to-issue.py` has run cleanly against that issue's markdown source.
- [ ] The sub-issue reference (`- [ ] #N`) survives the sync intact.
- [ ] If the script does **not** survive sub-task promotion cleanly, the failure mode is documented here and a fix is scoped (likely in the `existing_ac_section` extractor in [`scripts/sync-epic-to-issue.py`](scripts/sync-epic-to-issue.py)).
