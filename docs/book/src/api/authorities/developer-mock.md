# Developer mock

A public sandbox that imitates the X-Road provider API with canned answers. Integrators use it to
build and test their client before they have X-Road access, a Security Server or an authority
registration. It is a separate Ruuter project (`ruuter-xroad-mock`) with no database, no core and
no link to the gate, so it cannot leak consignment data.

Paths: `/developer/**` · on ruuter-xroad-mock (G1 :27088, G2 :28088; both serve the same
files) · public URL `https://eftidev.kemitaws.ee/developer/` · source `DSL/Ruuter-xroad-mock/developer/`

| Mock route | Real route | Source |
|---|---|---|
| `GET /developer/health/ready` | `GET /xroad/health/ready` | `GET/health/ready.yml` (public) |
| `POST /developer/v1/echo` | `POST /xroad/provide/v1/echo` | `POST/v1/echo.yml` |
| `GET /developer/v1/subsets` | `GET /xroad/provide/v1/subsets` | `GET/v1/subsets.yml` |
| `POST /developer/v1/search` | `POST /xroad/provide/v1/search` | `POST/v1/search.yml` |
| `POST /developer/v1/transport-means` | `POST /xroad/provide/v1/transport-means` | `POST/v1/transport-means.yml` |
| `POST /developer/v1/dataset` | `POST /xroad/provide/v1/dataset` | `POST/v1/dataset.yml` |
| `POST /developer/v1/follow-up` | `POST /xroad/provide/v1/follow-up` | `POST/v1/follow-up.yml` |

There is no `openapi` route (`GET /developer/v1/openapi` is `404 {"error":"Not Found"}`).

## Headers and the guard

Same headers as the real API; here you set them yourself. `DSL/Ruuter-xroad-mock/developer/.guard.yml`
checks the shape like the real guard but does not look at a registry:

| `X-Road-Client` `memberCode` (part 3) | Result |
|---|---|
| `00000000` | `403 FORBIDDEN` ("… Mock: member code 00000000 is reserved for this response.") |
| `70000000` | resolves, but with `subsets: []`: `dataset` and `transport-means` answer `403 FORBIDDEN_SUBSET` |
| any other well-formed value | resolves to `auth-mock` with `subsets: ["EU01","EU02","EU03","EU05"]`; `registryCode` = your member code |

Missing or malformed `X-Road-Client` → `401 UNAUTHORIZED`; missing `X-Road-Id` →
`400 MISSING_REQUIRED_HEADER`; non-UUID `X-Road-Id` → `400 INVALID_REQUEST_ID` with detail
"X-Road-Id must be a UUID (8-4-4-4-12)." Guard errors are sent as `application/json` (the real
guard uses `application/problem+json`). Nothing is audited.

## Canned data

| Item | Value |
|---|---|
| Authority | `id: auth-mock`, `name: Mock Competent Authority`, `status: ACTIVE` |
| Known identifiers | `MOCK-PLATE-1` and its container `CONT-MOCK-01` → 1 consignment (`…0001`); `MOCK-PLATE-3`, trailer `MOCK-TRAILER-3`, container `MOCK-CONTAINER-3` → 3 consignments (`…0011`, `…0012`, `…0013`); anything else → nothing |
| Registration country | `EE` for all; any other `countryCode` → nothing |
| Gate | the canned answers name gate `EU-EE` (the public mock's own gate), whichever local gate serves them |
| Datasets | `EU-EE/mock/550e8400-e29b-41d4-a716-446655440002` (every FTI010 field, 36 KB of XML), `…0011`–`…0013` (the MOCK-PLATE-3 shipments); every other UIL gets the small `MOCK-PLATE-1` fixture |

The dataset UUIDs are `550e8400-e29b-41d4-a716-4466554400NN`. `docs/developer/x_road_developer_mock.md`
(Estonian) describes the content of each fixture and where each form field sits in the response.

## How it differs from the real API

| | Real gate | Mock |
|---|---|---|
| Who you are | registry lookup by `registryCode` | any member code is `auth-mock` |
| Data | the gate's register, peer gates, the platform | four canned rows, five canned datasets |
| `search` miss | fan-out, `x-search-id`, poll collects peer rows | `[]` + `x-poll-more: true` + `x-search-id`; a poll answers `[]`, `x-poll-more: false` |
| `search` poll | needs `searchId`, owner check | `{"poll": true}` is enough |
| `transport-means` `allgates` | fan-out and poll | `501 NOT_IMPLEMENTED` |
| `transport-means` poll body | handled | treated as a lookup without identifier → `400` |
| `transport-means` validation | type checks on `identifier`, `countryCode` | only empty / missing `identifier` and unknown `scope` |
| `dataset` subset check | every requested subset must be permitted | only member code `70000000` is denied; `EU09` passes |
| `dataset` content | filtered by the platform | the same XML whatever the subsets |
| `dataset`, `follow-up` with a repeated `X-Road-Id` | `409 DUPLICATE_REQUEST_ID` | accepted |
| `follow-up` | validates, forwards, `201` + `uil` | always `200` `{uil, status: "DELIVERED", message}`, even for `{}` |
| `502` paths | yes | none |
| equipment arrays without values | `null` locally | `[]` |

## Examples

All examples run against G1's mock on `127.0.0.1:27088`. The same requests work on
`https://eftidev.kemitaws.ee/developer/…` (not run from here).

### health

```bash
curl -i http://127.0.0.1:27088/developer/health/ready
```

```http
HTTP/1.1 200 OK
content-type: application/json

"OK"
```

### echo

```bash
curl -i -X POST http://127.0.0.1:27088/developer/v1/echo \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000097/test' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"hello":"world"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "authority": {
    "id": "auth-mock",
    "name": "Mock Competent Authority",
    "registryCode": "70000097",
    "status": "ACTIVE",
    "subsets": ["EU01", "EU02", "EU03", "EU05"]
  },
  "echo": {"hello": "world"},
  "xRoadClient": "DEV/GOV/70000097/test",
  "xRoadId": "9f547372-0200-4638-87f9-580f25d1b1c7",
  "xRoadUserId": null
}
```

With `X-Road-Client: DEV/GOV/00000000/x`:

```http
HTTP/1.1 403 Forbidden
content-type: application/json

{
  "code": "FORBIDDEN",
  "detail": "The X-Road client does not resolve to an active competent authority. (Mock: member code 00000000 is reserved for this response.)",
  "status": 403,
  "title": "Forbidden",
  "type": "https://api.efti.ee/errors/forbidden"
}
```

### subsets

```bash
curl -s http://127.0.0.1:27088/developer/v1/subsets \
  -H 'X-Road-Client: DEV/GOV/70000097/test' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```json
{"authorityId": "auth-mock", "registryCode": "70000097", "subsets": ["EU01", "EU02", "EU03", "EU05"]}
```

With member code `70000000`: `{"authorityId": "auth-mock", "registryCode": "70000000", "subsets": []}`.

### transport-means

```bash
curl -i -X POST http://127.0.0.1:27088/developer/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000097/test' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"MOCK-PLATE-1","countryCode":"EE","scope":"local"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "consignments": [
    {
      "acceptanceCountry": null,
      "acceptanceDate": null,
      "carriedEquipmentCategories": [],
      "carriedEquipmentIds": [],
      "createdAt": "2026-04-23T10:15:00Z",
      "dangerousGoods": null,
      "deliveryCountry": null,
      "deliveryDate": null,
      "loadingCountry": "EE",
      "loadingDate": "2026-04-23T06:00:00Z",
      "mainTransportId": "MOCK-PLATE-1",
      "mainTransportType": "1522",
      "status": "ACTIVE",
      "transportMode": "3",
      "transportRegCountry": "EE",
      "uil": {
        "datasetId": "550e8400-e29b-41d4-a716-446655440001",
        "gateId": "EU-EE",
        "platformId": "mock"
      },
      "unloadingCountry": "FI",
      "unloadingDate": "2026-04-25T14:00:00Z",
      "usedEquipmentCategories": ["CN"],
      "usedEquipmentCountries": ["EE"],
      "usedEquipmentIds": ["CONT-MOCK-01"]
    }
  ],
  "countryCode": "EE",
  "found": 1,
  "identifier": "MOCK-PLATE-1",
  "scope": "local"
}
```

Other answers from the same route:

| Body | Answer |
|---|---|
| `{"identifier":"MOCK-TRAILER-3"}` | `200`, `found: 3` (`…0011`, `…0012`, `…0013`) |
| `{"identifier":"MOCK-PLATE-1","scope":"existence"}` | `200`, `{"countryCode": "", "identifier": "MOCK-PLATE-1", "registered": true, "scope": "existence"}` |
| `{"identifier":"TST-001"}` | `200`, `found: 0` — the mock does not know the gate's data |
| `{"identifier":"MOCK-PLATE-1","scope":"allgates"}` | `501`, `code: NOT_IMPLEMENTED`, detail "scope 'allgates' is part of the published contract but not yet implemented. Use scope 'local'." |
| `{"identifier":"MOCK-PLATE-1","scope":"x"}` | `400 BAD_REQUEST_GENERAL`, detail "scope, if given, must be one of: existence, local, allgates." |
| `{}` or `{"poll":true,"searchId":"…"}` | `400 BAD_REQUEST_GENERAL`, "identifier is required and must be a non-empty string." |
| any, member code `70000000` | `403 FORBIDDEN_SUBSET`, `authorityId: auth-mock`, `deniedSubsets: ["EU02"]`, `permittedSubsets: []` |

### search

```bash
curl -i -X POST http://127.0.0.1:27088/developer/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000097/test' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"mainTransportId":{"operator":"EQ","id":"MOCK-PLATE-3"}}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

[
  {
    "acceptanceCountry": null,
    "acceptanceDate": null,
    "carriedEquipmentCategories": ["CN"],
    "carriedEquipmentIds": ["MOCK-CONTAINER-3"],
    "createdAt": "2026-09-14T05:00:00Z",
    "dangerousGoods": null,
    …
    "mainTransportId": "MOCK-PLATE-3",
    …
    "uil": {
      "datasetId": "550e8400-e29b-41d4-a716-446655440011",
      "gateId": "EU-EE",
      "platformId": "mock"
    },
    …
    "usedEquipmentIds": ["MOCK-TRAILER-3"]
  },
  …two more rows, …0012 and …0013…
]
```

The mock filters its four rows with the same criteria rules as `get_consignments.sql`
([Search criteria](../../data/search-criteria.md)). `{}` returns all four.
`{"usedEquipmentId":{"operator":"EQ","id":"CONT-MOCK-01"}}` at G2's mock
(`127.0.0.1:28088`) returns `…0001`.

A miss:

```bash
curl -i -X POST http://127.0.0.1:27088/developer/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000097/test' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"mainTransportId":{"operator":"EQ","id":"TST-001"}}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-search-id: bf73680c-438a-45f6-a5cf-87239424c93e
x-poll-more: true

[]
```

The poll `{"poll":true}` answers `200`, `x-poll-more: false`, `[]`.

### dataset

```bash
curl -i -X POST http://127.0.0.1:27088/developer/v1/dataset \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000097/test' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"EU-EE","platformId":"mock","datasetId":"550e8400-e29b-41d4-a716-446655440001"},"subsets":["EU01","EU05"]}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "consignment": {
    "grossWeightMeasure": "15000.00",
    "grossWeightMeasure@unitCode": "KGM",
    "mainCarriageLogisticsTransportMovement": {
      "modeCode": "3",
      "usedLogisticsTransportMeans": {
        "Id": "MOCK-PLATE-1",
        "Id@schemeAgencyId": "5",
        "registrationTradeCountry": {"Id": "EE"}
      }
    },
    "packageQuantity": "120"
  },
  "subsets": ["EU01", "EU05"],
  "uil": {
    "datasetId": "550e8400-e29b-41d4-a716-446655440001",
    "gateId": "EU-EE",
    "platformId": "mock"
  },
  "xml": "<SpecifiedSupplyChainConsignment\n    xmlns:rsm=\"urn:eu:move:eFTI:data:…"
}
```

This is the same fixture the gate's `mock` platform serves, so the real gate's local
[`dataset`](./dataset.md) returns the same `consignment`.

| Body / client | Answer |
|---|---|
| UIL `…0002`, subsets `["EU01","EU02","EU03","EU05"]` | `200`; `xml` 36 560 characters, `consignment` with 49 top-level keys |
| `"subsets":["EU09"]` | `200` — the mock does not compare subsets |
| no `subsets` | `400 MISSING_SUBSET` |
| member code `70000000` | `403 FORBIDDEN_SUBSET`, `deniedSubsets` = the requested list, `permittedSubsets: []` |

### follow-up

```bash
curl -i -X POST http://127.0.0.1:27088/developer/v1/follow-up \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000097/test' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"EU-EE","platformId":"mock","datasetId":"550e8400-e29b-41d4-a716-446655440001"},"message":"Please confirm the gross weight.","referenceIds":["REF-1"]}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "message": "Please confirm the gross weight.",
  "status": "DELIVERED",
  "uil": {
    "datasetId": "550e8400-e29b-41d4-a716-446655440001",
    "gateId": "EU-EE",
    "platformId": "mock"
  }
}
```

`{}` answers `200 {"message": "", "status": "DELIVERED", "uil": null}`. Sending the same
`X-Road-Id` twice gives `200` both times.

## Notes

- `docs/developer/x_road_developer_mock.md` says the mock and the gate are "1:1" in contract.
  They differ in the points in the table above, most visibly in `follow-up` (`200` vs `201`, no
  validation) and in the `dataset` subset check.
- The same document says a `transport-means` poll (`{poll: true}`) answers `501`. The mock answers
  `400` "identifier is required …", because it has no poll branch; only `scope: allgates` gives
  `501`.
- `scope` errors use a different detail text than the real route.
- A Postman collection for the mock is in `docs/developer/eFTI-developer-mock.postman_collection.json`
  (`baseUrl` variable, default `https://eftidev.kemitaws.ee`). HTTP tests:
  `tests/http/developer-mock.http`.
