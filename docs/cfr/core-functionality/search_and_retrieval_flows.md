# EPIC 24 — Identifier Search and Dataset Retrieval Flows

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Core Functionality](README.md). Architecture: [core-functionality/README.md](../../architecture/core-functionality/README.md) (theme-wide rules) + [core-functionality/search_and_retrieval_flows.md](../../architecture/core-functionality/search_and_retrieval_flows.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** technical architect
**I WANT** documented data flows with sequence diagrams
**SO THAT** developers and integration partners understand exactly how identifier search, broadcast, and dataset retrieval works.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **Underlying epics** | Epic 3 (Identifier registration), Epic 4 (Identifier search), Epic 5 (Dataset retrieval & follow-up). This epic provides the **visual** companion. |
| **API operations** | All routes shown in the diagrams: [`openapi.yaml`](../../specs/openapi.yaml) |
| **Schema** | `consignments`, `identifiers` — see [`db/schema.sql`](../../specs/db/schema.sql) |
| **Companion mermaid files** | [`seq-01-identifier-registration.mmd`](../../specs/diagrams/seq-01-identifier-registration.mmd) |
| | [`seq-02-identifier-search-local-only.mmd`](../../specs/diagrams/seq-02-identifier-search-local-only.mmd) |
| | [`seq-03-identifier-search-broadcast.mmd`](../../specs/diagrams/seq-03-identifier-search-broadcast.mmd) |
| | [`seq-04-identifier-search-no-results.mmd`](../../specs/diagrams/seq-04-identifier-search-no-results.mmd) |
| | [`seq-05-dataset-request.mmd`](../../specs/diagrams/seq-05-dataset-request.mmd) |
| | [`seq-06-dataset-request-denied.mmd`](../../specs/diagrams/seq-06-dataset-request-denied.mmd) |
| | [`flow-01-search-broadcast-decision.mmd`](../../specs/diagrams/flow-01-search-broadcast-decision.mmd) |
| **Architecture** | [../../architecture/core-functionality/README.md](../../architecture/core-functionality/README.md) (theme rules) + [../../architecture/core-functionality/search_and_retrieval_flows.md](../../architecture/core-functionality/search_and_retrieval_flows.md) (sub-architecture) |
| | [RA §5.1 Identifier Query](../../architecture/eFTI-Gate-Reference-Architecture.md#51-identifier-query-cross-border-search) |
| | [RA §5.2 Dataset Query](../../architecture/eFTI-Gate-Reference-Architecture.md#52-dataset-query-request-full-data) |

<!-- issue-body:end -->
