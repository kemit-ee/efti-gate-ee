# eFTI Gate v2 — Specification Index

**Start here.** This is the entry point for building eFTI Gate v2.

eFTI Gate is a national node in the EU electronic freight transport information network (EU Regulation 2020/1056, fully applied 2027-07-09). It registers freight identifiers, mediates dataset retrieval between platforms and competent authorities, and bridges to peer gates over eDelivery AS4.

## Authoritative artifacts

| Artifact | Path | What it covers |
|---|---|---|
| OpenAPI 3.0 | [`../specs/openapi.yaml`](../specs/openapi.yaml) | Platform, Authority, Admin, Health APIs (RFC 7807 errors, JWT, pagination, audit, SSE) |
| DB schema | [`../specs/db/schema.sql`](../specs/db/schema.sql) | PostgreSQL 14+; every table and column carries `COMMENT ON …` |
| DB design rules | [`../specs/db/README.md`](../specs/db/README.md) | Append-only, denormalised reads, immutable audit |
| Errors | [`../specs/errors.json`](../specs/errors.json) | RFC 7807 catalog, 35 codes with realistic payloads |
| Logging | [`../specs/logging-spec.md`](../specs/logging-spec.md) | ECS 8.x dotted-field taxonomy, `efti.*` namespace |
| Permissions | [`../specs/permissions-matrix.md`](../specs/permissions-matrix.md) | Endpoint × role, subset filtering, multi-platform users |
| Transformations | [`../specs/data-transformations.md`](../specs/data-transformations.md) | XML ↔ DB ↔ JSON ↔ AS4 ↔ SSE conversions |
| Diagrams | [`../specs/diagrams/`](../specs/diagrams/) | 25 Mermaid diagrams (15 seq / 5 state / 3 flow / 2 arch) |
| Epics (canonical) | [`../efti_full_epics_en.md`](../efti_full_epics_en.md) | 25 epics across 9 themes, acceptance criteria |
| Epics (split) | [`../epics/`](../epics/) | Per-epic and per-theme files for direct linking |
| Reference architecture | [`../architecture/eFTI-Gate-Reference-Architecture.md`](../architecture/eFTI-Gate-Reference-Architecture.md) | Target architecture per EU 2020/1056, 2024/1942, 2024/2024, 2025/2243 |

## Non-negotiable rules

1. **Persistence taxonomy** (three classes — not blanket "append-only"):
   - **Ledger tables** (`change_history`, `audit_log`, `follow_up_log`) — truly immutable, INSERT-only. Enforced at the DB level via `BEFORE UPDATE OR DELETE` triggers that `RAISE EXCEPTION`.
   - **Ephemeral tables** (`request_id_cache`, `sessions`, `jobs_execution_log`) — INSERT-only at the application layer; rows age out via partition rotation (DDL) by a maintenance role, not via app-issued `DELETE`.
   - **Registry tables** (`gates`, `platforms`, `authorities`, `users`, `consignments`, `identifiers`) — `UPDATE` allowed (status transitions, `last_ping_at`, password resets, identifier expiry); `DELETE` never granted to the runtime `app` role; logical deletion uses status enums (`gates.status='DISABLED'`, `consignments.status='deleted'`). Every UPDATE is captured into `change_history` by an `AFTER UPDATE` trigger.
   - The runtime `app` role has `SELECT, INSERT` on every table plus `UPDATE` on registry tables only. `DELETE` is not granted to `app` on any table.
2. **Denormalised reads.** No `JOIN` in application hot paths. The `consignments` table carries all search columns directly (`vehicle_plate`, `vehicle_country`, `mode`, `dangerous_goods`, `origin_country`, `destination_country`, `transport_date`).
3. **Immutable audit.** `change_history` is locked at the DB level via `RULE … DO INSTEAD NOTHING` plus `REVOKE UPDATE, DELETE FROM PUBLIC`.
4. **Content-agnostic gate.** The gate stores identifiers and routes queries; it does not parse, validate, transform, or enforce business logic on dataset payloads. Dataset content lives on the platform, not the gate.

## Background — do not use as primary input

- [`../efti-analysis/`](../efti-analysis/) — Askend's procurement-deliverable analysis (hange 303988). Each section forward-points to the corresponding `specs/` artifact. Read for "why", not "what".
- [`./PROMPT-00-INDEX.md`](./PROMPT-00-INDEX.md) and `PROMPT-01..09` in this directory — KeMIT's LLM prompts that drove the spec generation, plus [`./ASKEND-FEEDBACK-EXECUTIVE-SUMMARY.md`](./ASKEND-FEEDBACK-EXECUTIVE-SUMMARY.md) explaining what was originally missing in v1.
- [`../access.md`](../access.md), [`../architecture.md`](../architecture.md), [`../code-style.md`](../code-style.md), [`../performance-documentation.md`](../performance-documentation.md), [`../performance-report.md`](../performance-report.md), [`../results/`](../results/) — PoC documentation; useful as architectural reference, superseded for v2 by the artifacts above.

## Known open issues (Phase-1 follow-ups)

- **Epic ↔ diagram links are broken.** Several epics cite `.mmd` filenames that do not exist (e.g. `seq-04-identifier-search-local.mmd`). Needs a global rename pass.
- **`data-transformations.md` is orphaned** from the epic graph. Needs links from epics 3, 4, 5, 10, 25.
- **Epic ↔ artifact links are sparse.** `errors.json` is cited by 2/25 epics, `logging-spec.md` by 2/25. Backfill needed for AC traceability.
- *(closed)* Phantom-table issues resolved: `follow_up_log` and `audit_log` added to `schema.sql`; previously-named `request_ids`, `user_roles`, `party_ids` reconciled in step 4.
- **Verbosity** in errors/logging/transformations/permissions is acknowledged. Trimming is Phase-2 polish, not a Phase-1 blocker.
