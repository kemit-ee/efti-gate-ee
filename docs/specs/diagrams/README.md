# eFTI Gate v2.0 Diagrams

**Purpose**: Visual documentation for all system flows, states, and architecture.

**Format**: Mermaid diagrams (validate at https://mermaid.live)

**Source**: Based on actual gate source code in `gate/src/` and `gate/db/`

---

## Sequence Diagrams (15)

| # | File | Description | Epic Reference |
|---|------|-------------|----------------|
| 1 | [seq-01-identifier-registration.mmd](seq-01-identifier-registration.mmd) | Platform registers identifier via POST /identifiers/{datasetId} | Epic 1.5 |
| 2 | [seq-02-identifier-search-local-only.mmd](seq-02-identifier-search-local-only.mmd) | Authority search — results found locally, no broadcast | Epic 1.1 |
| 3 | [seq-03-identifier-search-broadcast.mmd](seq-03-identifier-search-broadcast.mmd) | Authority search — empty local results, broadcast to all ONLINE gates | Epic 1.1 |
| 4 | [seq-04-identifier-search-no-results.mmd](seq-04-identifier-search-no-results.mmd) | Authority search — no results in any gate | Epic 1.1 |
| 5 | [seq-05-dataset-request.mmd](seq-05-dataset-request.mmd) | Authority requests dataset by UIL (cross-gate, via eDelivery) | Epic 1.2 |
| 6 | [seq-06-dataset-request-denied.mmd](seq-06-dataset-request-denied.mmd) | Platform denies or cannot serve dataset request (403/404/502) | Epic 1.2 |
| 7 | [seq-07-dataset-upload.mmd](seq-07-dataset-upload.mmd) | Platform uploads or updates dataset XML (upsert) | Epic 1.5 |
| 8 | [seq-08-identifier-expiration.mmd](seq-08-identifier-expiration.mmd) | Background job marks consignments as delivered/expired | Epic 1.4 |
| 9 | [seq-09-gate-ping.mmd](seq-09-gate-ping.mmd) | Background job pings all enabled remote gates, updates status | Epic 2.1 |
| 10 | [seq-10-platform-registration.mmd](seq-10-platform-registration.mmd) | Admin registers platform and creates platform user | Epic 3.1 |
| 11 | [seq-11-authority-registration.mmd](seq-11-authority-registration.mmd) | Admin registers authority and creates authority user with subset validation | Epic 3.2 |
| 12 | [seq-12-user-authentication.mmd](seq-12-user-authentication.mmd) | API key authentication flow (Basic auth, sha256 verification) | Epic 3.3 |
| 13 | [seq-13-multi-platform-user.mmd](seq-13-multi-platform-user.mmd) | Multi-platform user restriction for identifier submission | Epic 3.1 |
| 14 | [seq-14-gate-to-gate-search.mmd](seq-14-gate-to-gate-search.mmd) | Gate receives identifier query from remote gate and responds | Epic 1.1 |
| 15 | [seq-15-gate-registry-sync.mmd](seq-15-gate-registry-sync.mmd) | Admin adds gate, in-memory registry syncs across nodes via pg_notify | Epic 2.1 |

---

## State Diagrams (5)

| # | File | Description | States Shown |
|---|------|-------------|--------------|
| 16 | [state-01-identifier-lifecycle.mmd](state-01-identifier-lifecycle.mmd) | Consignment identifier lifecycle | Registered → Active → Expired → Deleted |
| 17 | [state-02-dataset-request.mmd](state-02-dataset-request.mmd) | Dataset request routing and outcome | Initiated → LocalFetch/RemoteForward → Approved/Denied/Error |
| 18 | [state-03-platform-status.mmd](state-03-platform-status.mmd) | Platform lifecycle | Active → Deleted |
| 19 | [state-04-authority-status.mmd](state-04-authority-status.mmd) | Authority lifecycle | Active → Deleted |
| 20 | [state-05-gate-health.mmd](state-05-gate-health.mmd) | Remote gate connection health | ONLINE → OFFLINE → DISABLED → Deleted |

---

## Flowcharts (3)

| # | File | Description | Decision Criteria |
|---|------|-------------|-------------------|
| 21 | [flow-01-search-broadcast-decision.mmd](flow-01-search-broadcast-decision.mmd) | When to broadcast vs. return local results only | `local.isEmpty() || forceBroadcast` (EftiService.kt:91) |
| 22 | [flow-02-authorization-check.mmd](flow-02-authorization-check.mmd) | Role-based authorization for all API endpoints | sha256 secret check → @Access annotation → role-specific logic |
| 23 | [flow-03-dataset-access-control.mmd](flow-03-dataset-access-control.mmd) | Dataset routing: local vs. remote gate, platform approval/denial | UIL.gateId == thisGateId → direct platform call; else → eDelivery forward |

---

## Architecture Diagrams (2)

| # | File | Description | Components Shown |
|---|------|-------------|------------------|
| 24 | [arch-01-multi-node-deployment.mmd](arch-01-multi-node-deployment.mmd) | Multi-node cluster deployment | Load Balancer, Gate Nodes, PostgreSQL, pg_notify sync, Background Jobs |
| 25 | [arch-02-gate-network.mmd](arch-02-gate-network.mmd) | EU-wide eFTI Gate network | 7 national gates + eDelivery AS4 connections + local platforms/authorities |

---

## Key Business Logic Notes

### Broadcast-only-when-empty Pattern
```
// EftiService.kt line 91
if (q.forceBroadcast || local.isEmpty()) {
    gateRegistry.online().forEach { gate -> launch { ... } }
}
```
- If local results exist → return local only (unless `forceBroadcast=true`)
- If local results empty → always broadcast to all ONLINE gates

### Gate Status Enum (`gate/src/efti/domain/Status`)
- `ONLINE` — included in broadcasts and ping
- `OFFLINE` — excluded from broadcasts, ping continues
- `DISABLED` — excluded from broadcasts AND ping (manual admin action)

### Authentication
- Users stored in `users` table with `secretHash = sha256(secret)`
- Roles stored as JSONB: `{"AUTHORITY": ["aut-politsei-001"], "PLATFORM": ["plt-abc-001"]}`
- Multi-platform users cannot send identifier data (single-platform required)

---

## How to View

**Option 1: Mermaid Live Editor**
1. Open https://mermaid.live
2. Copy-paste `.mmd` file content
3. View rendered diagram

**Option 2: GitHub**
- GitHub automatically renders Mermaid in Markdown, but `.mmd` files require manual copy-paste

**Option 3: VS Code**
- Install "Markdown Preview Mermaid Support" extension
- Open `.mmd` file → Preview

**Option 4: IntelliJ IDEA**
- Install "Mermaid" plugin
- Open `.mmd` file → Preview tab

---

## Naming Conventions

- **seq-NN-*.mmd**: Sequence diagrams (numbered 01–15)
- **state-NN-*.mmd**: State diagrams (numbered 01–05)
- **flow-NN-*.mmd**: Flowcharts (numbered 01–03)
- **arch-NN-*.mmd**: Architecture diagrams (numbered 01–02)
