# eFTI Gate v2.0 Diagrams

**Purpose**: Visual documentation for all system flows, states, and architecture.

**Format**: Mermaid diagrams (validate at https://mermaid.live)

**Source**: Based on the v2.0 spec set (`../`); references the actual gate code in `gate/src/` and `gate/db/` where applicable.

---

## Sequence Diagrams (16)

| # | File | Description | Epic |
|---|------|-------------|------|
| 1 | [seq-01-identifier-registration.mmd](seq-01-identifier-registration.mmd) | Platform registers identifier via POST /v1/identifiers/{datasetId} | [Epic 3](../../epics/epic_3_en.md) |
| 2 | [seq-02-identifier-search-local-only.mmd](seq-02-identifier-search-local-only.mmd) | Authority search — results found locally, no broadcast | [Epic 4](../../epics/epic_4_en.md) |
| 3 | [seq-03-identifier-search-broadcast.mmd](seq-03-identifier-search-broadcast.mmd) | Authority search — empty local results, broadcast to all ONLINE gates | [Epic 4](../../epics/epic_4_en.md) |
| 4 | [seq-04-identifier-search-no-results.mmd](seq-04-identifier-search-no-results.mmd) | Authority search — no results in any gate | [Epic 4](../../epics/epic_4_en.md) |
| 5 | [seq-05-dataset-request.mmd](seq-05-dataset-request.mmd) | Authority requests dataset by UIL (cross-gate, via eDelivery) | [Epic 5](../../epics/epic_5_en.md) |
| 6 | [seq-06-dataset-request-denied.mmd](seq-06-dataset-request-denied.mmd) | Platform denies or cannot serve dataset request (403/404/502) | [Epic 5](../../epics/epic_5_en.md) |
| 7 | [seq-07-dataset-upload.mmd](seq-07-dataset-upload.mmd) | Platform uploads or updates dataset XML (upsert) | [Epic 3](../../epics/epic_3_en.md) |
| 8 | [seq-08-identifier-expiration.mmd](seq-08-identifier-expiration.mmd) | CronManager triggers `POST /api/v1/admin/expire-identifiers`; gate INSERTs `status='inactive'` rows for cabotage-expired road consignments (Reg 2024/1942 Art 11(4)) | [Epic 5](../../epics/epic_5_en.md), [Epic 17](../../epics/epic_17_en.md), [Epic 26](../../epics/epic_26_en.md) |
| 9 | [seq-09-gate-ping.mmd](seq-09-gate-ping.mmd) | CronManager triggers `POST /api/v1/admin/ping-gates`; gate probes peer registry and INSERTs latest health rows | [Epic 6](../../epics/epic_6_en.md), [Epic 26](../../epics/epic_26_en.md) |
| 10 | [seq-10-platform-registration.mmd](seq-10-platform-registration.mmd) | Admin registers platform and creates platform user | [Epic 7](../../epics/epic_7_en.md) |
| 11 | [seq-11-authority-registration.mmd](seq-11-authority-registration.mmd) | Admin registers authority and creates user with subset validation | [Epic 8](../../epics/epic_8_en.md) |
| 12 | [seq-12-user-authentication.mmd](seq-12-user-authentication.mmd) | TARA OIDC authentication flow — JWT validation against TARA JWKS, denylist check, role/subset enforcement | [Epic 2](../../epics/epic_2_en.md), [Epic 23](../../epics/epic_23_en.md) |
| 13 | [seq-13-multi-platform-user.mmd](seq-13-multi-platform-user.mmd) | Multi-platform user restriction for identifier submission | [Epic 1](../../epics/epic_1_en.md) |
| 14 | [seq-14-gate-to-gate-search.mmd](seq-14-gate-to-gate-search.mmd) | Gate receives identifier query from remote gate and responds | [Epic 4](../../epics/epic_4_en.md), [Epic 10](../../epics/epic_10_en.md) |
| 15 | [seq-15-gate-registry-sync.mmd](seq-15-gate-registry-sync.mmd) | Admin adds gate, in-memory registry syncs across nodes via LISTEN/NOTIFY | [Epic 6](../../epics/epic_6_en.md) |
| 16 | [seq-16-mtls-fast-protocol.mmd](seq-16-mtls-fast-protocol.mmd) | Gate-to-gate fast protocol over mTLS (alternative to AS4 envelope) | [Epic 2](../../epics/epic_2_en.md), [Epic 10](../../epics/epic_10_en.md) |

## State Diagrams (5)

| # | File | Description | States | Epic |
|---|------|-------------|--------|------|
| 17 | [state-01-identifier-lifecycle.mmd](state-01-identifier-lifecycle.mmd) | Consignment identifier lifecycle | active → inactive → deleted | [Epic 3](../../epics/epic_3_en.md), [Epic 5](../../epics/epic_5_en.md) |
| 18 | [state-02-dataset-request.mmd](state-02-dataset-request.mmd) | Dataset request routing and outcome | Initiated → LocalFetch/RemoteForward → Approved/Denied/Error | [Epic 5](../../epics/epic_5_en.md) |
| 19 | [state-03-platform-status.mmd](state-03-platform-status.mmd) | Platform lifecycle | Active → Deleted | [Epic 7](../../epics/epic_7_en.md) |
| 20 | [state-04-authority-status.mmd](state-04-authority-status.mmd) | Authority lifecycle | Active → Deleted | [Epic 8](../../epics/epic_8_en.md) |
| 21 | [state-05-gate-health.mmd](state-05-gate-health.mmd) | Remote gate connection health | ONLINE → OFFLINE → DISABLED | [Epic 6](../../epics/epic_6_en.md) |

## Flowcharts (3)

| # | File | Description | Decision criterion | Epic |
|---|------|-------------|--------------------|------|
| 22 | [flow-01-search-broadcast-decision.mmd](flow-01-search-broadcast-decision.mmd) | When to broadcast vs. return local results only | `local.isEmpty() \|\| forceBroadcast` | [Epic 4](../../epics/epic_4_en.md) |
| 23 | [flow-02-authorization-check.mmd](flow-02-authorization-check.mmd) | Role-based authorization for all API endpoints | JWT validation → @Access annotation → role-specific RLS | [Epic 1](../../epics/epic_1_en.md), [Epic 14](../../epics/epic_14_en.md) |
| 24 | [flow-03-dataset-access-control.mmd](flow-03-dataset-access-control.mmd) | Dataset routing: local vs. remote gate, platform approval/denial | `UIL.gateId == thisGateId` → direct; else → eDelivery forward | [Epic 5](../../epics/epic_5_en.md) |

## Architecture Diagrams (2)

| # | File | Description | Components | Epic |
|---|------|-------------|------------|------|
| 25 | [arch-01-multi-node-deployment.mmd](arch-01-multi-node-deployment.mmd) | Multi-node cluster deployment | Load Balancer, Gate Nodes, PostgreSQL, LISTEN/NOTIFY, Background Jobs | [Epic 12](../../epics/epic_12_en.md) |
| 26 | [arch-02-gate-network.mmd](arch-02-gate-network.mmd) | EU-wide eFTI Gate network | National gates + eDelivery AS4 connections + local platforms/authorities | [Epic 6](../../epics/epic_6_en.md), [Epic 10](../../epics/epic_10_en.md) |

---

## How to view

- **GitHub**: renders Mermaid in `.md` files; for raw `.mmd` files, paste into [https://mermaid.live](https://mermaid.live).
- **VS Code**: install *Markdown Preview Mermaid Support*; open `.mmd` and use Preview.
- **IntelliJ IDEA**: install the *Mermaid* plugin.

## Naming conventions

- `seq-NN-*.mmd` — sequence diagrams (01–16)
- `state-NN-*.mmd` — state diagrams (01–05)
- `flow-NN-*.mmd` — flowcharts (01–03)
- `arch-NN-*.mmd` — architecture diagrams (01–02)
