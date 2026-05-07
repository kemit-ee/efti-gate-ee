# EPIC 3 — Identifier Management (Platform API)

> Part of [Theme 2](theme_2_en.md)

**AS A** eFTI platform operator  
**I WANT** to register freight transport identifiers in the gate  
**SO THAT** competent authorities can search for them later

**References:**
- [DB Schema](../specs/db/README.md) — Database schema for identifiers and consignments
- [XSD](../efti-analysis/xsd/consignment-identifier.xsd) — Identifier XML schema
- [Data Transformations](../specs/data-transformations.md) — XML→DB extraction (XPath maps for denormalised columns)
- [OpenAPI](../specs/openapi.yaml) — `POST /v1/identifiers/{datasetId}` contract
- [RA §2.1 UIL](../architecture/eFTI-Gate-Reference-Architecture.md#21-uil-unique-identifier-locator) — UIL structure and identifier registration concepts
- [RA §2.2 Identifiers vs Datasets](../architecture/eFTI-Gate-Reference-Architecture.md#22-identifiers-vs-datasets) — What the gate stores vs what platforms store
- [RA §3 Data Lifecycle](../architecture/eFTI-Gate-Reference-Architecture.md#3-data-lifecycle--ownership) — Identifier lifecycle and ownership rules

**Registration flow at a glance:**

```mermaid
sequenceDiagram
    participant Platform
    participant Gate as eFTI Gate
    participant DB as PostgreSQL
    Platform->>Gate: POST /v1/identifiers/{datasetId}<br/>Authorization: Bearer <JWT><br/>Content-Type: application/xml<br/>X-Request-ID: <uuid>
    Gate->>Gate: Validate XSD (consignment-identifier.xsd)<br/>Check X-Request-ID dedup (10-min TTL)
    alt new (or updated) datasetId
        Gate->>DB: INSERT consignments + identifiers<br/>(status='active'; previous row set to 'inactive' if upsert)
        Gate-->>Platform: 200 OK
    else duplicate X-Request-ID within TTL
        Gate-->>Platform: 409 Conflict<br/>code: DUPLICATE_REQUEST_ID
    else XSD invalid
        Gate-->>Platform: 400 Bad Request<br/>code: INVALID_XML
    end
```

See `seq-01-identifier-registration.mmd` for full detail.

#### Acceptance Criteria

##### Registration

**Happy path:**
- [ ] `POST /v1/identifiers/{datasetId}` accepts XML body `Content-Type: application/xml`; valid per `consignment-identifier.xsd`; user has exactly 1 PLATFORM role → `200 OK`
- [ ] Re-sending same `datasetId` with updated data → upsert; previous version's `consignments.status` set to `inactive`; new row's `status='active'` → `200 OK`
- [ ] Stored searchable fields on `consignments`: `vehicle_plate`, `vehicle_country`, `transport_date`, `origin_country`, `destination_country`, `mode`, `dangerous_goods` (snake_case per `schema.sql`)
- [ ] Identifier types supported (`identifiers.identifier_type` enum): `means` (vehicle / transport unit), `equipment` (container / trailer), `carried` (cargo unit)
- [ ] Transport modes (`consignments.mode` enum, EU Reg 2024/2024 Annex I): `maritime` (XML modeCode=1), `rail` (=2), `road` (=3), `air` (=4), `multimodal` (=5) — see `data-transformations.md` §2.4 for the mapping

**Edge cases:**
- [ ] eFTI platform omits `vehicle_plate` (pre-registration) → record stored with empty `vehicle_plate`; subsequent `POST` with same `datasetId` adds/updates plate
- [ ] Search by plate does not return records where `vehicle_plate` is empty or null
- [ ] Multi-platform user (>1 PLATFORM role) sends without `platformId` → `400 Bad Request` with `"detail": "Multiple platforms detected: specify platformId parameter"`
- [ ] Multi-platform user specifies valid `platformId` → processed as single-platform for that platform
- [ ] `countryCode` not ISO 3166-1 alpha-2 (e.g. `"EST"`) → `400 Bad Request` with field-level error
- [ ] `datasetId` not UUID format → `400 Bad Request` with `"detail": "datasetId must be a valid UUID v4"`

**Error handling:**
- [ ] XML invalid against `consignment-identifier.xsd` → `400 Bad Request` with XSD validation error path and line number
- [ ] `X-Request-ID` header missing → `400 Bad Request` with `"detail": "X-Request-ID header is required"`
- [ ] `X-Request-ID` seen within 10 minutes (`request_id_cache` TTL) → `409 Conflict` with `code: DUPLICATE_REQUEST_ID` per `errors.json`
- [ ] Unknown eDelivery message type received → error returned to sender; not silently ignored; event logged WARN

**Technical constraints:**
- [ ] Identifiers stored in `identifiers` table: one consignment → multiple identifier rows (1:N)
- [ ] `X-Request-ID` deduplication uses the shared `request_id_cache` table — checked across all nodes; TTL 10 minutes (per `schema.sql` `request_id_cache.expires_at` default)
- [ ] Schema migrations MUST use Liquibase (matches Askend baseline). `docs/specs/db/schema.sql` is the v0 baseline applied once against an empty database; all subsequent changes go through Liquibase changesets at `gate/db/changelog/` — no custom migration scripts.
- [ ] Rationale: procurement requirement "Tarkvara tehnilise analüüsi nõuded"

**Technical artifacts:**
- [ ] OpenAPI: `POST /v1/identifiers/{datasetId}` — request body, all error responses
- [ ] DB schema: `consignments`, `identifiers` tables with FK indexes and English column comments
- [ ] XSD: `consignment-identifier.xsd`
