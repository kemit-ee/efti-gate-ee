# Deployment artefacts

This directory holds the runtime deployment shape and, now that the implementation is under way, a real inventory of what a deployment needs. Helm charts / K8s manifests still live in the separate `services/efti/devops` repo (see [`resource-and-secrets-requirements.md`](resource-and-secrets-requirements.md) §7).

**[`resource-and-secrets-requirements.md`](resource-and-secrets-requirements.md)** — compiled from the actual running system (`compose.yml`, `constants.ini`, `docker/*`): per-component CPU/memory, every secret (name, consumer, purpose, dev value, deploy gotcha), the two separate PostgreSQL databases, the Liquibase `dev`-context seed data that must not ship to prod, the TIM JWT-key persistence requirement, the Ruuter `constants.ini` Secret file, the AS4 keystore (§3.5) and how every secret is stored, delivered and rotated (§3.0). Written for whoever does the first production deployment.

## What's documented

- **Deployment topology** — see [`../non-functional.md`](../non-functional.md) §3: two-node minimum, PostgreSQL primary + DR standby, Layer-7 LB, reverse proxy for TLS, AS4 access point (custom or Domibus), CronManager sibling.
- **Multi-node component diagram** — [`../diagrams/arch-01-multi-node-deployment.mmd`](../diagrams/arch-01-multi-node-deployment.mmd).
- **Environment-parity rule** — test/stage/prod must be identical; dev (developer's machine) is allowed minor looseness. Same software, same versions, same backend. No "Redis in prod, Postgres in dev" splits.
- **CronManager job set** — [`DSL/CronManager/efti-ops.yaml`](../../../DSL/CronManager/efti-ops.yaml), loaded by the `cronmanager` service ([turnerrainer/CronManager](https://github.com/turnerrainer/CronManager) `0.2.3-alpha`, `docker/cronmanager/`). Five jobs, all `POST` to Ruuter's `/ops/v1/*` with `Authorization` from the env var `OPS_AUTHORIZATION` (`Bearer <ARCHIVE_OPS_TOKEN>`): `archive-consignments` (every 10 min, 02:00–05:59), `purge-archive` (04:00), `purge-async-responses` and `purge-search-results` (every 5 min), `refresh-read-models` (every 15 min). The cabotage-expiry sweep (Reg 2024/1942 Art 11(4)) is kept as [`cronmanager-expire.pending.yaml`](cronmanager-expire.pending.yaml) and is **not loaded**: its route does not exist yet. Strict requirement (Epic 26): CronManager is deployed as a sibling Pod/container alongside the gate; it owns every scheduled task. The gate process never schedules its own jobs.
- **CronManager specifics** (see also `resource-and-secrets-requirements.md` §1, §3.2):
  - The job schema is checked with `deny_unknown_fields`: an unknown field, an unset `OPS_AUTHORIZATION` or an invalid header makes CronManager skip the whole file with an ERROR — check the boot log for `group efti-ops → 5 job(s)`. There is no request-body field, so every job POSTs an empty body and the route defaults apply; there is no `${ENV}` substitution in `url`, so the Ruuter host is in the file (`http://ruuter:8086`) and a deployment with another service name edits it before the image build.
  - Jobs and config are baked into the image; a change needs `docker compose up --build cronmanager` (or a new image + rollout).
  - No `database:` block: CronManager keeps no execution history of its own; the gate's `jobs_execution_log` (written by every `/ops` route) is the record.
  - **Open upstream gap — production posture.** Every job targets Ruuter by its in-cluster name, which resolves to a private address. CronManager's SSRF guard (`security.block_private_networks`, default `true`) refuses those, and it has no host allow-list, so `docker/cronmanager/cronmanager.yaml` sets it to `false`. With `APP_ENV`/`ENVIRONMENT`/`DEPLOY_ENV` = `production`, `staging` or `test`, CronManager **refuses to boot** with that setting (it is only a WARN in `dev`/unset). Until CronManager accepts a per-host allow-list for private targets, the chart must leave `APP_ENV` unset (accepting the WARN, and losing the strict weak-token check — so the admin token must be generated with ≥ 32 random characters regardless).
  - Not yet deployable through GitLab: `.gitlab-ci.yml` builds and scans the `cronmanager` image, but the `cronmanager` component is not in `components:` until its wrapper chart is merged in `services/efti/devops` ([devops !3](https://gitlab.kemitaws.ee/services/efti/devops/-/merge_requests/3): `charts/cronmanager`, `cronmanager-secret` from SSM `ops/archive-token` + `cronmanager/admin-token`, egress only to `ruuter:8086`, `APP_ENV: dev` for the reason above). Rollout: merge !3 → add `cronmanager` to `components:` → seed both SSM parameters → enable it in `environments/dev/release.yaml`.
- **Registry delivery** — the gate itself no longer receives registry data over HTTP. Each deployment supplies a `registry/` folder (`gates/`, `platforms/`, `authorities/`, one YAML file per entity, certificates inline) which `scripts/registry-to-json.py` converts and validates into JSON **at image build time**; the `registry` image's static nginx then serves that JSON. It is fail-closed at the build: a malformed entry fails `docker compose build` (and CI, which runs the same converter), so nothing unvalidated can be deployed. See [`registry/README.md`](../../../registry/README.md) and [ADR-011](../../architecture/decisions/011-registries-as-signed-config.md).

## What's missing (intentional Phase-2 scope)

| Artefact | Owner | Format |
|---|---|---|
| `helm/values.yaml` (skeleton) | Implementation phase | Helm 3 chart values, with parameters for replica count, DB connection, certificates, log level, eDelivery cert paths. |
| `helm/templates/*.yaml` | Implementation phase | Deployment, Service, Ingress, ConfigMap, SealedSecret manifests. |
| `compose.dev.yml` | Implementation phase | Single-node docker-compose for developer machines (gate + Postgres + Caddy). |
| `Dockerfile` | Implementation phase | Multi-stage build; non-root runtime user; health-probe-friendly. |
| TLS cert rotation runbook | Operator | Manual procedure for ingress TLS certificates. (AS4 keystore generation, provisioning and rotation: [`resource-and-secrets-requirements.md`](resource-and-secrets-requirements.md) §3.5; general secret rotation: §3.0.) |
| GitOps wiring | Implementation phase | Pick one of ArgoCD / Flux; not this spec's call. |

## Why this is deliberately empty

A specification repository describes the *contract* — APIs, schema, error catalogue, design rules, acceptance criteria. The deployment *implementation* is the next phase, after vendor selection. Pinning Helm values now would either (a) bake assumptions about a specific cluster shape that the vendor may not match, or (b) require upkeep as the implementation evolves. We leave the artefacts to the build phase, with the topology constraints in [`../non-functional.md`](../non-functional.md) §3 as the binding contract.

If you are a vendor evaluating this spec: assume the topology in §3 is binding; the YAML/manifest specifics are negotiable on day 1 of the engagement.
