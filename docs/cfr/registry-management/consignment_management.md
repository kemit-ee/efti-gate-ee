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
| | `POST /ops/v1/expire-identifiers` (CronManager-triggered) |
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
| **CronManager YAML** | `efti_gate_expire_identifiers` in [`DSL/CronManager/efti-ops.yaml`](../../../DSL/CronManager/efti-ops.yaml) (`0 45 * * * ?` — hourly at :45, at most 1000 per run) |
| **Architecture** | [../../architecture/registry-management/README.md](../../architecture/registry-management/README.md) (theme rules) + [../../architecture/registry-management/consignment_management.md](../../architecture/registry-management/consignment_management.md) (sub-architecture) |
| | [RA §3 Data Lifecycle](../../architecture/eFTI-Gate-Reference-Architecture.md#3-data-lifecycle--ownership) |
| | [RA §6.2 Data Processing Matrix](../../architecture/eFTI-Gate-Reference-Architecture.md#62-data-processing-matrix) |
| **Diagrams** | [`state-01-identifier-lifecycle.mmd`](../../specs/diagrams/state-01-identifier-lifecycle.mmd) |
| | [`seq-08-identifier-expiration.mmd`](../../specs/diagrams/seq-08-identifier-expiration.mmd) |
| **Related epic** | [Epic 26](../infrastructure/append_only_archival.md) — CronManager-driven append-only archival sweep |

## Acceptance Criteria

### Viewing and deletion

**Business rules:**
- [ ] Listing: all consignments are visible to authenticated admins.
- [ ] List response returns the **latest** row per `dataset_id`, ordered by `created_at DESC`.
- [ ] `DELETE` is INSERTs a new `consignments` row carrying `status='deleted'` — the previous row remains in place (append-only).

**Denial scenarios:**
- [ ] `DELETE` on a `dataset_id` whose latest row is already `deleted` → not found.

### Identifier-status lifecycle (Reg 2025/2243)

**Business rules:**
- [ ] States: `active` (searchable), `inactive` (historical queries only — returned only with explicit `status=inactive` or `status=all`), `deleted` (excluded from all default queries).
- [ ] Every state transition is an INSERT of a new `consignments` row sharing the same logical `dataset_id`. **No row is ever mutated.**
- [ ] Re-registration after a `deleted` row: new `active` row INSERTed; latest now reads `active`. The deleted row remains until archived.

### Retention and expiry (Reg 2024/1942)

**Business rules:**
- [ ] **Cabotage retention.** `mode='road'` consignments transition `active → inactive` 14 days after `transport_date`, per Reg 2024/1942 Art 11(4). Triggered by CronManager calling `POST /ops/v1/expire-identifiers`.
- [ ] Other transport modes: deactivated immediately after `delivered_at` (operator may choose to mark inactive at platform-DELETE time instead).
- [ ] **Audit log retention.** `audit_log` is preserved on the live DB **indefinitely** (≥ 7-year regulatory floor). It is **never archived**.
- [ ] **Non-latest consignments rows are archived nightly** by CronManager via `POST /api/v1/admin/archive` (Epic 26). After each sweep the live DB carries only the latest row per `dataset_id`.
- [ ] The gate must support an export of the 5-year monitoring report for the European Commission.

**Idempotency / safety:**
- [ ] `transport_date` not set or in the future → identifier remains `active`; the expiry sweep skips it.
- [ ] The expire-identifiers endpoint holds no mutex: it is one SQL statement, and two overlapping runs can at most append a second, identical `INACTIVE` row for a dataset, which no latest-row read can tell apart.
- [ ] Each expiry sweep (other than a `dryRun`) appends a `jobs_execution_log` row (`job_name = 'expire-identifiers'`, details `{candidates, expired, reason: "cabotage_14d"}`).

<!-- issue-body:end -->
