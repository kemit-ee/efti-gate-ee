# EPIC 9 — Consignment Management (Admin API)

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Registry Management](README.md). Architecture: [registry-management/README.md](../../architecture/registry-management/README.md) (theme-wide rules) + [registry-management/consignment_management.md](../../architecture/registry-management/consignment_management.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** system administrator
**I WANT** to view and manage stored consignment data
**SO THAT** I can audit data and remove erroneous records.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **API operations** | `GET /api/v1/consignments` |
| | `DELETE /api/v1/consignments/{datasetId}` (Super Admin only) |
| | `POST /api/v1/admin/expire-identifiers` (CronManager-triggered) |
| | Full request / response / error shapes: [`openapi.yaml`](../../specs/openapi.yaml) |
| **Schema** | `consignments` (append-only; latest row by `created_at` wins per `dataset_id`) |
| | `consignment_status` enum: `active`, `inactive`, `deleted` |
| | `audit_log` (≥ 7 years retention; never archived) |
| | Index `idx_consignments_dataset_latest (dataset_id, created_at DESC)` — canonical latest-row read path |
| | Full schema: [`db/schema.sql`](../../specs/db/schema.sql); read patterns: [`db/README.md`](../../specs/db/README.md) |
| **Data transformations** | XML → denormalised search columns: [`data-transformations.md`](../../specs/data-transformations.md) |
| **Error codes** | `BAD_REQUEST_GENERAL` |
| | `FORBIDDEN` |
| | `CONSIGNMENT_NOT_FOUND` |
| | `ARCHIVE_IN_PROGRESS` (concurrency-guard 409) |
| | Full catalog: [`errors.json`](../../specs/errors.json) |
| **CronManager YAML** | [`cronmanager-expire.yaml`](../../specs/deploy/cronmanager-expire.yaml) (default `0 45 3 * * ?` — 03:45 daily) |
| **Architecture** | [RA §3 Data Lifecycle](../../architecture/eFTI-Gate-Reference-Architecture.md#3-data-lifecycle--ownership) |
| | [RA §6.2 Data Processing Matrix](../../architecture/eFTI-Gate-Reference-Architecture.md#62-data-processing-matrix) |
| **Diagrams** | [`state-01-identifier-lifecycle.mmd`](../../specs/diagrams/state-01-identifier-lifecycle.mmd) |
| | [`seq-08-identifier-expiration.mmd`](../../specs/diagrams/seq-08-identifier-expiration.mmd) |
| **Related epic** | [Epic 26](../infrastructure/append_only_archival.md) — CronManager-driven append-only archival sweep |
| **Architecture** | [../../architecture/registry-management/README.md](../../architecture/registry-management/README.md) (theme rules) + [../../architecture/registry-management/consignment_management.md](../../architecture/registry-management/consignment_management.md) (sub-architecture) |

## Acceptance Criteria

### Viewing and deletion

**Business rules:**
- [ ] Listing: Super Admin sees all consignments; a regular Admin sees only consignments owned by gates in their `users.roles[ADMIN]` scope-IDs.
- [ ] List response returns the **latest** row per `dataset_id`, ordered by `created_at DESC`.
- [ ] `DELETE` is **Super Admin only**. It INSERTs a new `consignments` row carrying `status='deleted'` — the previous row remains in place (append-only).

**Denial scenarios:**
- [ ] Regular Admin attempts `DELETE` → forbidden.
- [ ] `DELETE` on a `dataset_id` whose latest row is already `deleted` → not found.

### Identifier-status lifecycle (Reg 2025/2243)

**Business rules:**
- [ ] States: `active` (searchable), `inactive` (historical queries only — returned only with explicit `status=inactive` or `status=all`), `deleted` (excluded from all default queries).
- [ ] Every state transition is an INSERT of a new `consignments` row sharing the same logical `dataset_id`. **No row is ever mutated.**
- [ ] Re-registration after a `deleted` row: new `active` row INSERTed; latest now reads `active`. The deleted row remains until archived.

### Retention and expiry (Reg 2024/1942)

**Business rules:**
- [ ] **Cabotage retention.** `mode='road'` consignments transition `active → inactive` 14 days after `transport_date`, per Reg 2024/1942 Art 11(4). Triggered by CronManager calling `POST /api/v1/admin/expire-identifiers`.
- [ ] Other transport modes: deactivated immediately after `delivered_at` (operator may choose to mark inactive at platform-DELETE time instead).
- [ ] **Audit log retention.** `audit_log` is preserved on the live DB **indefinitely** (≥ 7-year regulatory floor). It is **never archived**.
- [ ] **Non-latest consignments rows are archived nightly** by CronManager via `POST /api/v1/admin/archive` (Epic 26). After each sweep the live DB carries only the latest row per `dataset_id`.
- [ ] The gate must support an export of the 5-year monitoring report for the European Commission.

**Idempotency / safety:**
- [ ] `transport_date` not set or in the future → identifier remains `active`; the expiry sweep skips it.
- [ ] The expire-identifiers endpoint, like every CronManager-triggered admin endpoint, holds a cluster-wide mutex at handler entry; a concurrent invocation returns `409 ARCHIVE_IN_PROGRESS`.
- [ ] Each expiry sweep emits `event.action: "identifier.expire"` with `efti.expired_count` (per [`logging-spec.md`](../../specs/logging-spec.md) §5).

<!-- issue-body:end -->
