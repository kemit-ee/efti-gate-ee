# Deployment artefacts

This directory holds the runtime deployment shape and, now that the implementation is under way, a real inventory of what a deployment needs. Helm charts / K8s manifests still live in the separate `services/efti/devops` repo (see [`resource-and-secrets-requirements.md`](resource-and-secrets-requirements.md) §7).

**[`resource-and-secrets-requirements.md`](resource-and-secrets-requirements.md)** — compiled from the actual running system (`compose.yml`, `constants.ini`, `docker/*`): per-component CPU/memory, every secret (name, consumer, purpose, dev value, deploy gotcha), the two separate PostgreSQL databases, the Liquibase `dev`-context seed data that must not ship to prod, and the TIM JWT-key persistence requirement. Written for whoever does the first production deployment.

## What's documented

- **Deployment topology** — see [`../non-functional.md`](../non-functional.md) §3: two-node minimum, PostgreSQL primary + DR standby, Layer-7 LB, reverse proxy for TLS, AS4 access point (custom or Domibus), CronManager sibling.
- **Multi-node component diagram** — [`../diagrams/arch-01-multi-node-deployment.mmd`](../diagrams/arch-01-multi-node-deployment.mmd).
- **Environment-parity rule** — test/stage/prod must be identical; dev (developer's machine) is allowed minor looseness. Same software, same versions, same backend. No "Redis in prod, Postgres in dev" splits.
- **CronManager job set** — two canonical YAMLs: [`cronmanager-archive.yaml`](cronmanager-archive.yaml) (nightly archival sweep, Epic 26) and [`cronmanager-expire.yaml`](cronmanager-expire.yaml) (daily cabotage-expiry sweep — Reg 2024/1942 Art 11(4)). A peer-gate health probe used to be a third one; it is gone with the registry ping surface ([ADR-011](../../architecture/decisions/011-registries-as-signed-config.md)) — registry `status` is configuration, not a health observation. Strict requirement (Epic 26): CronManager is deployed as a sibling Pod/container alongside the gate; it owns every scheduled task and calls the gate's `/api/v1/admin/*` endpoints over HTTP on its cron schedule. The gate process never schedules its own jobs.
- **Registry delivery** — the gate itself no longer receives registry data over HTTP. Each deployment supplies a `registry/` folder (`gates/`, `platforms/`, `authorities/`, one YAML file per entity, certificates inline) which `scripts/registry-to-json.py` converts and validates into JSON **at image build time**; the `registry` image's static nginx then serves that JSON. It is fail-closed at the build: a malformed entry fails `docker compose build` (and CI, which runs the same converter), so nothing unvalidated can be deployed. See [`registry/README.md`](../../../registry/README.md) and [ADR-011](../../architecture/decisions/011-registries-as-signed-config.md).

## What's missing (intentional Phase-2 scope)

| Artefact | Owner | Format |
|---|---|---|
| `helm/values.yaml` (skeleton) | Implementation phase | Helm 3 chart values, with parameters for replica count, DB connection, certificates, log level, eDelivery cert paths. |
| `helm/templates/*.yaml` | Implementation phase | Deployment, Service, Ingress, ConfigMap, SealedSecret manifests. |
| `compose.dev.yml` | Implementation phase | Single-node docker-compose for developer machines (gate + Postgres + Caddy). |
| `Dockerfile` | Implementation phase | Multi-stage build; non-root runtime user; health-probe-friendly. |
| Cert rotation runbook | Operator | Manual procedure for AS4 + TLS certificates (no automated rotation in v0). |
| GitOps wiring | Implementation phase | Pick one of ArgoCD / Flux; not this spec's call. |

## Why this is deliberately empty

A specification repository describes the *contract* — APIs, schema, error catalogue, design rules, acceptance criteria. The deployment *implementation* is the next phase, after vendor selection. Pinning Helm values now would either (a) bake assumptions about a specific cluster shape that the vendor may not match, or (b) require upkeep as the implementation evolves. We leave the artefacts to the build phase, with the topology constraints in [`../non-functional.md`](../non-functional.md) §3 as the binding contract.

If you are a vendor evaluating this spec: assume the topology in §3 is binding; the YAML/manifest specifics are negotiable on day 1 of the engagement.
