# eFTI Gate v2 — Specification Index

**Start here.** This is the entry point for building eFTI Gate v2.

eFTI Gate is a national node in the EU electronic freight transport information network (EU Regulation 2020/1056, fully applied 2027-07-09). It registers freight identifiers, mediates dataset retrieval between platforms and competent authorities, and bridges to peer gates over eDelivery AS4.

## Authoritative artifacts

| Artifact | Path | What it covers |
|---|---|---|
| OpenAPI 3.0 | [`docs/specs/openapi.yaml`](docs/specs/openapi.yaml) | Platform, Authority, Admin, Health, Auth APIs (RFC 7807 errors, JWT, pagination, audit, SSE) |
| DB schema | [`docs/specs/db/schema.sql`](docs/specs/db/schema.sql) | PostgreSQL 14+; every table and column carries `COMMENT ON …` |
| DB design rules | [`docs/specs/db/README.md`](docs/specs/db/README.md) | Persistence taxonomy (ledger/ephemeral/registry), denormalised reads, retention |
| Errors | [`docs/specs/errors.json`](docs/specs/errors.json) | RFC 7807 catalog, 35 codes with realistic payloads |
| Logging | [`docs/specs/logging-spec.md`](docs/specs/logging-spec.md) | ECS 8.x dotted-field taxonomy, `efti.*` namespace |
| Permissions | [`docs/specs/permissions-matrix.md`](docs/specs/permissions-matrix.md) | Endpoint × role matrix, RLS rules, subset enforcement |
| Transformations | [`docs/specs/data-transformations.md`](docs/specs/data-transformations.md) | XML ↔ DB ↔ JSON ↔ AS4 ↔ SSE conversions, denormalised-column mapping |
| Diagrams | [`docs/specs/diagrams/`](docs/specs/diagrams/) | 26 Mermaid diagrams (16 sequence / 5 state / 3 flow / 2 architecture) |
| Epics (canonical) | [`docs/efti_full_epics_en.md`](docs/efti_full_epics_en.md) | 25 epics across 9 themes, acceptance criteria |
| Epics (per-epic split) | [`docs/epics/`](docs/epics/) | Per-epic / per-theme files, each opens with a Mermaid mini-diagram |
| Reference architecture | [`docs/architecture/eFTI-Gate-Reference-Architecture.md`](docs/architecture/eFTI-Gate-Reference-Architecture.md) | Target architecture per EU 2020/1056, 2024/1942, 2024/2024, 2025/2243 |
| XSDs | [`docs/efti-analysis/xsd/`](docs/efti-analysis/xsd/) | eFTI consignment + eDelivery XML schemas (used by epics 3, 10, 25) |

## Non-negotiable rules

1. **Persistence taxonomy** (three classes — not blanket "append-only"):
   - **Ledger tables** (`change_history`, `audit_log`, `follow_up_log`) — truly immutable, INSERT-only. Enforced at the DB level via `BEFORE UPDATE OR DELETE` triggers that `RAISE EXCEPTION`.
   - **Ephemeral tables** (`request_id_cache`, `sessions`, `jobs_execution_log`) — INSERT-only at the application layer; rows age out via partition rotation (DDL) by a maintenance role, not via app-issued `DELETE`.
   - **Registry tables** (`gates`, `platforms`, `authorities`, `users`, `consignments`, `identifiers`) — `UPDATE` allowed (status transitions, `last_ping_at`, password resets, identifier expiry); `DELETE` never granted to the runtime `app` role; logical deletion uses status enums (`gates.status='DISABLED'`, `consignments.status='deleted'`). Every UPDATE is captured into `change_history` by an `AFTER UPDATE` trigger.
   - The runtime `app` role has `SELECT, INSERT` on every table plus `UPDATE` on registry tables only. `DELETE` is not granted to `app` on any table.
2. **Denormalised reads.** No `JOIN` in application hot paths. The `consignments` table carries all search columns directly (`vehicle_plate`, `vehicle_country`, `mode`, `dangerous_goods`, `origin_country`, `destination_country`, `transport_date`).
3. **Immutable audit.** `change_history`, `audit_log`, `follow_up_log` are locked at the DB level via `BEFORE UPDATE OR DELETE` trigger that `RAISE EXCEPTION`, plus `REVOKE UPDATE, DELETE FROM PUBLIC`.
4. **Content-agnostic gate.** The gate stores identifiers and routes queries; it does not parse, validate, transform, or enforce business logic on dataset payloads. Dataset content lives on the platform, not the gate.

## Background — do not use as primary input

- [`docs/planning/`](docs/planning/) — KeMIT's LLM prompts (`PROMPT-00-INDEX.md` and `PROMPT-01..09-*.md`) that drove the v2 spec generation, plus [`ASKEND-FEEDBACK-EXECUTIVE-SUMMARY.md`](docs/planning/ASKEND-FEEDBACK-EXECUTIVE-SUMMARY.md) explaining what was originally missing in v1.
- [`docs/efti-analysis/1-analysis/`](docs/efti-analysis/1-analysis/) — Askend's broad project analysis (goal, mission, scope, EU regulations); useful context for new readers but not authoritative.
- [`docs/efti-analysis/3-model/`](docs/efti-analysis/3-model/) — ER diagram supporting `docs/specs/db/schema.sql`.

Sections of Askend's analysis that the v2 specs supersede (`2-openapi/`, `4-rights-n-permissions/`, `5-errors-n-logging/`, `6-transformations/`, `7-diagrams/`, `8-codereview/`) have been pruned from this repo. The original full analysis is preserved on the upstream `kemit-ee/efti-gate` repo's `feature/v2` branch (`askend-baseline` tag).

## Known open issues

- **Epic ↔ artifact links are still sparse for errors and logging.** `errors.json` is cited by 2/25 epics, `logging-spec.md` by 2/25. Backfill needed for full AC traceability.
- **Verbosity of `logging-spec.md` and `permissions-matrix.md`** has been compacted in Phase 2 (~−45% bytes), but the field tables remain long because they document every ECS field / endpoint × role cell. Further reduction would need to drop information; not recommended.
