# EPIC 4 — Identifier Search (Authority API)

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Core Functionality](README.md). Architecture: [core-functionality/README.md](../../architecture/core-functionality/README.md) (theme-wide rules) + [core-functionality/identifier_search.md](../../architecture/core-functionality/identifier_search.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** competent authority officer
**I WANT** to search freight transport identifiers (e.g. by registration plate) across all EU gates
**SO THAT** I can verify a consignment's compliance with eFTI regulations.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **API operations** | `GET /v1/identifiers/{identifier}` (JSON + SSE variants) |
| | Full request / response / error shapes, query parameters (`modeCode`, `identifierTypes`, `registrationCountryCode`, `dangerousGoodsIndicator`, `dateFrom`, `dateTo`, `status`, `limit`, `offset`, `forceBroadcast`): [`openapi.yaml`](../../specs/openapi.yaml) |
| **Schema** | `consignments` (denormalised search columns: `vehicle_plate`, `vehicle_country`, `mode`, `dangerous_goods`, `origin_country`, `destination_country`, `transport_date`, `status`) |
| | Indexes: `idx_consignments_plate_trgm` (GIN trigram), `idx_consignments_status_active` (partial), `idx_identifiers_value_trgm` (GIN trigram) |
| | Full schema: [`db/schema.sql`](../../specs/db/schema.sql) |
| **Search query path** | XPath → column mapping that feeds the denormalised columns: [`data-transformations.md`](../../specs/data-transformations.md) |
| **Access-check rules** | Authority API role + subset filtering: [`permissions-matrix.md`](../../specs/permissions-matrix.md) |
| **Error codes** | `BAD_REQUEST_GENERAL` |
| | `FORBIDDEN` |
| | `FORBIDDEN_SUBSET` |
| | `GATE_TIMEOUT` |
| | `GATEWAY_UNAVAILABLE` |
| | Full catalog: [`errors.json`](../../specs/errors.json) |
| **Architecture** | [../../architecture/core-functionality/README.md](../../architecture/core-functionality/README.md) (theme rules) + [../../architecture/core-functionality/identifier_search.md](../../architecture/core-functionality/identifier_search.md) (sub-architecture) |
| | [RA §5.1 Identifier Query](../../architecture/eFTI-Gate-Reference-Architecture.md#51-identifier-query-cross-border-search) |
| | [RA §6.1 Gate Responsibilities](../../architecture/eFTI-Gate-Reference-Architecture.md#61-gate-responsibilities) |
| **Diagrams** | [`seq-02-identifier-search-local-only.mmd`](../../specs/diagrams/seq-02-identifier-search-local-only.mmd) |
| | [`seq-03-identifier-search-broadcast.mmd`](../../specs/diagrams/seq-03-identifier-search-broadcast.mmd) |
| | [`flow-01-search-broadcast-decision.mmd`](../../specs/diagrams/flow-01-search-broadcast-decision.mmd) |

## Acceptance Criteria

### Local search

**Business rules:**
- [ ] Hot-path query reads `consignments` directly via its denormalised search columns — **no JOIN to `identifiers`**. All filters apply at the database level.
- [ ] Default `status` filter is `active` (when the parameter is omitted). `status=inactive` returns cabotage-expired road consignments. `status=all` returns both.
- [ ] Empty result is a `200 OK` with an empty array, **not** a 404.
- [ ] Local DB query latency: p95 < 50 ms (per SLO; requires the trigram + partial indexes above).

**Denial scenarios:**
- [ ] `limit` exceeds 1000 (`PageLimit` upper bound).
- [ ] `dateFrom` after `dateTo`.
- [ ] Missing or invalid Bearer token.
- [ ] Authority user lacks the required search permission for the resolved scope.

### Cabotage control

**Business rules:**
- [ ] An `IdentifierExpirationJob` (CronManager-triggered) flips `consignments.status` from `active` to `inactive` 14 days after `transport_date` for `mode='road'` only — Reg 2024/1942 Art 11(4) retention window.
- [ ] State transition is by INSERT of a new `consignments` row carrying `status='inactive'`; the previous row stays in place (append-only).
- [ ] Each row in the response carries its own `status` field so the caller sees whether the record is currently `active` or `inactive`.
- [ ] The `dateFrom`/`dateTo` filter is general-purpose and applies regardless of `mode`; the cabotage rule itself is road-only. See [`seq-08-identifier-expiration.mmd`](../../specs/diagrams/seq-08-identifier-expiration.mmd).

### Broadcast to other gates

**Business rules:**
- [ ] Broadcast triggers **only** when local search returns 0 results (the broadcast-only-when-empty rule) — prevents unnecessary load and privacy exposure. `forceBroadcast=true` overrides for diagnostic use.
- [ ] Recipients: all gates with `gate_status='ONLINE'`. `OFFLINE` and `DISABLED` gates are skipped.
- [ ] Parallel fan-out, not sequential. Timeout per peer: 8 s (configurable via `BROADCAST_TIMEOUT_SECONDS`).
- [ ] Per-peer outcome (`gateId`, `responseTimeMs`, `success`, `failure`) is returned to the caller in the `failures[]` array on the final SSE event.
- [ ] Each gate interaction is logged: `gateId`, response time, outcome.
- [ ] A peer-gate failure does **not** cause a 5xx; the response is `200 OK` with whatever results came back + the `failures[]` array.

### SSE streaming

**Business rules:**
- [ ] `Accept: text/event-stream` → response is `Content-Type: text/event-stream`. `Accept: application/json` → all results returned together after all gates respond.
- [ ] Per peer-gate result: `event: gate`.
- [ ] Per consignment: `event: consignment` with `id: <UIL>`.
- [ ] Terminal event: `event: complete` so the client knows the stream is exhausted.
- [ ] Client disconnect mid-stream releases server-side resources (no leak).
- [ ] Stream open > 60 s (all peers timed out) → emit `event: complete` and close the connection.

<!-- issue-body:end -->
