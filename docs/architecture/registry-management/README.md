# Architecture: Registry Management

## Changes

- _Initial state. Change tracking begins at v1.0.0._
- **2026-10-08 — [ADR-015](../../architecture/decisions/015-registry-as-file-server.md): §1.1 and §1.5 rewritten again, §1.3 narrowed.**
  The gate/platform/authority registries no longer exist in the database at all: `gates`, `platforms`,
  `authorities` and their read models were dropped, and the `registry/**` folder is served over HTTP by
  the `registry` service. The platform credential is now plaintext in that folder (ADR-004 amended),
  so the folder is a secret store. Append-only and logical-deletion rules now apply only to
  `consignments` and `users`.
- **2026-10-08 — [ADR-014](../../architecture/decisions/014-registry-as-git-folder.md): §1.1 (and the sub-area links, §1.2 and §1.5) rewritten.** `gates`, `platforms` and `authorities` are no longer mutated through the Admin API at all: they are declared in the git folder `registry/**` and applied by the one-shot `registry-sync` container at startup. The Admin API write surface for those three registries, including `ping` and `api-key`, is deleted. Only `consignments` and `users` keep admin write endpoints, so §1.1, §1.2 and §1.5 now apply to those.

> Theme-wide architectural rules. Every sub-area below — and every Acceptance Criterion (AC) it carries — must derive from or at minimum **not conflict with** the rules stated here. AC live in the corresponding sub-area files under [`docs/cfr/registry-management/`](../../cfr/registry-management/); this document describes the *contract those AC implement*.

> **`registry/**` is a secret store since ADR-015**: platform entries carry a plaintext `apiKey`, so the
> folder must be mounted from a Secret and the `registry` service must never be exposed.

**System-wide reference:** [eFTI Gate Reference Architecture](../eFTI-Gate-Reference-Architecture.md). This document narrows the system-wide rules to the Registry Management surface.

**Sub-architectures in this theme** (each is the architectural surface for the AC tracked in the linked epic):

- [Gate Registry Management (Configuration-Driven, no database)](gate_registry.md) — AC: [`docs/cfr/registry-management/gate_registry.md`](../../cfr/registry-management/gate_registry.md)
- [Platform Registry Management (Configuration-Driven, no database)](platform_registry.md) — AC: [`docs/cfr/registry-management/platform_registry.md`](../../cfr/registry-management/platform_registry.md)
- [Authority Registry Management (Configuration-Driven, no database)](authority_registry.md) — AC: [`docs/cfr/registry-management/authority_registry.md`](../../cfr/registry-management/authority_registry.md)
- [Consignment Management (Admin API)](consignment_management.md) — AC: [`docs/cfr/registry-management/consignment_management.md`](../../cfr/registry-management/consignment_management.md)

---

## Overarching rules

These are the cross-cutting invariants every sub-area in this theme derives from. AC bullets in the CFR files specialise them to specific endpoints, error codes, or DB state.

### 1.1 There is exactly one mutation path per registry — and it is not HTTP

The registries have **one** writer each, and no second way in:

| Registry | Sole mutation path | Authorisation |
|---|---|---|
| `gates`, `platforms`, `authorities` | the git folder `registry/**` — the only store; it is served read-only over HTTP by the `registry` service (`GET /<type>.json`, `GET /<type>/<id>.json`) and filtered by each consumer | PR review + the release process (ADR-015); the `registry` service is reachable only on the internal Compose network |
| `consignments` | admin-API write endpoints | TARA-issued JWT resolving to an active `users` row |
| `users` | admin-API write endpoints | TARA-issued JWT resolving to an active `users` row |

There are **no** SQL migrations that inject registry business data and **no** "seeder" jobs. The three
registries have no HTTP write path and no database representation at all — the admin `POST`/`PUT`/`DELETE`
routes for them, including `ping` and `api-key`, do not exist, and the tables were dropped. Read-only
`GET` remains, and the Admin UI is a read-only view over the same registry documents the runtime reads.

### 1.2 Authentication check on every admin write

Admin write operations — `consignments` and `users` — require the caller to be authenticated with a valid TARA-issued JWT that resolves to an active `users` row. Registry configuration has no caller to authenticate: its credential is the git history and its authorisation is code review (see §1.1).

### 1.3 Append-only for everything the database still holds

`consignments` and `users` are INSERT-only. Editing an entity means INSERTing a new row sharing the same
logical identifier; reads use the latest-row-by-`created_at` projection. The runtime `app` PostgreSQL role
has `SELECT, INSERT` only — no UPDATE/DELETE grants. CronManager-driven archival (Theme 5) moves non-latest
`consignments` rows to cold storage on schedule.

`gates`, `platforms` and `authorities` have **no table at all** since ADR-015 (§1.1), so nothing in this rule
applies to them: their history is git, their concurrency control is the review process, and their only
read path is the `registry` service.

### 1.4 Listing scope

A caller listing registry entities sees all rows. The intersection check happens at the application layer; no row-level security (RLS) policy is required for registry tables.

### 1.5 Logical deletion

For `consignments` there is no DELETE on the wire: the entity is removed by INSERTing a new row carrying
the terminal status `deleted`, hidden from default listings but retained for audit until CronManager
archives it. `users` uses `is_active`/`token_revoked_at` the same way.

For `gates`, `platforms` and `authorities` there is **no tombstone at all** (ADR-015): deleting the file
removes the entity, so lookups 404 and guards deny. There is no `DELETED` status to carry and no
reactivation guard to satisfy — the id and (for an authority) the `registryCode` are simply free again.

### 1.6 Multi-tenancy via scope-IDs, not separate schemas

A multi-tenant deployment uses scope-IDs to slice access (one admin per gate or per authority), not separate Postgres schemas or databases. This keeps the deployment topology simple (one DB cluster) and the access-check uniform across tenants.
