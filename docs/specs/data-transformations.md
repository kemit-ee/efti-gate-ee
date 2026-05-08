# eFTI Gate v2.0 Data Transformations Specification

**Version**: 1.1 — Phase-2 compaction
**Date**: 2026-05-05
**Status**: Development-ready specification

---

## 1. Overview

The eFTI Gate handles four data formats and must transform between them precisely:

- **XML (eFTI identifier schema)** — `http://efti.eu/v1/consignment/identifier` — submitted by platforms; the Gate parses it to extract searchable metadata.
- **XML (eFTI eDelivery schema)** — `http://efti.eu/v1/edelivery` — wraps consignment XML for gate-to-gate AS4 messaging.
- **JSON (REST API)** — request/response bodies for the Gate's HTTP API (authority search results, admin management, SSE events).
- **SQL/PostgreSQL** — normalised storage in `consignments` and `identifiers` (plus denormalised search columns on `consignments`).

### 1.1 Pipeline overview

The four directions (XML→DB ingest, DB→JSON search results, DB→AS4 G2G wrap, platform passthrough) are summarised below:

```mermaid
graph LR
    PL[Platform] -->|"XML identifier schema<br/>(POST /v1/identifiers/{id})"| ING[XML→DB ingest<br/>EftiParser.parseIdentifiers]
    ING -->|INSERT consignments + identifiers<br/>denormalised search columns| DB[(PostgreSQL)]
    AUTH[Authority] -->|GET /v1/identifiers/{id}| Q[DB→JSON search<br/>ConsignmentRepository.find]
    Q --> DB
    DB -->|JAXB marshal +<br/>SSE / JSON| AUTH
    Q -.broadcast.-> G2G[DB→AS4 wrap<br/>EftiService.handleIdentifierQuery]
    G2G -->|"&lt;identifierResponse&gt;<br/>(eDelivery schema)"| RG[Remote gate]
    RG -.AS4 in.-> Q
    AUTH2[Authority] -->|GET /v1/dataset/...| PT[Platform passthrough<br/>EftiService.getDataset]
    PT -->|HTTP / AS4| PL2[Platform / Remote gate]
    PL2 -->|XML byte-for-byte| PT
    PT --> AUTH2
```

### 1.2 Key principle: the Gate is content-agnostic

The Gate does **not** validate or transform dataset XML content (the full CMDS payload stored on platforms). It only:

1. Parses the **identifier XML** submitted by platforms to extract searchable metadata.
2. Wraps/unwraps XML in eDelivery SOAP envelopes for G2G communication.
3. Passes dataset XML through unchanged from platform to authority.

---

## 2. eFTI identifier XML schema

### 2.1 Namespace and root element

```
Namespace:    http://efti.eu/v1/consignment/identifier
Root element: <consignment>
XSD:          https://github.com/EFTI4EU/reference-implementation/blob/main/schema/xsd/consignment-identifier.xsd
```

### 2.2 Schema → DB mapping (identifier extraction)

The identifier XML carries transport metadata used for searching. The Gate extracts and stores:

| XML path | Database column | Type | Required |
|---|---|---|---|
| `//mainCarriageTransportMovement[1]/modeCode` | `consignments.mode` | `transport_mode` enum | No |
| `//mainCarriageTransportMovement[1]/dangerousGoodsIndicator` | `consignments.dangerous_goods` | boolean | No |
| `//deliveryEvent/actualOccurrenceDateTime` | `consignments.delivered_at` | timestamptz | No |
| `//mainCarriageTransportMovement/usedTransportMeans/id` | `identifiers.identifier_value` (with `identifier_type='means'`) | varchar(200) | No |
| `//mainCarriageTransportMovement/usedTransportMeans/registrationCountry/code` | `identifiers.country_code` | char(2) | No |
| `//usedTransportEquipment/id` | `identifiers.identifier_value` (with `identifier_type='equipment'`) | varchar(200) | No |
| `//usedTransportEquipment/registrationCountry/code` | `identifiers.country_code` | char(2) | No |
| `//usedTransportEquipment/carriedTransportEquipment/id` | `identifiers.identifier_value` (with `identifier_type='carried'`) | varchar(200) | No |

> **Schema note.** `identifiers.id` is a UUID v4 primary key generated on INSERT (`uuid_generate_v4()`); it is **not** the identifier-value column. The XML-derived value (vehicle plate, container number, etc.) is stored in `identifiers.identifier_value`, with `identifiers.identifier_type` carrying the corresponding `means` / `equipment` / `carried` discriminator. Together `(identifier_value, identifier_type, country_code)` is the search-target.

### 2.3 Identifier types (`identifiers.identifier_type` enum)

| Enum | Description | Example |
|---|---|---|
| `means` | Vehicle registration plate from `usedTransportMeans/id` | `123ABC` |
| `equipment` | Container or trailer from `usedTransportEquipment/id` | `MSCU1234567` |
| `carried` | Nested equipment from `usedTransportEquipment/carriedTransportEquipment/id` | `TRLU9876543` |

### 2.4 Transport mode codes

Per EU Reg 2024/2024 Annex I; matches the `transport_mode` enum in `schema.sql`.

| Code | Description | `transport_mode` enum |
|---|---|---|
| `1` | Maritime (sea transport) | `maritime` |
| `2` | Rail (railway transport) | `rail` |
| `3` | Road (road transport — cabotage rules apply) | `road` |
| `4` | Air (air cargo) | `air` |
| `5` | Multimodal (combined transport) | `multimodal` |

### 2.5 Delivery datetime — `formatId` patterns

`actualOccurrenceDateTime` may use any of three format IDs (parsed in `ActualOccurrenceDateTime.instant`):

| `formatId` | Pattern | Example input | Stored as |
|---|---|---|---|
| `102` | `yyyyMMdd` | `20260423` | `2026-04-23T00:00:00Z` |
| `203` | `yyyyMMddHHmm` | `202604231015` | `2026-04-23T10:15:00Z` |
| `205` | `yyyyMMddHHmmxxxx` | `202604231015+0300` | `2026-04-23T07:15:00Z` |

---

## 3. Transformation scenarios

### 3.1 XML → Database (identifier extraction)

This is the **primary transformation** in the Gate. Performed in `EftiParser.parseIdentifiers()` using JAXB unmarshalling into `ConsignmentXml`.

**Pipeline (canonical for every ingest):**

1. `xml.dropXmlHeader()` — strip optional `<?xml?>` declaration before storage and parsing.
2. `ConsignmentXml.parse(xml)` — JAXB unmarshal.
3. Take `mainCarriageTransportMovement.firstOrNull()` as the primary movement.
4. Build `Consignment` (UIL + primary movement fields + denormalised search columns per §3.1.4).
5. Iterate **all** `mainCarriageTransportMovement` entries → extract `usedTransportMeans` → `Identifier(type=means)`.
6. Iterate `usedTransportEquipment` → `Identifier(type=equipment)`; nested `carriedTransportEquipment` → `Identifier(type=carried)`.
7. INSERT one row into `consignments`, N rows into `identifiers`.

If JAXB throws (`JAXBException` / `SAXParseException`), `EftiService.saveIdentifiers()` wraps it as `BadRequestException` and returns `INVALID_XML` — **no database write occurs**.

#### 3.1.1 End-to-end example A — Single vehicle, road transport

**Input XML** (POST `/v1/identifiers/550e8400-e29b-41d4-a716-446655440000`):

```xml
<consignment xmlns="http://efti.eu/v1/consignment/identifier">
  <mainCarriageTransportMovement>
    <dangerousGoodsIndicator>false</dangerousGoodsIndicator>
    <modeCode>3</modeCode>
    <usedTransportMeans>
      <id schemeAgencyId="6">123ABC</id>
      <registrationCountry><code>EE</code></registrationCountry>
    </usedTransportMeans>
  </mainCarriageTransportMovement>
  <deliveryEvent>
    <actualOccurrenceDateTime formatId="205">202604231015+0300</actualOccurrenceDateTime>
  </deliveryEvent>
</consignment>
```

**Output:**

```sql
INSERT INTO consignments (dataset_id, platform_id, gate_id, xml, mode, dangerous_goods, delivered_at, created_at)
VALUES ('550e8400-e29b-41d4-a716-446655440000', 'demo', 'eu-ee31',
        '<consignment xmlns="http://efti.eu/v1/consignment/identifier">...</consignment>',
        'road', false, '2026-04-23T07:15:00Z', now());
-- No `updated_at` column under the append-only schema. State changes write
-- a new row; the latest row by `created_at` is the current state.

-- identifiers.id is auto-generated UUIDv4 by uuid_generate_v4(); the XML-derived
-- vehicle plate is stored in identifier_value, the discriminator in identifier_type.
INSERT INTO identifiers (dataset_id, identifier_type, identifier_value, country_code)
VALUES ('550e8400-e29b-41d4-a716-446655440000', 'means', '123ABC', 'EE');
```

#### 3.1.2 End-to-end example B — Container + dangerous goods

**Input XML** (POST `/v1/identifiers/770fa622-a49d-53f6-c938-668877662222`):

```xml
<consignment xmlns="http://efti.eu/v1/consignment/identifier">
  <mainCarriageTransportMovement>
    <dangerousGoodsIndicator>true</dangerousGoodsIndicator>
    <modeCode>3</modeCode>
    <usedTransportMeans>
      <id schemeAgencyId="6">123ABC</id>
      <registrationCountry><code>EE</code></registrationCountry>
    </usedTransportMeans>
  </mainCarriageTransportMovement>
  <usedTransportEquipment>
    <id schemeAgencyId="6">MSCU1234567</id>
    <registrationCountry><code>EE</code></registrationCountry>
    <carriedTransportEquipment>
      <id schemeAgencyId="6">TRLU9876543</id>
    </carriedTransportEquipment>
  </usedTransportEquipment>
</consignment>
```

**Output:**

```sql
INSERT INTO consignments (dataset_id, platform_id, gate_id, xml, mode, dangerous_goods)
VALUES ('770fa622-a49d-53f6-c938-668877662222', 'demo', 'eu-ee31', '...xml...', 'road', true);

INSERT INTO identifiers (dataset_id, identifier_type, identifier_value, country_code) VALUES
  ('770fa622-a49d-53f6-c938-668877662222', 'means',     '123ABC',      'EE'),
  ('770fa622-a49d-53f6-c938-668877662222', 'equipment', 'MSCU1234567', 'EE'),
  ('770fa622-a49d-53f6-c938-668877662222', 'carried',   'TRLU9876543', NULL);
```

`carriedTransportEquipment` does **not** inherit the parent's country — `country_code` is NULL unless the carried element has its own `registrationCountry`.

#### 3.1.3 Additional ingest scenarios — what each one teaches

Every scenario uses the same canonical pipeline; only the rule listed below differs. No worked-example XML is repeated — start from §3.1.1 / §3.1.2 and apply the rule.

| Scenario | The one rule it teaches |
|---|---|
| Multiple `mainCarriageTransportMovement` (e.g. tractor + trailer) | Loop step 5 produces one row in `identifiers` (with `identifier_type='means'`) per movement; primary movement (step 3) drives `consignments.mode` / `dangerous_goods`. |
| XML missing optional fields (only `usedTransportMeans/id`) | All optional columns become `NULL` (`mode`, `dangerous_goods`, `country_code`, `delivered_at`); INSERT still succeeds. |
| `registrationCountry` missing on a `usedTransportMeans` | `identifiers.country_code = NULL` for that row. |
| `deliveryEvent` missing | `consignments.delivered_at = NULL`. |
| Malformed XML (e.g. unclosed `<modeCode>`) | JAXB throws → `BadRequestException` → 400 RFC 7807 with `efti.error.code = INVALID_XML`. **No DB write.** |
| XML with `<?xml version="1.0"?>` declaration | `dropXmlHeader()` strips the first line before parsing **and before storage** in `consignments.xml`. |
| `actualOccurrenceDateTime formatId="102"` / `"203"` / `"205"` | Parsed via the §2.5 format table; `consignments.delivered_at` stored in UTC. |
| Multi-leg (sea → road) transport | First-leg attributes go to denormalised columns (see §3.1.4); subsequent legs live only in the stored `xml`. |

#### 3.1.4 XML → denormalised search columns

The `consignments` table carries denormalised search columns that hold the
projection of XML fields most commonly filtered by authorities. The Gate
populates them on every INSERT (the table is append-only — every state
change is a new row); state transitions like `IdentifierExpirationJob`
flipping `status` to `inactive` also INSERT a new row, copying the prior
row's other columns. Authority search is a single-table read using
latest-row resolution per `dataset_id` (canonical read pattern in `db/README.md`); no JOINs.

| `consignments` column | XPath in the identifier XML | Notes |
|-----------------------|------------------------------|-------|
| `mode` | `mainCarriageTransportMovement[1]/modeCode` mapped via 2.4 table | First mode wins on multi-leg consignments; later legs reside in `xml` only. |
| `dangerous_goods` | `mainCarriageTransportMovement[*]/dangerousGoodsIndicator = "true"` (any leg) | Boolean OR across all legs. |
| `vehicle_plate` | `mainCarriageTransportMovement[1]/usedTransportMeans/id` (where `schemeAgencyId="6"`) | First leg's vehicle plate. NULL if the leg uses container/equipment instead of `means`. |
| `vehicle_country` | `mainCarriageTransportMovement[1]/usedTransportMeans/registrationCountry/code` | ISO 3166-1 alpha-2. |
| `origin_country` | `placeOfDeparture/locationCountrySubDivisionCode` (first 2 chars) OR `placeOfDeparture/locationCountryCode` | Normalised to ISO 3166-1 alpha-2 uppercase. |
| `destination_country` | `placeOfDelivery/locationCountrySubDivisionCode` (first 2 chars) OR `placeOfDelivery/locationCountryCode` | Same normalisation. |
| `transport_date` | `deliveryEvent/actualOccurrenceDateTime` parsed via formatId table above; truncated to date | Used by the `dateFrom`/`dateTo` filters on `GET /v1/identifiers/{identifier}`. |
| `expires_at` | NULL except for `mode = 'road'` — set to `transport_date + 14 days` per Reg 2024/1942 cabotage retention | Drives `IdentifierExpirationJob` to flip `status` from `active` to `inactive`. |

These columns are partial-indexed (`WHERE NOT NULL`) and the plate column
additionally has a `pg_trgm` GIN index for fuzzy lookups. Authority queries
should always WHERE on `consignments` directly — never JOIN to `identifiers`
which holds only the original XML fragment for round-trip retrieval.

---

### 3.2 Database → JSON (search results)

**Pipeline:** `EftiService.getLocalIdentifiers()` → `ConsignmentRepository.find(q)` → `ConsignmentXml.parse(c.xml)` (JAXB unmarshal stored XML) → set `uil = UIL(c.platformId, c.datasetId)` for local results (no `gateId`); set `identifierCountryOfOrigin = Config.countryCode` → JAXB marshal as `GateIdentifiersResponse` → JSON serialise.

For `Accept: text/event-stream`, the result is streamed as SSE events instead of a JSON array (see §3.2.2).

#### 3.2.1 Canonical example — Authority `GET /v1/identifiers/123ABC`

```json
[
  {
    "uil": {
      "platformId": "demo",
      "datasetId": "550e8400-e29b-41d4-a716-446655440000"
    },
    "mainCarriageTransportMovement": [
      {
        "dangerousGoodsIndicator": false,
        "modeCode": "3",
        "usedTransportMeans": {
          "id": { "value": "123ABC", "schemeAgencyId": "6" },
          "registrationCountry": { "code": "EE" }
        }
      }
    ],
    "identifierCountryOfOrigin": "EE"
  }
]
```

#### 3.2.2 SSE stream — broadcast variant

When the search broadcasts to peer gates, each gate response and each consignment is sent as a separate SSE event:

| SSE field | Content |
|---|---|
| `event: gate` / `data: {…GateIdentifiersResponse…}` | One per gate. `consignments` set to `null` (consignments are streamed separately). Includes `gateId`, `responseTimeMs`, `failure` (string or null). |
| `id: {platformId}/{datasetId}` / `data: {…ConsignmentXml…}` | One per result row. Includes the `uil` (with `gateId` for remote results) and `identifierCountryOfOrigin`. |
| `event: complete` / `data:` (empty) | Terminates the stream. |

When `Accept: application/json` is used and there are zero results, the response is an empty array `[]`. When `Accept: text/event-stream` is used and there are zero results, the SSE stream still emits one `event: gate` per queried gate followed by `event: complete`.

---

### 3.3 XML → AS4 (gate-to-gate eDelivery wrap)

eDelivery namespace `http://efti.eu/v1/edelivery`. The Gate parses incoming AS4 messages with JAXB (`IdentifiersQuery`, `UILQuery`, `FollowUpRequest`) and builds outgoing AS4 messages by string-concatenating the eDelivery wrapper around stored consignment XML.

**Helpers:** `dropXmlHeader()` (strip `<?xml?>` from any string before storage / wrapping); `dropXmlRoot()` (strip outer root element so the inner content can be re-embedded under a new wrapper).

#### 3.3.1 Canonical example — Identifier query response (Gate → Remote Gate)

`EftiService.handleIdentifierQuery()` builds, per matched consignment: drop the stored XML's outer `<consignment>` via `dropXmlRoot()`, embed inside a fresh `<ed:consignment>` wrapper with UIL metadata, collect into `<identifierResponse>`:

```xml
<identifierResponse status="200" requestId="550e8400-e29b-41d4-a716-446655440000"
                    xmlns="http://efti.eu/v1/edelivery">
  <ed:consignment xmlns="http://efti.eu/v1/consignment/identifier"
                  xmlns:ed="http://efti.eu/v1/edelivery">
    <mainCarriageTransportMovement>
      <dangerousGoodsIndicator>false</dangerousGoodsIndicator>
      <modeCode>3</modeCode>
      <usedTransportMeans>
        <id schemeAgencyId="6">123ABC</id>
        <registrationCountry><code>EE</code></registrationCountry>
      </usedTransportMeans>
    </mainCarriageTransportMovement>
    <ed:uil>
      <ed:gateId>eu-ee31</ed:gateId>
      <ed:platformId>demo</ed:platformId>
      <ed:datasetId>550e8400-e29b-41d4-a716-446655440000</ed:datasetId>
    </ed:uil>
  </ed:consignment>
</identifierResponse>
```

#### 3.3.2 Other AS4 message shapes (rule summary)

All variations use the same wrap/unwrap idiom; the table records each one's defining rule.

| Message | Direction | Rule |
|---|---|---|
| `<identifierQuery>` | Gate ← Remote gate | `IdentifiersQuery.parse()` (JAXB) → extract `requestId`, `identifier value`, `type`, optional filters → `ConsignmentRepository.find()` → build response per §3.3.1. |
| `<uilQuery>` | Gate ← Remote gate | `UILQuery.parse()` → extract UIL + `subsetId[]` (values are `EU01`..`EU07`) + `requestId` → forward to platform via `EftiService.getDataset()` → wrap response per `<uilResponse>` rule below. |
| `<uilResponse>` (success) | Gate → Remote gate | Platform body's `<?xml?>` declaration removed via `dropXmlHeader()`; entire body embedded under `<uilResponse status="200" requestId="…" xmlns="…/edelivery">…</uilResponse>`. |
| `<uilResponse>` (error) | Gate → Remote gate | If platform status ≠ 200 **and** the body does not start with `<`, wrap the body in `<description>…</description>` inside `<uilResponse status="404"…>`. |
| `<followUpRequest>` | Gate ← Remote gate | `FollowUpRequest.parse()` → guard `req.uil.gateId == Config.gateId` (else `FOLLOW_UP_GATE_MISMATCH` 400) → `sendFollowUp()` forwards `message` to platform via `PlatformClient.postFollowUp()`. |

---

### 3.4 Platform passthrough (dataset retrieval)

The Gate **does not** parse, validate, or transform dataset XML — it is byte-for-byte passthrough.

| Route | Outbound | Notes |
|---|---|---|
| Local platform | `GET {platform.baseUrl}/v1/datasets/{datasetId}?subsetId=EU01[&subsetId=EU07…]` with `X-Request-ID` and `{platform.headers}` (e.g. `X-Api-Key`) | Gate echoes platform's exact HTTP status + body; `Content-Type: application/xml`; `X-Request-ID` echoed on response. |
| Remote gate | AS4 `<uilQuery>` per §3.3.2 | Response is unwrapped from `<uilResponse>` — extract `@status` attribute and inner content; pair `(StatusCode, body)` returned to the route handler. |

Subset values on the wire are always the canonical `EU01`..`EU07` codes from `users.subsets` / `authorities.subsets`.

---

### 3.5 Error transformations

All transformation errors surface as RFC 7807 problem JSON with `type: "https://api.efti.ee/errors/<slug>"` and a required `code` field bound to the catalog enum. The complete catalog (36 codes, full payloads) is in `docs/specs/errors.json` — do not duplicate it here. The transformation-specific subset:

| Trigger | HTTP | `errorCode` (in `efti.error.code` log field) | Type slug |
|---|---|---|---|
| Malformed identifier XML / JAXB failure | 400 | `INVALID_XML` | `invalid-xml` |
| Request body > 10 MB | 400 (or 413) | `INVALID_XML` (size variant) | `bad-request` |
| Target gate not ONLINE | 502 | `GATEWAY_UNAVAILABLE` | `bad-gateway` |
| mTLS cert subject DN + serial resolves to >1 active `platforms` row (config error) | 403 | `FORBIDDEN_MULTI_PLATFORM` | `forbidden-multi-platform` |
| Follow-up `req.uil.gateId != Config.gateId` | 400 | `FOLLOW_UP_GATE_MISMATCH` | `follow-up-gate-mismatch` |
| Unhandled JAXB / NPE during transform | 500 | `TRANSFORMATION_ERROR` | `internal-error` |

**Never** echo the input XML in an error response — it may carry PII or sensitive cargo data. Log at most the first 200 characters at DEBUG level, plus `http.request.body.bytes`.

---

### 3.6 Special cases

**Namespace declarations.** JAXB resolves namespaces transparently. The default namespace is `http://efti.eu/v1/consignment/identifier`; any `xsi:*` attributes are ignored on unmarshal. The raw XML string (with all namespace declarations) is stored as-is in `consignments.xml`.

**`dropXmlHeader()`.** Strip `<?xml?>` declaration before JAXB parsing and before DB storage. Reference impl: `if (startsWith("<?xml")) substringAfter("\n") else this`. Inputs without a header pass through unchanged; only the **first** line is stripped if multiple `<?xml` lines somehow appear.

**`dropXmlRoot()`.** Strip outer root element, returning inner content. Reference impl: `substringAfter(">").substringBeforeLast("<")`. Two callers: re-wrapping consignment XML under `<ed:consignment>` for AS4 responses; extracting inner `<consignment>` when the platform wraps submission under `<identifiers datasetId="…">`. Naïve string manipulation — assumes single-root, no leading whitespace before the root element.

**CDATA sections.** Not expected in eFTI identifier XML. If present, JAXB parses content as plain text and the raw CDATA is preserved in `consignments.xml`.

**Large payloads.** ≤ 1 MB: in-memory JAXB DOM (default for identifier XML). 1–10 MB: still JAXB, monitor heap. > 10 MB: reject with 400 / 413 via HTTP server config. Dataset XML stored on platforms is **never parsed by the Gate** — only the metadata identifier XML is parsed.

**Concurrency.** `JAXBContext` is thread-safe and created once per class via `companion object: JaxbParseable<T>()`. Each `parse()` / `render()` call constructs a new `Unmarshaller` / `Marshaller`; these are not shared across threads.

---

## 4. Helper functions

The Gate-side helpers live in the `edelivery` module (`gate/src/efti/edelivery/`). The names below are the spec-level contract; full Kotlin source belongs to the implementation, not this document.

| Function | Source | Purpose | Where called |
|---|---|---|---|
| `String.dropXmlHeader()` | `edelivery` module, `String` extension | Remove `<?xml?>` declaration before parsing / storage. | `EftiService.saveIdentifiers()`; AS4 response wrapping. |
| `String.dropXmlRoot()` | `edelivery` module, `String` extension | Strip outer root element so inner XML can be re-embedded. | `EftiService.handleSaveIdentifiersRequest()`, `handleIdentifierQuery()`. |
| `JaxbParseable<T>` | `edelivery` module, abstract companion base | Holds a single `JAXBContext` per class; provides `parse(xml)` and `render(o)` (`JAXB_FRAGMENT=true` → output without XML declaration). | `ConsignmentXml`, `IdentifiersQuery`, `UILQuery`, `GateIdentifiersResponse`, `FollowUpRequest`. |
| `ActualOccurrenceDateTime.instant` | `edelivery` module | Parse `formatId`-tagged datetimes per §2.5 into `Instant` (UTC). | `EftiParser.parseIdentifiers()`. |

---

## 5. Validation rules

### 5.1 XML / route-level

| Rule | When | Error code (`efti.error.code`) | HTTP |
|---|---|---|---|
| Well-formed XML | Before JAXB parse | `INVALID_XML` | 400 |
| Root element namespace matches expected | JAXB type binding | `INVALID_XML` | 400 |
| `datasetId` path param is valid UUID v4 | Route binding | `INVALID_DATASET_ID` | 400 |

The Gate does **not** validate XML against the full XSD schema. JAXB maps known fields and ignores unknown elements.

### 5.2 Business

| Rule | Where applied | Error | HTTP |
|---|---|---|---|
| mTLS cert subject DN + serial resolves to >1 active `platforms` row (config error) | `PlatformAuthChecker.resolvePlatform()` | `FORBIDDEN_MULTI_PLATFORM` | 403 |
| mTLS cert subject DN + serial resolves to 0 active `platforms` rows | `PlatformAuthChecker.resolvePlatform()` | `FORBIDDEN_NO_PLATFORM` | 403 |
| Authority requested subsets ⊆ `users.subsets` | `AuthorityRoutes.getDataset()` | `FORBIDDEN_SUBSET` | 403 |
| Follow-up `gateId` equals this gate's `gateId` | `EftiService.handlePostFollowUpRequest()` | `FOLLOW_UP_GATE_MISMATCH` | 400 |
| Target gate is ONLINE | `EftiService.checkGateAvailable()` | `GATEWAY_UNAVAILABLE` | 502 |

### 5.3 Database constraints

The append-only schema uses synthetic `row_id UUID` primary keys; the previous "primary key" columns (`consignments.dataset_id`, `identifiers.id`) are now **non-unique logical identifiers**. There is no PRIMARY KEY violation on `dataset_id` and no FOREIGN KEY between operational tables — referential integrity is enforced at the application layer (see [`db/README.md`](db/README.md) "Foreign keys"). Re-uploads against an existing `dataset_id` succeed as new rows; the latest row by `created_at` is the current state.

| Constraint | Table | Column | Error |
|---|---|---|---|
| PRIMARY KEY (synthetic) | every operational table | `row_id` | n/a — auto-generated UUID, cannot collide |
| NOT NULL | `consignments` | `platform_id`, `gate_id`, `xml` | `DATABASE_ERROR` (500) |
| CHECK / domain enums | every operational table | various | `BAD_REQUEST_GENERAL` (400) at app boundary; `DATABASE_ERROR` (500) if it slips through |

---

## 6. Performance requirements

- Identifier XML parse + DB write (< 50 KB): ≤ 50 ms; in-memory JAXB DOM.
- Identifier XML parse + DB write (50 KB – 1 MB): ≤ 200 ms; JAXB DOM, monitor GC.
- Identifier XML > 1 MB: reject (400 / 413).
- G2G identifier query response build: ≤ 20 ms (string concatenation; no marshalling).
- Dataset passthrough (no parse): platform latency + ≤ 10 ms gate overhead; HTTP body streamed.
- `JAXBContext` initialisation: < 500 ms one-off at startup, via companion-object singleton.
- Memory: JAXB DOM uses ~2–3× XML size in heap (50 KB XML → ~150 KB heap per request); size the connection pool and coroutine dispatcher accordingly.

---

## 7. Security

**XXE prevention.** Disable external entities on the SAXParserFactory used by JAXB:
`disallow-doctype-decl=true`, `external-general-entities=false`, `external-parameter-entities=false`, `setXIncludeAware(false)`, `setExpandEntityReferences(false)`. Modern Jakarta JAXB defaults to safe; verify and configure explicitly for production.

**XML bomb (Billion Laughs).** Enforce: max body size 10 MB (HTTP server); max XML nesting depth 20; max XML parsing time 5 s.

**SQL injection.** All extracted XML values are inserted via parameterised statements (`klite-jdbc` / `consignmentRepository.save(consignment)`). Never string-concatenate user input into SQL.

**Input size limits.** Platform identifier XML: 10 MB. eDelivery AS4 message body: 10 MB. Platform dataset response: unlimited (streamed, not parsed).

---

## Appendix A — Complete eFTI identifier XML examples

### A.1 Minimal valid (one vehicle)

```xml
<consignment xmlns="http://efti.eu/v1/consignment/identifier">
  <mainCarriageTransportMovement>
    <modeCode>3</modeCode>
    <usedTransportMeans>
      <id schemeAgencyId="6">123ABC</id>
      <registrationCountry><code>EE</code></registrationCountry>
    </usedTransportMeans>
  </mainCarriageTransportMovement>
</consignment>
```

### A.2 Full example (vehicle + equipment + dangerous goods + delivery)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<consignment xmlns="http://efti.eu/v1/consignment/identifier">
  <mainCarriageTransportMovement>
    <dangerousGoodsIndicator>true</dangerousGoodsIndicator>
    <modeCode>3</modeCode>
    <usedTransportMeans>
      <id schemeAgencyId="6">789DEF</id>
      <registrationCountry><code>DE</code></registrationCountry>
    </usedTransportMeans>
  </mainCarriageTransportMovement>
  <usedTransportEquipment>
    <id schemeAgencyId="6">MSCU1234567</id>
    <registrationCountry><code>DE</code></registrationCountry>
    <sequenceNumber>1</sequenceNumber>
    <carriedTransportEquipment>
      <id schemeAgencyId="6">TRLU9876543</id>
      <sequenceNumber>1</sequenceNumber>
    </carriedTransportEquipment>
  </usedTransportEquipment>
  <deliveryEvent>
    <actualOccurrenceDateTime formatId="205">202604231015+0200</actualOccurrenceDateTime>
  </deliveryEvent>
</consignment>
```

### A.3 Multi-leg transport (two transport movements)

```xml
<consignment xmlns="http://efti.eu/v1/consignment/identifier">
  <mainCarriageTransportMovement>
    <dangerousGoodsIndicator>false</dangerousGoodsIndicator>
    <modeCode>1</modeCode>
    <usedTransportMeans>
      <id schemeAgencyId="6">VESSEL-IMO-1234567</id>
      <registrationCountry><code>FI</code></registrationCountry>
    </usedTransportMeans>
  </mainCarriageTransportMovement>
  <mainCarriageTransportMovement>
    <dangerousGoodsIndicator>false</dangerousGoodsIndicator>
    <modeCode>3</modeCode>
    <usedTransportMeans>
      <id schemeAgencyId="6">456XYZ</id>
      <registrationCountry><code>EE</code></registrationCountry>
    </usedTransportMeans>
  </mainCarriageTransportMovement>
</consignment>
```

---

## Appendix B — XPath quick reference

| Data element | XPath | Stored in | Example |
|---|---|---|---|
| Mode code | `//mainCarriageTransportMovement[1]/modeCode` | `consignments.mode` | `"road"` (mapped from XML `"3"`) |
| Dangerous goods | `//mainCarriageTransportMovement[1]/dangerousGoodsIndicator` | `consignments.dangerous_goods` | `true` |
| Delivery datetime | `//deliveryEvent/actualOccurrenceDateTime` | `consignments.delivered_at` | `"2026-04-23T07:15:00Z"` |
| Vehicle plate (means) | `//mainCarriageTransportMovement/usedTransportMeans/id` | `identifiers.identifier_value` (with `identifier_type='means'`) | `"123ABC"` |
| Vehicle country | `//mainCarriageTransportMovement/usedTransportMeans/registrationCountry/code` | `identifiers.country_code` | `"EE"` |
| Container ID | `//usedTransportEquipment/id` | `identifiers.identifier_value` (with `identifier_type='equipment'`) | `"MSCU1234567"` |
| Nested equipment | `//usedTransportEquipment/carriedTransportEquipment/id` | `identifiers.identifier_value` (with `identifier_type='carried'`) | `"TRLU9876543"` |

> `identifiers.id` is a UUID v4 primary key generated by `uuid_generate_v4()`; it is *never* the identifier-value column. See §2.2 for the schema note.
