# EPIC 4 — Identifier Search (Authority API)

> Part of [Theme 2](theme_2_en.md)

**AS A** competent authority officer  
**I WANT** to search freight transport identifiers (e.g. by registration plate) across all EU gates  
**SO THAT** I can verify a consignment's compliance with eFTI regulations

**References:**
- [DB Schema](../specs/db/README.md) — Database schema for identifier search
- [Permissions Matrix](../specs/permissions-matrix.md) — Authority access control rules
- [Data Transformations](../specs/data-transformations.md) — XML→DB extraction; denormalised search columns drive the no-JOIN query path
- [OpenAPI](../specs/openapi.yaml) — `GET /v1/identifiers/{identifier}` contract incl. SSE streaming and dateFrom/dateTo filters
- [Errors](../specs/errors.json) — Error codes for invalid plate / country / mode / subset
- [RA §5.1 Identifier Query](../architecture/eFTI-Gate-Reference-Architecture.md#51-identifier-query-cross-border-search) — Cross-border identifier search flow
- [RA §6.1 Gate Responsibilities](../architecture/eFTI-Gate-Reference-Architecture.md#61-gate-responsibilities) — Broadcast-only-when-empty rule

**Search decision at a glance:**

```mermaid
flowchart TD
    Q[GET /v1/identifiers/{identifier}<br/>Accept: text/event-stream] --> Local[Query identifiers table<br/>status=active, pg_trgm plate match]
    Local --> Count{local count > 0<br/>OR forceBroadcast?}
    Count -- local hits, no force --> SSEonly[SSE: stream local<br/>+ event: complete]
    Count -- empty or force --> Broadcast[Broadcast to ONLINE gates<br/>parallel, 8 s timeout]
    Broadcast --> Stream["SSE: gate, consignment, complete<br/>per-gate failures array"]
    SSEonly --> End([200 OK])
    Stream --> End
```

See `flow-01-search-broadcast-decision.mmd` and `seq-03-identifier-search-broadcast.mmd` for full detail.

#### Acceptance Criteria

##### Local search

**Happy path:**
- [ ] `GET /v1/identifiers/:identifier` searches `identifiers` table; all filters applied at database level: `modeCode`, `identifierTypes`, `registrationCountryCode`, `dangerousGoodsIndicator`
- [ ] Only identifiers with status `active` returned
- [ ] Results paginated: `limit` (default 20, max 100), `offset`; response includes `X-Total-Count`
- [ ] Empty result → `200 OK` with `{"identifiers": []}` — not `404`
- [ ] Local DB query response time < 50 ms at p95 (requires `pg_trgm` index)

**Edge cases:**
- [ ] `limit` exceeds 100 → `400 Bad Request` with `"detail": "limit must not exceed 100"`
- [ ] `dateFrom` after `dateTo` → `400 Bad Request` with `"detail": "dateFrom must be before dateTo"`
- [ ] `dateFrom`/`dateTo` without `modeCode=3` → `400 Bad Request` with `"detail": "dateFrom/dateTo requires modeCode=3"`

**Error handling:**
- [ ] Missing Bearer token → `401 Unauthorized` RFC 7807
- [ ] Authority user without search permission → `403 Forbidden` with `"detail": "Insufficient permissions for identifier search"`

**Technical constraints:**
- [ ] PostgreSQL 14+; MUST use `pg_trgm` extension for fuzzy plate search — performance requirement: < 50 ms local query
- [ ] DB index: `CREATE INDEX CONCURRENTLY idx_identifiers_plate_trgm ON identifiers USING GIN (vehicle_plate gin_trgm_ops)`

**Technical artifacts:**
- [ ] OpenAPI: `GET /v1/identifiers/{identifier}` — all query params, response schema, all error responses
- [ ] Diagram: `seq-02-identifier-search-local-only.mmd`

##### Cabotage control

**Happy path:**
- [ ] `dateFrom`–`dateTo` range filter returns `inactive` road transport (`modeCode=3`) records within date range
- [ ] Road transport UIL remains `inactive` for 14 days after `delivered_at` (art. 11 para. 4 Reg 2024/1942)
- [ ] Result list shows record status (`active` / `inactive`) per item

**Technical artifacts:**
- [ ] OpenAPI: `dateFrom`, `dateTo` query parameters on `GET /v1/identifiers/{identifier}`

##### Broadcast to other gates

**Happy path:**
- [ ] Broadcast triggered **only** when local search returns 0 results — prevents unnecessary load and privacy exposure
- [ ] Rationale: broadcast-only-when-empty pattern from Current Gate `EftiService.kt:91`
- [ ] Broadcast sends parallel requests to all gates with status `ACTIVE`; `DISABLED` and `OFFLINE` gates skipped
- [ ] Per-gate response metadata: `gateId`, `responseTimeMs`, `success`, `failure`
- [ ] Each gate interaction logged: gate ID, response time ms, success/failure

**Edge cases:**
- [ ] 3 of 15 active gates timeout after 8 seconds → partial results returned; timeout gates in `failures[]`; SSE stream still ends with `event: complete`
- [ ] All gates offline → `200 OK` with empty identifiers and populated `failures[]` — not a 5xx error
- [ ] One gate returns unexpected format → that gate marked `failure`; others unaffected

**Technical constraints:**
- [ ] Broadcast timeout: 8 seconds (configurable via `BROADCAST_TIMEOUT_SECONDS`)
- [ ] All active gates queried in parallel — not sequentially

**Technical artifacts:**
- [ ] Diagram: `seq-03-identifier-search-broadcast.mmd`

##### SSE (streaming)

**Happy path:**
- [ ] Request with `Accept: text/event-stream` → `Content-Type: text/event-stream` response
- [ ] Each gate's result: `event: gate` SSE event
- [ ] Each individual consignment: `event: consignment` with `id: <UIL>`
- [ ] Stream ends with `event: complete` — client knows all results delivered
- [ ] Without SSE (`Accept: application/json`) → all results returned together after all gates respond

**Edge cases:**
- [ ] Client disconnects mid-stream → gate stops sending and releases resources (no resource leak)
- [ ] Stream open > 60 seconds (all gates timed out) → `event: complete` sent; connection closed

**Technical artifacts:**
- [ ] OpenAPI: `GET /v1/identifiers/{identifier}` with `Accept: text/event-stream` variant documented
