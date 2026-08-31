# Changelog

All notable changes to the **eFTI Gate (EE)** specification corpus are documented here. *(There is one eFTI Gate per EU Member State; this corpus is the Estonian one, maintained by KeMIT.)* The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project applies [Semantic Versioning](https://semver.org/spec/v2.0.0.html) to the **specification surface** (OpenAPI operations, DB schema, error codes, permissions matrix, ECS log fields, AS4 envelopes).

## [Unreleased]

### Changed

- **Two Ruuter instances** ([ADR-005](docs/architecture/decisions/005-m2m-ruuter-split.md)) — `efti` (admin UI API + `auth/*`, admin JWT) and `ruuter-m2m` (renamed from `ruuter-xroad`): peer-gate eDelivery, Authority API, Platform API and X-Road, each under a guarded subdirectory. Admin POST endpoints keep their inline `check-admin-authority` call (Ruuter 0.9.x chains guards, so a non-public `POST/api/v1/.guard` would also block `auth/*`).
- **Platform API authentication** ([ADR-004](docs/architecture/decisions/004-platform-api-key.md)) — platforms authenticate with an `X-Api-Key` header stored as a SHA-256 hash, replacing the mTLS `cert_subject` design in the permissions matrix / OpenAPI. New `POST /api/v1/platforms/{id}/api-key` admin endpoint and Admin UI "Generate API key" flow (key shown once).

## [1.0.0] — 2026-05-18

First public release of the eFTI Gate (EE) specification corpus by KeMIT.

### Released artifacts

- **Epics** — 26 epics across 9 themes under [`docs/cfr/`](docs/cfr/), each with Spec anchors, business-rule-style acceptance criteria, and a mini-diagram.
- **OpenAPI 3.0** — Platform, Authority, Admin, Health, and Auth APIs ([`docs/specs/openapi.yaml`](docs/specs/openapi.yaml)). RFC 7807 errors, JWT + mTLS, pagination, audit, SSE.
- **PostgreSQL schema** — append-only everywhere; every column carries `COMMENT ON` ([`docs/specs/db/schema.sql`](docs/specs/db/schema.sql)).
- **Errors catalog** — 36 RFC 7807 codes with realistic payloads ([`docs/specs/errors.json`](docs/specs/errors.json)).
- **Logging spec** — ECS 8.x dotted-field taxonomy, `efti.*` namespace ([`docs/specs/logging-spec.md`](docs/specs/logging-spec.md)).
- **Permissions matrix** — endpoint × role, RLS rules, subset enforcement ([`docs/specs/permissions-matrix.md`](docs/specs/permissions-matrix.md)).
- **Data transformations** — XML ↔ DB ↔ JSON ↔ AS4 ↔ SSE conversions ([`docs/specs/data-transformations.md`](docs/specs/data-transformations.md)).
- **Diagrams** — 26 Mermaid diagrams: 16 sequence, 5 state, 3 flow, 2 architecture ([`docs/specs/diagrams/`](docs/specs/diagrams/)).
- **Reference architecture** — target architecture per EU Reg 2020/1056, 2024/1942, 2024/2024, 2025/2243 ([`docs/architecture/eFTI-Gate-Reference-Architecture.md`](docs/architecture/eFTI-Gate-Reference-Architecture.md)).
- **Non-functional contracts** — SLOs / SLIs per surface, capacity model, deployment topology, retention rules ([`docs/specs/non-functional.md`](docs/specs/non-functional.md)).

### Background and lineage

This release builds on internal work generations that were never published independently:

- **Askend Estonia OÜ procurement deliverable** *(predecessor; not published).* Askend was contracted under public procurement to deliver a full project analysis with initial epics and a data model for the eFTI Gate. Askend fulfilled that contract; their deliverable became the working baseline for KeMIT's subsequent consolidation. The surviving background material (broad project analysis, ER model, eFTI XML schemas) is preserved under [`docs/efti-analysis/`](docs/efti-analysis/); the original full deliverable is preserved on the `kemit-ee/efti-gate-poc` repository under the `v0.2-askend-final` tag (with the pre-Askend PoC state at `v0.1-pikker-digilogistika-baseline`).
- **KeMIT internal v1 consolidation** *(predecessor; not published).* First KeMIT pass over the Askend baseline — restructured the epic set and surfaced cross-references between epics and contract artifacts.
- **KeMIT internal v2 rewrite** *(this release's basis).* Rewrote the data model to **append-only everywhere** (operational tables INSERT-only, state transitions as new rows, archival by CronManager); generalised hardcoded identifiers; tightened acceptance criteria into the business-rule format used here; built the artifact-by-artifact cross-reference graph (Epic ↔ OpenAPI ↔ schema ↔ errors ↔ logging ↔ permissions); added the diagrams.

The v1.0 release is the **first** public-facing eFTI Gate (EE) specification corpus published by KeMIT. The internal "v2" naming refers to KeMIT's spec-generation lineage and is independent of this public versioning.
