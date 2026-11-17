# search

Finds consignments by identifier criteria (plate, mode, countries, dates, equipment, …). The gate
searches its own register first. A local hit answers at once. On a local miss the gate asks every
other gate (FTI019 over AS4) in the background and the caller collects their answer with a poll.
The answer is identifier-level metadata and a `uil` per consignment, never dataset content, so
there is no subset check.

`POST /xroad/provide/v1/search` · on ruuter-xroad (G1 :27087, G2 :28087) · X-Road guard ·
source `DSL/Ruuter-xroad/xroad/POST/provide/v1/search.yml`, forwarding to
`DSL/Ruuter/efti/POST/api/v1/authority/search.yml`

The X-Road clients below are development fixtures and public in the repo.

## Request

| Header | Required | Value |
|---|---|---|
| `X-Road-Client` | yes | the caller, see [the guard](./index.md#the-guard) |
| `X-Road-Id` | yes | a new UUID per call. On a first call it becomes the `searchId`. |
| `Content-Type` | yes | `application/json` |

The body is one of two forms.

**Search** — the criteria object itself, no wrapper. Each key is a field with `operator` and one
value key; dates take an array. The full list of fields, value keys and operators is in
[Search criteria](../../data/search-criteria.md).

| Body | Required | Type | Meaning |
|---|---|---|---|
| `<field>` | no | object | e.g. `"mainTransportId": {"operator": "EQ", "id": "TST-001"}` |
| `<date field>` | no | array of 1–2 objects | e.g. `"loadingDate": [{"operator": "GE", "date": "2026-11-01T00:00:00Z"}]` |

All fields are ANDed. `{}` matches everything. Unknown fields are ignored. The route does not
validate criteria; a malformed criterion usually matches nothing (see
[Pitfalls](../../data/search-criteria.md#pitfalls)).

**Poll** — collects the other gates' answer for an earlier first call.

| Body | Required | Type | Meaning |
|---|---|---|---|
| `poll` | yes | boolean `true` | a JSON boolean; a string `"true"` is rejected by the engine (`400 Field type mismatch`) |
| `searchId` | yes | UUID string | the `x-search-id` header of the first response |

There is no paging. A first call returns at most 20 local rows, newest first.

## Responses

| Status | When | Headers | Body |
|---|---|---|---|
| 200 | local hit | `x-poll-more: false` | array of consignment rows |
| 200 | local miss, fan-out started | `x-poll-more: true`, `x-search-id: <X-Road-Id>` | `[]` |
| 200 | poll, results in | `x-poll-more: false` | the peer gates' rows |
| 200 | poll, results not in after ~30 s | `x-poll-more: true` | `[]` — poll again |
| 200 | poll, unknown / expired / another authority's `searchId` | `x-poll-more: false` | `[]` |
| 400 `BAD_REQUEST_GENERAL` | poll without a UUID `searchId` | | RFC 7807, detail "A poll needs {\"poll\": true, \"searchId\": …}." |
| 400 `BAD_REQUEST_GENERAL` | `incoming.body == null` | | "A JSON request body is required." Not reachable in practice: the engine supplies `{}`. |
| 400 (engine) | invalid JSON; `poll` not a boolean or `searchId` not a string | | `{"error": "…"}` |
| 400 / 401 / 403 | guard | | see [the guard](./index.md#the-guard) |
| 502 `GATEWAY_UNAVAILABLE` | core answered ≥ 400 (e.g. the SQL failed on a bad criterion value) | | `coreStatus`, `coreResponse` |
| 502 `GATEWAY_UNAVAILABLE` | core unreachable | | `transportError`. Not run locally. |
| 502 `GATEWAY_UNAVAILABLE` | xml-mapper could not normalise the rows | | `failedStep: "transport-means-normalize"`, `mapperStatus`. Not run locally. |
| 502 `GATEWAY_UNAVAILABLE` | the owner record could not be read or written | | `failedStep: "search-owner"`, `resqlStatus`. Not run locally. |

### The consignment row

Every row has the same keys, in local and remote answers alike (xml-mapper's
`/api/v1/transport-means/normalize` renders missing values as explicit `null`).

| Field | Type | Local row | Row from a peer gate |
|---|---|---|---|
| `uil` | object `{gateId, platformId, datasetId}` | yes | yes — pass it to [`dataset`](./dataset.md) |
| `mainTransportId` | string | yes | yes |
| `mainTransportType` | string | yes | `null` |
| `transportRegCountry` | country | yes | `null` |
| `transportMode` | mode code | yes | `null` |
| `dangerousGoods` | indicator code | yes | `null` |
| `acceptanceDate`, `deliveryDate`, `loadingDate`, `unloadingDate` | ISO date-time | yes | `null` |
| `acceptanceCountry`, `deliveryCountry`, `loadingCountry`, `unloadingCountry` | country | yes | `null` |
| `usedEquipmentIds`, `usedEquipmentCategories`, `usedEquipmentCountries` | arrays | array or `null` | `[]` |
| `carriedEquipmentIds`, `carriedEquipmentCategories` | arrays | array or `null` | `[]` |
| `status` | `ACTIVE` / `INACTIVE` | yes | `null` |
| `createdAt` | ISO date-time, registration time at this gate | yes | `null` |

The identifier XML blob and the equipment sequence numbers are not returned.

## Examples

### Local hit at G1

`TST-001` is at both gates. G1 answers with its own two rows and does not ask G2.

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"mainTransportId":{"operator":"EQ","id":"TST-001"}}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

[
  {
    "acceptanceCountry": "EE",
    "acceptanceDate": "2026-11-03T00:00:00Z",
    "carriedEquipmentCategories": null,
    "carriedEquipmentIds": null,
    "createdAt": "2026-11-03T12:03:37.807711Z",
    "dangerousGoods": "0",
    "deliveryCountry": "FI",
    "deliveryDate": "2026-11-03T00:00:00Z",
    "loadingCountry": "EE",
    "loadingDate": "2026-11-03T00:00:00Z",
    "mainTransportId": "TST-001",
    "mainTransportType": "31",
    "status": "ACTIVE",
    "transportMode": "3",
    "transportRegCountry": "EE",
    "uil": {
      "datasetId": "11111111-0000-4000-8000-000000000005",
      "gateId": "G1",
      "platformId": "mock"
    },
    "unloadingCountry": "FI",
    "unloadingDate": "2026-11-03T00:00:00Z",
    "usedEquipmentCategories": null,
    "usedEquipmentCountries": null,
    "usedEquipmentIds": null
  },
  {
    "acceptanceCountry": "EE",
    "acceptanceDate": "2026-11-01T00:00:00Z",
    …
    "dangerousGoods": "1",
    "deliveryCountry": "LV",
    …
    "uil": {
      "datasetId": "11111111-0000-4000-8000-000000000001",
      "gateId": "G1",
      "platformId": "mock"
    },
    …
  }
]
```

### Local hit at G2

```bash
curl -i -X POST http://127.0.0.1:28087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"mainTransportId":{"operator":"EQ","id":"TST-001"}}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

[
  {
    "acceptanceCountry": "LV",
    "acceptanceDate": "2026-11-02T00:00:00Z",
    "carriedEquipmentCategories": null,
    "carriedEquipmentIds": null,
    "createdAt": "2026-11-03T12:29:46.978576Z",
    "dangerousGoods": "1",
    "deliveryCountry": "LT",
    "deliveryDate": "2026-11-03T00:00:00Z",
    "loadingCountry": "LV",
    "loadingDate": "2026-11-02T00:00:00Z",
    "mainTransportId": "TST-001",
    "mainTransportType": "31",
    "status": "ACTIVE",
    "transportMode": "3",
    "transportRegCountry": "EE",
    "uil": {
      "datasetId": "22222222-0000-4000-8000-000000000002",
      "gateId": "G2",
      "platformId": "mock"
    },
    "unloadingCountry": "LT",
    "unloadingDate": "2026-11-03T00:00:00Z",
    "usedEquipmentCategories": null,
    "usedEquipmentCountries": null,
    "usedEquipmentIds": null
  }
]
```

### Another criterion: transport mode

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"transportMode":{"operator":"EQ","mode":"4"}}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

[
  {
    "acceptanceCountry": "SE",
    "acceptanceDate": "2026-11-01T00:00:00Z",
    "carriedEquipmentCategories": null,
    "carriedEquipmentIds": null,
    "createdAt": "2026-11-03T12:03:37.807711Z",
    "dangerousGoods": "0",
    "deliveryCountry": "EE",
    "deliveryDate": "2026-11-01T00:00:00Z",
    "loadingCountry": "SE",
    "loadingDate": "2026-11-01T00:00:00Z",
    "mainTransportId": "OH-LKA",
    "mainTransportType": "1100",
    "status": "ACTIVE",
    "transportMode": "4",
    "transportRegCountry": "FI",
    "uil": {
      "datasetId": "11111111-0000-4000-8000-000000000004",
      "gateId": "G1",
      "platformId": "mock"
    },
    "unloadingCountry": "EE",
    "unloadingDate": "2026-11-01T00:00:00Z",
    "usedEquipmentCategories": null,
    "usedEquipmentCountries": null,
    "usedEquipmentIds": null
  }
]
```

More criteria, with their results, are in [Search criteria](../../data/search-criteria.md#examples).

### Empty criteria

`{}` matches every non-deleted consignment and is cut at 20 rows.

```bash
curl -s -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" -d '{}' \
  | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))'
```

```text
20
```

### Cross-gate, G1 asks G2

`G2-AB123` is only at G2. Keep the `X-Road-Id` of the first call; it is the `searchId`.

```bash
SID=$(cat /proc/sys/kernel/random/uuid)
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $SID" \
  -d '{"mainTransportId":{"operator":"EQ","id":"G2-AB123"}}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: true
x-search-id: 58d01af9-be0e-41f5-95d8-64c03cdfbbbc

[]
```

Poll with a new `X-Road-Id`:

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d "{\"poll\":true,\"searchId\":\"$SID\"}"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

[
  {
    "acceptanceCountry": null,
    "acceptanceDate": null,
    "carriedEquipmentCategories": [],
    "carriedEquipmentIds": [],
    "createdAt": null,
    "dangerousGoods": null,
    "deliveryCountry": null,
    "deliveryDate": null,
    "loadingCountry": null,
    "loadingDate": null,
    "mainTransportId": "G2-AB123",
    "mainTransportType": null,
    "status": null,
    "transportMode": null,
    "transportRegCountry": null,
    "uil": {
      "datasetId": "22222222-0000-4000-8000-000000000001",
      "gateId": "G2",
      "platformId": "mock"
    },
    "unloadingCountry": null,
    "unloadingDate": null,
    "usedEquipmentCategories": [],
    "usedEquipmentCountries": [],
    "usedEquipmentIds": []
  }
]
```

The poll answered in 0.14 s. A second poll with the same `searchId` returns the same row.

### Cross-gate, G2 asks G1

`TST-002` is only at G1.

```bash
SID=$(cat /proc/sys/kernel/random/uuid)
curl -i -X POST http://127.0.0.1:28087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $SID" \
  -d '{"mainTransportId":{"operator":"EQ","id":"TST-002"}}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: true
x-search-id: 0136fdd9-3356-4070-af0f-3b9940212de8

[]
```

```bash
curl -i -X POST http://127.0.0.1:28087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d "{\"poll\":true,\"searchId\":\"$SID\"}"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

[
  {
    "acceptanceCountry": null,
    …
    "mainTransportId": "TST-002",
    …
    "uil": {
      "datasetId": "11111111-0000-4000-8000-000000000002",
      "gateId": "G1",
      "platformId": "mock"
    },
    …
    "usedEquipmentIds": []
  }
]
```

### No match anywhere

```bash
SID=$(cat /proc/sys/kernel/random/uuid)
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $SID" \
  -d '{"mainTransportId":{"operator":"EQ","id":"NO-SUCH-ID"}}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: true
x-search-id: 2f1ea780-c955-47d4-a111-0928be75ffc1

[]
```

The poll (same form as above) answers `200`, `x-poll-more: false`, `[]`.

### Poll by another authority

The search above was started by `DEV/GOV/70000099/testSubsystem`. Another registered authority
gets nothing, and cannot tell this from an unknown id:

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"poll":true,"searchId":"58d01af9-be0e-41f5-95d8-64c03cdfbbbc"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

[]
```

A random UUID as `searchId` gives the same answer.

### Poll without a `searchId` → 400

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"poll":true}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json

{
  "code": "BAD_REQUEST_GENERAL",
  "detail": "A poll needs {\"poll\": true, \"searchId\": <the x-search-id of the first response>}.",
  "status": 400,
  "title": "Bad Request",
  "type": "https://api.efti.ee/errors/bad-request"
}
```

`"searchId":"abc"` gives the same `400`.

### A criterion value the database rejects → 502

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/search \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"usedEquipmentSeq":{"operator":"EQ","sequence":"x"}}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json

{
  "code": "GATEWAY_UNAVAILABLE",
  "coreResponse": {
    "code": "GATEWAY_UNAVAILABLE",
    "detail": "Searching this gate's consignments failed in the data layer.",
    "status": 502,
    "title": "Bad Gateway",
    "type": "https://api.efti.ee/errors/bad-gateway"
  },
  "coreStatus": 502,
  "detail": "The gate's consignment search failed. The gate's own status and response are carried in coreStatus and coreResponse.",
  "status": 502,
  "title": "Bad Gateway",
  "type": "https://api.efti.ee/errors/bad-gateway"
}
```

A date given as an object (`"date": {"formatId": "204", "value": "20300101000000"}`) gives the
same `502`.

## Notes

- A local hit stops the search. If both gates hold matches, the caller sees only the asked
  gate's rows. To see another gate's rows, ask that gate, or use criteria that do not match
  locally.
- On a fan-out, core answers with the local slice; on a miss that is `[]`. The first call never
  waits for a peer gate.
- The `X-Road-Id` of the first call is core's `x-request-id` and the key of the stored search
  (`search_results`). `search` and `transport-means` share that key space: a `transport-means`
  poll with the `searchId` of a `search` (same authority) returned the search's row, wrapped in
  the `transport-means` envelope.
- Date criteria, a wrong value key, `LT`… on text fields and unknown country codes make the
  fan-out send no valid FTI019. The poll then returns `[]` with no error. See
  [Pitfalls](../../data/search-criteria.md#pitfalls).
- Without a `status` criterion the search returns `INACTIVE` consignments as well;
  [`transport-means`](./transport-means.md) returns only `ACTIVE` ones.
- The integration guide lists only `401`, `403`, `502` for this route; the poll `400` exists too.
- Postman: **1. CA -> Gate / Search / search - local hit TST-001**, **… - peer-only plate starts
  fan-out**, **… - poll peer-only plate**, **… - by transport mode (road)**, **… - poll without
  searchId -> 400**. `peer_only_plate` is `G2-AB123` in the G1 environment, `TST-002` in G2's.
- Use case: [An authority searches for consignments](../../use-cases/authority-searches.md).
