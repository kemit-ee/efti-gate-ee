# Architecture: Infrastructure

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Theme-wide architectural rules. Every sub-area below — and every Acceptance Criterion (AC) it carries — must derive from or at minimum **not conflict with** the rules stated here. AC live in the corresponding sub-area files under [`docs/cfr/infrastructure/`](../../cfr/infrastructure/); this document describes the *contract those AC implement*.

**System-wide reference:** [eFTI Gate Reference Architecture](../eFTI-Gate-Reference-Architecture.md). This document narrows the system-wide rules to the Infrastructure surface.

**Sub-architectures in this theme** (each is the architectural surface for the AC tracked in the linked epic):

- [Scalability and Statelessness](scalability_and_statelessness.md) — AC: [`docs/cfr/infrastructure/scalability_and_statelessness.md`](../../cfr/infrastructure/scalability_and_statelessness.md)
- [Health Checks and Graceful Shutdown](health_checks_and_graceful_shutdown.md) — AC: [`docs/cfr/infrastructure/health_checks_and_graceful_shutdown.md`](../../cfr/infrastructure/health_checks_and_graceful_shutdown.md)
- [Append-Only Archival via CronManager](append_only_archival.md) — AC: [`docs/cfr/infrastructure/append_only_archival.md`](../../cfr/infrastructure/append_only_archival.md)

---

## Overarching rules

These are the cross-cutting invariants every sub-area in this theme derives from. AC bullets in the CFR files specialise them to specific endpoints, error codes, or DB state.

### 1.1 Horizontally scalable; no node holds state

Every gate node is stateless from the request perspective. Sessions are JWT-encoded (see [Identity & Access §1.2](../identity-and-access/README.md)); async cross-gate responses are routed via Postgres `LISTEN`/`NOTIFY` (see [Integrations §1.3](../integrations/README.md)). No shared in-memory cache, no in-process session store, no node-local state that another node would need to fail over. The gate scales horizontally by adding nodes behind a load balancer — no sticky sessions, no shard rebalancing.

### 1.2 12-factor compliance is non-negotiable

Configuration via environment variables only (no config files baked into the image). Secrets loaded at startup from a runtime secret store (Kubernetes Secret, Vault, etc.) — never in the container image. One process per container. Logs to stdout/stderr as structured ECS records (see [Observability §1.1](../observability/README.md)). Graceful shutdown on `SIGTERM` within a bounded drain window so the load balancer can deregister before traffic is dropped.

### 1.3 Health endpoints surface operational facts only

`/health/live` and `/health/ready` return shallow operational state: process is up (`live`), and dependencies (DB, eDelivery AP) are reachable (`ready`). They never return business data, never count rows, never trigger work. The endpoints are unauthenticated by design — a caller that can reach them already has network access; the response carries no PII or business secrets.

### 1.4 Archival is owned by CronManager, not by the gate process

The live DB carries every event ever written; non-latest rows are archived by [CronManager](https://github.com/Buerostack/CronManager) — a separate Quartz-based scheduler service running alongside the gate. CronManager calls a gate admin endpoint (`POST /api/v1/admin/archive`, authenticated by `ARCHIVE_OPS_TOKEN`) on schedule; that endpoint runs the archival sweep. The gate process **never** schedules its own jobs. This separation lets the gate stay stateless and lets operators centralise scheduling.

### 1.5 Capacity model is append-only-aware

Capacity planning assumes every state transition is a new row (no UPDATE). `consignments`, `identifiers`, `sessions`, `users`, `dataset_requests`, `follow_up_messages` all grow monotonically until archived. Disk and IOPS budgets are sized against this growth model, not against a steady-state row count.

### 1.6 Migrations via Liquibase only

Schema changes go through Liquibase changesets in `gate/db/changelog/`. No ad-hoc DDL, no in-application schema-mutation code. The v0 baseline is `docs/specs/db/schema.sql` — applied once against an empty database; every subsequent change is an additive Liquibase changeset. This satisfies the operational rule "the runtime never holds DDL privileges in production".
