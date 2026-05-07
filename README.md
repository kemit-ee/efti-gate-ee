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
| Non-functional contracts | [`docs/specs/non-functional.md`](docs/specs/non-functional.md) | SLOs / SLIs per surface, capacity model, deployment topology, pinned dependency versions, compliance retention |
| Deployment | [`docs/specs/deploy/`](docs/specs/deploy/) | Topology constraints; concrete Helm/k8s/compose artefacts deferred to implementation phase |
| XSDs | [`docs/efti-analysis/xsd/`](docs/efti-analysis/xsd/) | eFTI consignment + eDelivery XML schemas (used by epics 3, 10, 25) |

## Non-negotiable rules

1. **Append-only everywhere.** Every operational table is INSERT-only. No `UPDATE`, no `DELETE`, anywhere. The runtime `app` role has `SELECT, INSERT` only on every table — `UPDATE` and `DELETE` are not granted. Editing an entity means INSERTing a new row sharing the same logical identifier; the latest row by `created_at` is the current state. State transitions (gate ping, identifier expiry, password reset, status flip, token revocation, async-response consumption) are all INSERTs of a new row.
2. **Latest-row reads, no `JOIN`.** Reads use `SELECT DISTINCT ON (logical_id) … ORDER BY logical_id, created_at DESC`. Single-table. Search columns are denormalised onto `consignments` directly (`vehicle_plate`, `vehicle_country`, `mode`, `dangerous_goods`, `origin_country`, `destination_country`, `transport_date`); the no-`JOIN` rule holds for the hot path.
3. **Archival by CronManager.** The live database carries every event ever written; non-latest rows are moved to archival storage by [**CronManager**](https://github.com/Buerostack/CronManager) — a separate Quartz-based scheduler service deployed alongside the gate. CronManager calls a gate admin endpoint on schedule (e.g. nightly); that endpoint runs the archival sweep. See Epic 26.
4. **Content-agnostic gate.** The gate stores identifiers and routes queries; it does not parse, validate, transform, or enforce business logic on dataset payloads. Dataset content lives on the platform, not the gate.

## Repository layout

- **`docs/specs/`** — authoritative v2 specifications: OpenAPI, PostgreSQL schema, errors catalog, logging spec, permissions matrix, data transformations, 26 Mermaid diagrams.
- **`docs/epics/`** plus **`docs/efti_full_epics_{en,et}.md`** — 25 epics across 9 themes (English + Estonian canonical aggregate; English split per epic).
- **`docs/architecture/`** — target reference architecture per EU Reg 2020/1056, 2024/1942, 2024/2024, 2025/2243.
- **`docs/planning/`** — KeMIT's LLM prompts (`PROMPT-00..09`) that drove the v2 specification generation, plus the executive summary explaining v1 gaps. Background only; not authoritative input.
- **`docs/efti-analysis/`** — surviving background material from Askend Estonia OÜ's procurement deliverable: broad project analysis (`1-analysis/`), ER diagram (`3-model/`), and the eFTI XML schemas (`xsd/`). Sections superseded by `docs/specs/` have been removed; original full analysis preserved on the `kemit-ee/efti-gate` repo's `feature/v2` branch (`askend-baseline` tag).

## Open issues

Honest list of what's known to be incomplete or deferred. None of these blocks an implementer from starting; they're items that vendor + KeMIT will wire up together during the implementation phase.

**Phase-2 deferred (intentional):**

- **Deployment artefacts.** [`docs/specs/deploy/`](docs/specs/deploy/) describes the topology contract but ships no Helm chart values, Kubernetes manifests, or `docker-compose`. These are produced during implementation, after vendor selection.
- **Threat model.** No STRIDE-per-surface document yet; `docs/specs/permissions-matrix.md` covers the access-control side, but a proactive threat model (informed by Reg 2025/2243) is operator-supplied.
- **On-call runbook.** Alert thresholds (`db.pool < 2`, `heap > 80%` from `logging-spec.md` §2.3) are documented; the playbook for what an on-call engineer does when each fires is not.
- **Load-test plan.** `docs/specs/non-functional.md` §1 sets SLOs; the k6/JMeter scenarios that validate them against the topology in §3 are an implementation deliverable.
- **Capacity-plan revisions.** §2 numbers are first-pass estimates; re-derive after Test Fest 4+.
- **Partition rotation jobs.** `docs/specs/db/README.md` documents the per-table retention strategy; the `pg_partman` (or equivalent) configuration that enforces it is an operator-supplied piece.

**Coverage gaps in the spec itself:**

- **Epic ↔ artifact links remain sparse for errors and logging.** `errors.json` is cited by 3 / 25 epics; `logging-spec.md` by 2 / 25. Backfill would improve AC traceability but doesn't block implementation — every epic's request/response shape is anchored in OpenAPI which carries the canonical references.
- **Verbosity of `logging-spec.md`, `permissions-matrix.md`, `data-transformations.md`** was compacted in Phase 2 (~−45 % bytes); the field tables remain long because they document every ECS field / endpoint × role cell / XPath mapping. Further reduction would drop information; not recommended.

**Closed in this round** (cf. the second deep-dive review): API path-prefix drift, error catalog vs OpenAPI enum sync, schema field-name drift in transformations, consignments change-history trigger gap, two diagrams encoding v1 lifecycle rules, permissions matrix coverage gaps, epic AC contradictions with OpenAPI, FK CASCADE comment lies, OpenAPI orphan schema, migration tooling ambiguity.

## Status

This repository contains the **specification corpus** for the v2 eFTI Gate. Implementation work follows once the spec is signed off.

The `feature/planning` branch carries the active consolidation work; `main` is the integration branch.

## Maintainer

KeMIT — Riigi Infosüsteemi Amet (Estonia)
Contact: `help@kemit.ee`

## Licence

This work is licensed under [Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)](https://creativecommons.org/licenses/by-nc/4.0/). Free to share and adapt for non-commercial use, with attribution to KeMIT, Estonia.

**Commercial use requires a separate licence.** Contact `help@kemit.ee` with the intended use case, the licensee entity, and the geographic scope. See [`LICENSE`](LICENSE) for the full terms.
