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
- [ ] Uses a `GITHUB_TOKEN` (or a finer-grained PAT) with `issues: write` on the target repo(s). The target repo may not be the same repo as the workflow source (e.g. `turnerrainer/eFTI` during testing, `kemit-ee/efti-gate-ee` in production) — handle cross-repo credentials.
- [ ] Skips files whose markdown has no `<!-- issue-body:begin --> ... <!-- issue-body:end -->` markers (instead of erroring).
- [ ] Workflow-run output is clear enough to diagnose a sync failure without re-running locally.
- [ ] Documented in `CONTRIBUTING.md` so contributors know the sync is automated and the issue body shouldn't be hand-edited in the upper zone.
