# Manual rollback

## Symptoms
- A deployment to `dev`/`test`/`prelive`/`live` introduced a regression (elevated error rate, a
  failing health probe, a broken feature) and the fix isn't a quick forward-fix.

## Current state note
This repo's `.gitlab-ci.yml` is the kemitaws platform build + release pipeline (test → build → scan →
package → `deploy-dev`/`deploy-test`/`deploy-prelive`/`deploy-live`, via the shared
`standard-service-delivery@8.0.1` component); **it does not deploy anything itself** — `package:charts`
hands the built images to the separate `services/efti/devops` repo, whose Helm wrapper charts and
ArgoCD application definitions are what actually reach a cluster. There is no GitHub Actions deployment
path at all (`.github/workflows/e2e.yml` only runs the `docker-compose` smoke tests). A rollback is
therefore an **ArgoCD action in the devops repo**, not something this repo can trigger directly — the
CICD-3 ticket's original scope (a `.github/workflows/rollback.yml` with a version parameter) doesn't
match this architecture and wasn't built; what's documented below is the real procedure.

## Diagnosis
1. Identify the last known-good release: git tags on this repo (`git tag --sort=-creatordate | head`)
   track releases (MFN-8, `CHANGELOG.md`); `release:pin:dev` (`.gitlab-ci.yml`) pins the version
   deployed to `dev` per push to `main`. For `test`/`prelive`/`live`, the devops repo's release-pin
   file (or its ArgoCD Application manifest's `targetRevision`/image tag) is the source of truth for
   "what's running now" — check it there, not here.
2. Confirm the regression is actually the new release and not a dependency (database migration,
   registry change, a peer gate) — a rollback that doesn't address the real cause just delays the
   incident. Liquibase migrations in particular are **forward-only** in this repo (no down-migrations
   are maintained); rolling back the application image while a new migration already ran can leave the
   schema ahead of the code if the previous release assumed the old schema. Check `DSL/Liquibase/`
   history for whether the bad release included a migration before deciding to roll back the image
   alone.

## Resolution
1. In the `services/efti/devops` repo: point the target environment's ArgoCD Application at the
   previous image tag (the tag immediately before the one that introduced the regression — cross-check
   against this repo's git tags / `CHANGELOG.md`) and sync.
2. If ArgoCD is set to auto-sync from a branch/path that itself pins the "latest" tag, this means
   editing that pinned value in the devops repo (equivalent of `release:pin:dev` but for the target
   environment) and letting ArgoCD reconcile, or triggering a manual sync to a specific prior revision
   directly in the ArgoCD UI/CLI — the exact mechanism is devops-repo-owned; this repo does not carry
   those manifests.
3. After rollback, verify readiness the same way CI does: `GET /efti/health/ready` and
   `GET /xroad/health/ready` return 200, and re-run (or manually exercise) the smoke-test surface the
   `docker compose run --rm http-tests` suite covers for the target environment, since there's no
   automated post-rollback smoke job wired up yet.
4. If the bad release included a forward-only migration (see Diagnosis step 2), a straight image
   rollback is not sufficient — coordinate a fix-forward migration or restore the database from backup
   per the target environment's data-recovery procedure (outside this runbook's scope).
5. Notify: there's no automated rollback notification channel yet (same gap as Alertmanager's missing
   receiver, see [high-error-rate.md](high-error-rate.md)) — post the rollback manually wherever the
   team tracks incidents.
6. Log it: there's no automated "rollback logged in deployment history" mechanism either; the ArgoCD
   sync history and ArgoCD's own audit log are the closest equivalent today.

## Escalation
- Ops engineer identifies the target image tag and performs the ArgoCD rollback.
- Escalate to the platform/infra owner for ArgoCD/devops-repo access if the on-call engineer doesn't
  have it.
- Escalate to gate maintainers if the regression involves a database migration that can't simply be
  rolled back with the image.

## Dashboards / queries
- Not yet available from this repo's side — see [high-error-rate.md](high-error-rate.md) for what
  Prometheus/Grafana currently cover (edelivery/xml-mapper only); deployment/rollout history lives in
  ArgoCD, outside this stack.

## Follow-up (not done)
The original ticket (CICD-3) asked for a manually-triggered GitHub Actions workflow with version
selection, post-rollback smoke tests, and notification. Building that would require either giving
GitHub Actions deploy credentials into environments it currently has none for, or building an ArgoCD
API call from Actions into the devops repo — a real design decision for whoever owns both repos, not
something to default into from here. If that's wanted, the right next step is an ADR, not a stray
workflow file.
