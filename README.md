# eFTI Gate (EE) — KeMIT

This is the **eFTI Gate (EE)**: the **Estonian** national node of the EU electronic freight transport information network defined by [EU Regulation 2020/1056](https://eur-lex.europa.eu/eli/reg/2020/1056/oj). There is one eFTI Gate per Member State; this repository is the specification corpus for the Estonian one, maintained by KeMIT. The Gate registers freight identifiers, mediates dataset retrieval between certified eFTI Platforms and competent authorities, and bridges to peer national gates over eDelivery AS4. Full regulatory effect: **9 July 2027**.

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
| DB design rules | [`docs/specs/db/README.md`](docs/specs/db/README.md) | Append-only everywhere (no UPDATE, no DELETE); latest-row reads (`DISTINCT ON`); denormalised columns; archival by CronManager |
| Errors | [`docs/specs/errors.json`](docs/specs/errors.json) | RFC 7807 catalog, 36 codes with realistic payloads |
| Logging | [`docs/specs/logging-spec.md`](docs/specs/logging-spec.md) | ECS 8.x dotted-field taxonomy, `efti.*` namespace |
| Permissions | [`docs/specs/permissions-matrix.md`](docs/specs/permissions-matrix.md) | Endpoint × role matrix, RLS rules, subset enforcement |
| Transformations | [`docs/specs/data-transformations.md`](docs/specs/data-transformations.md) | XML ↔ DB ↔ JSON ↔ AS4 ↔ SSE conversions, denormalised-column mapping |
| Diagrams | [`docs/specs/diagrams/`](docs/specs/diagrams/) | 26 Mermaid diagrams (16 sequence / 5 state / 3 flow / 2 architecture) |
| Epics (canonical) | [`docs/epics/`](docs/epics/) | 26 epics across 9 themes; per-epic / per-theme files with acceptance criteria and a Mermaid mini-diagram |
| Reference architecture | [`docs/architecture/eFTI-Gate-Reference-Architecture.md`](docs/architecture/eFTI-Gate-Reference-Architecture.md) | Target architecture per EU 2020/1056, 2024/1942, 2024/2024, 2025/2243 |
| Non-functional contracts | [`docs/specs/non-functional.md`](docs/specs/non-functional.md) | SLOs / SLIs per surface, capacity model, deployment topology, pinned protocols and version floors, compliance retention |
| Deployment | [`docs/specs/deploy/`](docs/specs/deploy/) | Topology constraints; concrete Helm/k8s/compose artefacts deferred to implementation phase |
| XSDs | [`docs/efti-analysis/xsd/`](docs/efti-analysis/xsd/) | eFTI consignment + eDelivery XML schemas (used by epics 3, 10, 25) |

## Non-negotiable rules

1. **Append-only everywhere.** Every operational table is INSERT-only. No `UPDATE`, no `DELETE`, anywhere. The runtime `app` role has `SELECT, INSERT` only on every table — `UPDATE` and `DELETE` are not granted. Editing an entity means INSERTing a new row sharing the same logical identifier; the latest row by `created_at` is the current state. State transitions (gate ping, identifier expiry, password reset, status flip, token revocation, async-response consumption) are all INSERTs of a new row.
2. **Latest-row reads, no `JOIN`.** Reads use `SELECT DISTINCT ON (logical_id) … ORDER BY logical_id, created_at DESC`. Single-table. Search columns are denormalised onto `consignments` directly (`vehicle_plate`, `vehicle_country`, `mode`, `dangerous_goods`, `origin_country`, `destination_country`, `transport_date`); the no-`JOIN` rule holds for the hot path.
3. **Archival by CronManager.** The live database carries every event ever written; non-latest rows are moved to archival storage by [**CronManager**](https://github.com/Buerostack/CronManager) — a separate Quartz-based scheduler service deployed alongside the gate. CronManager calls a gate admin endpoint on schedule (e.g. nightly); that endpoint runs the archival sweep. See Epic 26.
4. **Content-agnostic gate.** The gate stores identifiers and routes queries; it does not parse, validate, transform, or enforce business logic on dataset payloads. Dataset content lives on the platform, not the gate.

## Repository layout

- **`docs/specs/`** — authoritative v2 specifications: OpenAPI, PostgreSQL schema, errors catalog, logging spec, permissions matrix, data transformations, 26 Mermaid diagrams.
- **`docs/epics/`** — 26 epics across 9 themes; the canonical specification surface.
- **`docs/architecture/`** — target reference architecture per EU Reg 2020/1056, 2024/1942, 2024/2024, 2025/2243.
- **`docs/planning/`** — KeMIT's LLM prompts (`PROMPT-00..09`) that drove the v2 specification generation, plus the executive summary explaining v1 gaps. Background only; not authoritative input.
- **`docs/efti-analysis/`** — surviving background material from Askend Estonia OÜ's procurement deliverable: broad project analysis (`1-analysis/`), ER diagram (`3-model/`), and the eFTI XML schemas (`xsd/`). Sections superseded by `docs/specs/` have been removed; the original full deliverable is preserved on the `kemit-ee/efti-gate-poc` repository under the `v0.2-askend-final` tag.

## Open issues

Honest list of what's known to be incomplete or deferred. None of these blocks an implementer from starting; they're items that vendor + KeMIT will wire up together during the implementation phase.

**Phase-2 deferred (intentional):**

- **Deployment artefacts — documented gap, not closed.** [`docs/specs/deploy/README.md`](docs/specs/deploy/README.md) explains *why* the directory ships no Helm chart values, Kubernetes manifests, `Dockerfile`, or `docker-compose` — the topology contract is binding, the YAML/manifest specifics are negotiable on day 1 of the engagement. The placeholder document closes the documentation gap; it does not produce the artefacts. The concrete deployment files shipped today are the three canonical CronManager job definitions: [`cronmanager-archive.yaml`](docs/specs/deploy/cronmanager-archive.yaml), [`cronmanager-expire.yaml`](docs/specs/deploy/cronmanager-expire.yaml), and [`cronmanager-ping-gates.yaml`](docs/specs/deploy/cronmanager-ping-gates.yaml).
- **Threat model.** No STRIDE-per-surface document yet; `docs/specs/permissions-matrix.md` covers the access-control side, but a proactive threat model (informed by Reg 2025/2243) is operator-supplied.
- **On-call runbook.** Alert thresholds (`db.pool < 2`, `heap > 80%` from `logging-spec.md` §2.3) are documented; the playbook for what an on-call engineer does when each fires is not.
- **Load-test plan.** `docs/specs/non-functional.md` §1 sets SLOs; the k6/JMeter scenarios that validate them against the topology in §3 are an implementation deliverable.
- **Capacity-plan re-validation.** §2 numbers are recalculated for the append-only growth model (every state transition is a new row); they remain first-pass estimates and should be re-derived against Test Fest 4+ data.
- **Archival worker implementation.** Epic 26 specifies the `POST /api/v1/admin/archive` contract, the `db_archiver` PostgreSQL role with `SELECT, DELETE` grants on operational tables (and `SELECT`-only on `audit_log`), and the canonical CronManager job YAML. The per-table archival sweep itself is an implementation deliverable.

**Coverage gaps in the spec itself:**

- **Epic ↔ artifact links remain sparse for errors and logging.** `errors.json` is cited by 3 / 26 epics; `logging-spec.md` by 2 / 26. Backfill would improve AC traceability but doesn't block implementation — every epic's request/response shape is anchored in OpenAPI which carries the canonical references.
- **Verbosity of `logging-spec.md`, `permissions-matrix.md`, `data-transformations.md`** was compacted in Phase 2 (~−45 % bytes); the field tables remain long because they document every ECS field / endpoint × role cell / XPath mapping. Further reduction would drop information; not recommended.

**Closed in this round** (cf. the second deep-dive review): API path-prefix drift, error catalog vs OpenAPI enum sync, schema field-name drift in transformations, two diagrams encoding v1 lifecycle rules, permissions matrix coverage gaps, epic AC contradictions with OpenAPI, FK CASCADE comment lies, OpenAPI orphan schema, migration tooling ambiguity. Subsequent rewrite to **append-only everywhere** (commit `2c0ce58`) replaced the original `change_history` design — the runtime `app` PostgreSQL role now has `SELECT, INSERT` only on every operational table, and Epic 26 introduces a separate `db_archiver` role for nightly CronManager-driven archival sweeps.

## Status

This repository contains the **specification corpus** for the eFTI Gate (EE). Implementation work follows once the spec is signed off.

`main` is the integration branch; tagged releases come from `main`. New work uses short-lived feature branches merged back to `main`. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the full workflow.

### Versioning

This is **v1.0** — the first public release. The internal "v2" naming visible in earlier branches and commits refers to KeMIT's second internal spec-generation pass and is independent of the public versioning. The Askend Estonia OÜ procurement deliverable that informed the consolidation, and KeMIT's earlier internal v1 pass, were never published. See [`CHANGELOG.md`](CHANGELOG.md) for the lineage.

## Maintainer

KeMIT — Keskkonnaministeeriumi Infotehnoloogiakeskus (Estonia)

- **Primary contact:** [@turnerrainer](https://github.com/turnerrainer) on GitHub.
- **Fallback:** `help@kemit.ee`.

## Authors and acknowledgements

The architecture and specification corpus are authored by Rainer Türner (KeMIT) — [@turnerrainer](https://github.com/turnerrainer) on GitHub, `rainer.turner@gmail.com` — building on the procurement-phase analysis delivered by Askend Estonia OÜ and the original PoC by Digilogistika Keskus. Drafts were produced with assistance from Anthropic Claude; AI assistants are tools, not authors. Copyright and licence remain with KeMIT. See [`AUTHORS`](AUTHORS) for full credits.

## Licence

This work is licensed under the [Business Source License 1.1 (BUSL 1.1)](LICENSE). BUSL 1.1 is **not an Open Source license**. Read, fork, study, modify, and non-production use are permitted today; production use — including operation of a national eFTI Gate by another Member State — requires a commercial licence from KeMIT (`help@kemit.ee`).

On the Change Date — **2030-05-19** — the Licensed Work automatically converts to the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0), and the rights granted under BUSL 1.1 terminate. See [`LICENSE`](LICENSE) for the full parameter block and licence text. Contributions are accepted under the [Contributor License Agreement](CLA.md).

`SPDX-License-Identifier: BUSL-1.1`
