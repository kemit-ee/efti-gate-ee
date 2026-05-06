# eFTI Gate — Estonia (KeMIT)

Estonian implementation of the **eFTI Gate**: a national node in the EU electronic freight transport information network defined by [EU Regulation 2020/1056](https://eur-lex.europa.eu/eli/reg/2020/1056/oj). The Gate registers freight identifiers, mediates dataset retrieval between certified eFTI Platforms and competent authorities, and bridges to peer national gates over eDelivery AS4. Full regulatory effect: **9 July 2027**.

## Where to start

| You want to | Read |
|---|---|
| 1-page visual map (themes × epics) | [`PROJECT-OVERVIEW.md`](PROJECT-OVERVIEW.md) |
| Acceptance criteria, per epic | [`docs/epics/`](docs/epics/) (each epic opens with a Mermaid mini-diagram) |
| Reference architecture (target design, EU regs) | [`docs/architecture/eFTI-Gate-Reference-Architecture.md`](docs/architecture/eFTI-Gate-Reference-Architecture.md) |
| Authoritative artifact list | the table below |

## Authoritative artifacts

The implementation contracts. Build the new gate against these.

| Artifact | Path | What it covers |
|---|---|---|
| OpenAPI 3.0 | [`docs/specs/openapi.yaml`](docs/specs/openapi.yaml) | Platform, Authority, Admin, Health, Auth APIs (RFC 7807 errors, JWT, pagination, audit, SSE) |
| DB schema | [`docs/specs/db/schema.sql`](docs/specs/db/schema.sql) | PostgreSQL 14+; every table and column carries `COMMENT ON …` |
| DB design rules | [`docs/specs/db/README.md`](docs/specs/db/README.md) | Persistence taxonomy (ledger / ephemeral / registry), denormalised reads, retention |
| Errors | [`docs/specs/errors.json`](docs/specs/errors.json) | RFC 7807 catalog, 35 codes with realistic payloads |
| Logging | [`docs/specs/logging-spec.md`](docs/specs/logging-spec.md) | ECS 8.x dotted-field taxonomy, `efti.*` namespace |
| Permissions | [`docs/specs/permissions-matrix.md`](docs/specs/permissions-matrix.md) | Endpoint × role matrix, RLS rules, subset enforcement |
| Transformations | [`docs/specs/data-transformations.md`](docs/specs/data-transformations.md) | XML ↔ DB ↔ JSON ↔ AS4 ↔ SSE conversions, denormalised-column mapping |
| Diagrams | [`docs/specs/diagrams/`](docs/specs/diagrams/) | 26 Mermaid diagrams (16 sequence / 5 state / 3 flow / 2 architecture) |
| Epics (canonical) | [`docs/efti_full_epics_en.md`](docs/efti_full_epics_en.md) | 25 epics across 9 themes; acceptance criteria |
| Epics (per-epic split) | [`docs/epics/`](docs/epics/) | Per-epic / per-theme files; each opens with a Mermaid mini-diagram |
| Reference architecture | [`docs/architecture/eFTI-Gate-Reference-Architecture.md`](docs/architecture/eFTI-Gate-Reference-Architecture.md) | Target architecture per EU 2020/1056, 2024/1942, 2024/2024, 2025/2243 |
| XSDs | [`docs/efti-analysis/xsd/`](docs/efti-analysis/xsd/) | eFTI consignment + eDelivery XML schemas (used by epics 3, 10, 25) |

## Non-negotiable rules

1. **Persistence taxonomy** (three classes — not blanket "append-only"):
   - **Ledger tables** (`change_history`, `audit_log`, `follow_up_log`) — truly immutable, INSERT-only. Enforced at the DB level via `BEFORE UPDATE OR DELETE` triggers that `RAISE EXCEPTION`.
   - **Ephemeral tables** (`request_id_cache`, `sessions`, `jobs_execution_log`) — INSERT-only at the application layer; rows age out via partition rotation (DDL) by a maintenance role, not via app-issued `DELETE`.
   - **Registry tables** (`gates`, `platforms`, `authorities`, `users`, `consignments`, `identifiers`) — `UPDATE` allowed (status transitions, `last_ping_at`, password resets, identifier expiry); `DELETE` never granted to the runtime `app` role; logical deletion uses status enums (`gates.status='DISABLED'`, `consignments.status='deleted'`). Every `UPDATE` is captured into `change_history` by an `AFTER UPDATE` trigger.
   - The runtime `app` role has `SELECT, INSERT` on every table plus `UPDATE` on registry tables only. `DELETE` is not granted to `app` on any table.
2. **Denormalised reads.** No `JOIN` in application hot paths. The `consignments` table carries all search columns directly (`vehicle_plate`, `vehicle_country`, `mode`, `dangerous_goods`, `origin_country`, `destination_country`, `transport_date`).
3. **Immutable audit.** `change_history`, `audit_log`, `follow_up_log` are locked at the DB level via `BEFORE UPDATE OR DELETE` trigger that `RAISE EXCEPTION`, plus `REVOKE UPDATE, DELETE FROM PUBLIC`.
4. **Content-agnostic gate.** The gate stores identifiers and routes queries; it does not parse, validate, transform, or enforce business logic on dataset payloads. Dataset content lives on the platform, not the gate.

## Repository layout

- **`docs/specs/`** — authoritative v2 specifications: OpenAPI, PostgreSQL schema, errors catalog, logging spec, permissions matrix, data transformations, 26 Mermaid diagrams.
- **`docs/epics/`** plus **`docs/efti_full_epics_{en,et}.md`** — 25 epics across 9 themes (English + Estonian canonical aggregate; English split per epic).
- **`docs/architecture/`** — target reference architecture per EU Reg 2020/1056, 2024/1942, 2024/2024, 2025/2243.
- **`docs/planning/`** — KeMIT's LLM prompts (`PROMPT-00..09`) that drove the v2 specification generation, plus the executive summary explaining v1 gaps. Background only; not authoritative input.
- **`docs/efti-analysis/`** — surviving background material from Askend Estonia OÜ's procurement deliverable: broad project analysis (`1-analysis/`), ER diagram (`3-model/`), and the eFTI XML schemas (`xsd/`). Sections superseded by `docs/specs/` have been removed; original full analysis preserved on the `kemit-ee/efti-gate` repo's `feature/v2` branch (`askend-baseline` tag).

## Open issues

- **Epic ↔ artifact links are still sparse for errors and logging.** `errors.json` is cited by 2 / 25 epics, `logging-spec.md` by 2 / 25. Backfill needed for full acceptance-criterion traceability.
- **Verbosity of `logging-spec.md` and `permissions-matrix.md`** has been compacted (~−45 % bytes), but the field tables remain long because they document every ECS field / endpoint × role cell. Further reduction would drop information; not recommended.

## Status

This repository contains the **specification corpus** for the v2 eFTI Gate. Implementation work follows once the spec is signed off.

The `feature/planning` branch carries the active consolidation work; `main` is the integration branch.

## Maintainer

KeMIT — Riigi Infosüsteemi Amet (Estonia)
Contact: `help@kemit.ee`

## Licence

This work is licensed under [Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)](https://creativecommons.org/licenses/by-nc/4.0/). Free to share and adapt for non-commercial use, with attribution to KeMIT, Estonia.

**Commercial use requires a separate licence.** Contact `help@kemit.ee` with the intended use case, the licensee entity, and the geographic scope. See [`LICENSE`](LICENSE) for the full terms.
