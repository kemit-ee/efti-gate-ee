# Architecture: Registry Management

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Theme-wide architectural rules. Every sub-area below — and every Acceptance Criterion (AC) it carries — must derive from or at minimum **not conflict with** the rules stated here. AC live in the corresponding sub-area files under [`docs/cfr/registry-management/`](../../cfr/registry-management/); this document describes the *contract those AC implement*.

**System-wide reference:** [eFTI Gate Reference Architecture](../eFTI-Gate-Reference-Architecture.md). This document narrows the system-wide rules to the Registry Management surface.

**Sub-architectures in this theme** (each is the architectural surface for the AC tracked in the linked epic):

- [Gate Registry Management (Admin API)](gate_registry.md) — AC: [`docs/cfr/registry-management/gate_registry.md`](../../cfr/registry-management/gate_registry.md)
- [Platform Registry Management (Admin API)](platform_registry.md) — AC: [`docs/cfr/registry-management/platform_registry.md`](../../cfr/registry-management/platform_registry.md)
- [Authority Registry Management (Admin API)](authority_registry.md) — AC: [`docs/cfr/registry-management/authority_registry.md`](../../cfr/registry-management/authority_registry.md)
- [Consignment Management (Admin API)](consignment_management.md) — AC: [`docs/cfr/registry-management/consignment_management.md`](../../cfr/registry-management/consignment_management.md)

---

## Overarching rules

These are the cross-cutting invariants every sub-area in this theme derives from. AC bullets in the CFR files specialise them to specific endpoints, error codes, or DB state.

### 1.1 Admin API is the only mutation path into registries

`gates`, `platforms`, `authorities`, and `consignments` (as admin-visible registry entities) are mutated **only** through admin-API write endpoints. There are no internal admin-bypass paths, no SQL migrations that inject business data, no "seeder" jobs that bypass the access-check pipeline. Every registry mutation flows through the same authentication check (see [Identity & Access §1.1 RBAC](../identity-and-access/user_management_and_rbac.md)).

### 1.2 Authentication check on every admin write

Admin write operations require the caller to be authenticated with a valid TARA-issued JWT that resolves to an active `users` row.

### 1.3 Append-only across all registry tables

Every registry table (`gates`, `platforms`, `authorities`, `consignments`) is INSERT-only. Editing a registry entity means INSERTing a new row sharing the same logical identifier. Reads use the latest-row-by-`created_at` projection. The runtime `app` PostgreSQL role has `SELECT, INSERT` only — no UPDATE/DELETE grants. CronManager-driven archival (Theme 5) moves non-latest rows to cold storage on schedule.

### 1.4 Listing scope

A caller listing registry entities sees all rows. The intersection check happens at the application layer; no row-level security (RLS) policy is required for registry tables.

### 1.5 Logical deletion via status flip

There is no DELETE on the wire. To "delete" a platform or a consignment, the admin INSERTs a new row carrying a terminal status (`platforms.is_active = FALSE`, `consignments.status = 'deleted'`). The row is hidden from default listings (`WHERE is_active = TRUE`) but remains in the table for audit until CronManager archives it.

### 1.6 Multi-tenancy via scope-IDs, not separate schemas

A multi-tenant deployment uses scope-IDs to slice access (one admin per gate or per authority), not separate Postgres schemas or databases. This keeps the deployment topology simple (one DB cluster) and the access-check uniform across tenants.
