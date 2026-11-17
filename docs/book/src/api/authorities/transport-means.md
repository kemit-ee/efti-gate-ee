# transport-means

One identifier in, everything this gate knows about it at identifier level out. The identifier
can be a road plate, a vessel IMO number, an aircraft registration, or a transport-equipment
(container, trailer) id; the gate matches it against the main transport means, the used
equipment and the carried equipment. No dataset content is returned; pass a returned `uil` to
[`dataset`](./dataset.md) for that. The answer is EU02-class data (means of transport), so the
authority needs `EU02`.

`POST /xroad/provide/v1/transport-means` · on ruuter-xroad (G1 :27087, G2 :28087) · X-Road
guard + EU02 · source `DSL/Ruuter-xroad/xroad/POST/provide/v1/transport-means.yml`

The X-Road clients below are development fixtures and public in the repo.
`DEV/GOV/70000093/ants` holds `EU01` and `EU02`; `DEV/GOV/70000099/testSubsystem` holds only `EU01`.

## Request

| Header | Required | Value |
|---|---|---|
| `X-Road-Client` | yes | an authority with `EU02` |
| `X-Road-Id` | yes | a new UUID per call. With `scope: allgates` it becomes the `searchId`. |
| `Content-Type` | yes | `application/json` |

Lookup body:

| Body field | Required | Type | Meaning / validation |
|---|---|---|---|
| `identifier` | yes | non-empty string | matched exactly, case-sensitive, not trimmed |
| `countryCode` | no | string | filters on the transport means registration country (`transport_reg_country`). Absent or `""` = any country. Meaningful for plates, not for containers. |
| `scope` | no | `existence` \| `local` \| `allgates` | default `local`. Case-sensitive; anything else is `400`. |

Poll body (only after an `allgates` first call that answered `x-poll-more: true`):

| Body field | Required | Type | Meaning |
|---|---|---|---|
| `poll` | yes | JSON boolean `true` | the string `"true"` is not a poll: the request is read as a lookup and fails on the missing `identifier` |
| `searchId` | yes | UUID string | the `x-search-id` header of the first response |
| `identifier`, `countryCode` | no | string | only echoed back in the response; they do not change the result |
| `scope` | no | string | validated like a lookup, but the response always says `allgates` |

### Scopes

| `scope` | Where | Response | Speed |
|---|---|---|---|
| `existence` | this gate only | `{identifier, countryCode, scope, registered}` | one indexed query |
| `local` (default) | this gate only | `{identifier, countryCode, scope, found, consignments}`, at most 50 rows | one query |
| `allgates` | this gate, then all other gates on a local miss | same shape as `local`, plus `x-poll-more` (and `x-search-id` on a fan-out) | asynchronous; poll |

`existence` and `local` return only `ACTIVE` consignments, and only if the latest version of the
consignment still carries the identifier. `allgates` asks core's authority search with
`transportMeansOrEquipmentId EQ <identifier>`, `status EQ ACTIVE` and, if given,
`transportRegCountry EQ <countryCode>` ([Search criteria](../../data/search-criteria.md)). Its
local slice is limited to 20 rows.

The `consignments` rows have the same fields as the [`search`](./search.md#the-consignment-row)
rows. Rows from another gate carry only `uil` and `mainTransportId`.

## Responses

| Status | When | Body |
|---|---|---|
| 200 | `existence` | `registered: true/false` |
| 200 | `local`, or `allgates` with a local hit | `found` = `consignments.length`; `found: 0` for an unknown identifier (not 404). `allgates` adds `x-poll-more: false`. |
| 200 | `allgates`, local miss | `found: 0`, `consignments: []`, `x-poll-more: true`, `x-search-id` |
| 200 | poll | the other gates' rows, `scope: "allgates"`, `x-poll-more` |
| 200 | poll with an unknown, expired or another authority's `searchId` | `found: 0`, `x-poll-more: false` |
| 400 `BAD_REQUEST_GENERAL` | `identifier` missing, empty, a number, boolean or object | "identifier is required and must be a non-empty string." |
| 400 `BAD_REQUEST_GENERAL` | `scope` not one of the three | "scope must be one of 'existence', 'local' or 'allgates'. Omit it for 'local'." |
| 400 `BAD_REQUEST_GENERAL` | poll without a UUID `searchId` | "A poll needs {\"poll\": true, \"searchId\": …}." |
| 400 (engine) | `countryCode` or `searchId` not a string | `{"error":"Field type mismatch in body: countryCode expected string, got number"}` |
| 400 / 401 / 403 | guard | see [the guard](./index.md#the-guard) |
| 403 `FORBIDDEN_SUBSET` | the authority's `subsets` has no `EU02` (also on a poll) | `authorityId`, `deniedSubsets: ["EU02"]`, `permittedSubsets` |
| 403 `FORBIDDEN` | the authority row has no usable `subsets` array | "The subset permission of the X-Road client could not be established." Not reachable with the registry fixtures. |
| 502 `GATEWAY_UNAVAILABLE` | `existence` query failed | `failedStep: "check_transport_means_registered"`, `resqlStatus` |
| 502 `GATEWAY_UNAVAILABLE` | `local` query failed (also: `identifier` is a non-empty array) | `failedStep: "get_consignments_by_transport_means"`, `resqlStatus` |
| 502 `GATEWAY_UNAVAILABLE` | `allgates`: core answered ≥ 400 / unreachable | `failedStep: "authority-search"`, `coreStatus` + `coreResponse` / `transportError`. Not run locally. |
| 502 `GATEWAY_UNAVAILABLE` | `allgates`: normalising failed | `failedStep: "transport-means-normalize"`, `mapperStatus`. Not run locally. |
| 502 `GATEWAY_UNAVAILABLE` | `allgates`: owner record not readable/writable | `failedStep: "search-owner"`, `resqlStatus`. Not run locally. |

## Examples

### existence: known plate

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"TST-001","scope":"existence"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "countryCode": "",
  "identifier": "TST-001",
  "registered": true,
  "scope": "existence"
}
```

`{"identifier":"NO-SUCH-ID","scope":"existence"}` answers `"registered": false`. So does
`tst-001`: the match is case-sensitive.

### existence: country filter

`OH-LKA` has registration country `FI`.

```bash
curl -s -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"OH-LKA","countryCode":"FI","scope":"existence"}'
```

```json
{"countryCode": "FI", "identifier": "OH-LKA", "registered": true, "scope": "existence"}
```

With `"countryCode":"EE"`:

```json
{"countryCode": "EE", "identifier": "OH-LKA", "registered": false, "scope": "existence"}
```

### existence at G2: a carried container

```bash
curl -s -X POST http://127.0.0.1:28087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"CONT-400","scope":"existence"}'
```

```json
{"countryCode": "", "identifier": "CONT-400", "registered": true, "scope": "existence"}
```

### local: a plate on two consignments

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"TST-001"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "consignments": [
    {
      "acceptanceCountry": "EE",
      "acceptanceDate": "2026-11-03T00:00:00Z",
      "carriedEquipmentCategories": null,
      "carriedEquipmentIds": null,
      "createdAt": "2026-11-03T12:03:37.807711Z",
      "dangerousGoods": "0",
      "deliveryCountry": "FI",
      …
      "uil": {
        "datasetId": "11111111-0000-4000-8000-000000000005",
        "gateId": "G1",
        "platformId": "mock"
      },
      …
    },
    {
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
  ],
  "countryCode": "",
  "found": 2,
  "identifier": "TST-001",
  "scope": "local"
}
```

### local: a container on the used equipment

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"CONT-100","scope":"local"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "consignments": [
    {
      "acceptanceCountry": "DE",
      "acceptanceDate": "2026-11-01T00:00:00Z",
      "carriedEquipmentCategories": null,
      "carriedEquipmentIds": null,
      "createdAt": "2026-11-03T12:03:37.807711Z",
      "dangerousGoods": "0",
      "deliveryCountry": "EE",
      "deliveryDate": "2026-11-03T00:00:00Z",
      "loadingCountry": "DE",
      "loadingDate": "2026-11-01T00:00:00Z",
      "mainTransportId": "TST-002",
      "mainTransportType": "31",
      "status": "ACTIVE",
      "transportMode": "3",
      "transportRegCountry": "DE",
      "uil": {
        "datasetId": "11111111-0000-4000-8000-000000000002",
        "gateId": "G1",
        "platformId": "mock"
      },
      "unloadingCountry": "EE",
      "unloadingDate": "2026-11-03T00:00:00Z",
      "usedEquipmentCategories": ["T10"],
      "usedEquipmentCountries": ["DE"],
      "usedEquipmentIds": ["CONT-100"]
    }
  ],
  "countryCode": "",
  "found": 1,
  "identifier": "CONT-100",
  "scope": "local"
}
```

`{"identifier":"CONT-200"}` (carried on the vessel) finds `IMO9321483` (`…0003`) the same way.

### local: unknown identifier

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"NO-SUCH-ID"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "consignments": [],
  "countryCode": "",
  "found": 0,
  "identifier": "NO-SUCH-ID",
  "scope": "local"
}
```

### allgates: local hit, no fan-out

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"TST-001","scope":"allgates"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

{
  "consignments": [ …the same two G1 rows as scope local… ],
  "countryCode": "",
  "found": 2,
  "identifier": "TST-001",
  "scope": "allgates"
}
```

G2's own `TST-001` row is not included: a local hit stops the search.

### allgates: G1 asks G2, then polls

`IMO9876543` is only at G2.

```bash
SID=$(cat /proc/sys/kernel/random/uuid)
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $SID" \
  -d '{"identifier":"IMO9876543","scope":"allgates"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: true
x-search-id: c554826b-e5b0-4393-8734-6148911de6a6

{
  "consignments": [],
  "countryCode": "",
  "found": 0,
  "identifier": "IMO9876543",
  "scope": "allgates"
}
```

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d "{\"poll\":true,\"searchId\":\"$SID\"}"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

{
  "consignments": [
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
      "mainTransportId": "IMO9876543",
      "mainTransportType": null,
      "status": null,
      "transportMode": null,
      "transportRegCountry": null,
      "uil": {
        "datasetId": "22222222-0000-4000-8000-000000000004",
        "gateId": "G2",
        "platformId": "mock"
      },
      "unloadingCountry": null,
      "unloadingDate": null,
      "usedEquipmentCategories": [],
      "usedEquipmentCountries": [],
      "usedEquipmentIds": []
    }
  ],
  "countryCode": "",
  "found": 1,
  "identifier": "",
  "scope": "allgates"
}
```

`identifier` is `""` here: the poll body did not repeat it (see Notes). Repeating it in the poll
body, `{"poll":true,"searchId":"…","identifier":"IMO9876543","scope":"allgates"}`, gives the same
row with `"identifier": "IMO9876543"`.

### allgates: G2 asks G1

```bash
SID=$(cat /proc/sys/kernel/random/uuid)
curl -i -X POST http://127.0.0.1:28087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $SID" \
  -d '{"identifier":"IMO9321483","scope":"allgates"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: true
x-search-id: 5eb87f5f-33fb-4d93-9588-3765e28ebcb0

{
  "consignments": [],
  "countryCode": "",
  "found": 0,
  "identifier": "IMO9321483",
  "scope": "allgates"
}
```

The poll to `http://127.0.0.1:28087/…/transport-means` with `{"poll":true,"searchId":"$SID"}`:

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

{
  "consignments": [
    {
      …
      "mainTransportId": "IMO9321483",
      …
      "uil": {
        "datasetId": "11111111-0000-4000-8000-000000000003",
        "gateId": "G1",
        "platformId": "mock"
      },
      …
    }
  ],
  "countryCode": "",
  "found": 1,
  "identifier": "",
  "scope": "allgates"
}
```

### allgates: a container only matches locally

`CONT-400` is carried equipment at G2. Asked at G1 with `allgates`, the fan-out sends it as
the main transport means id, so G2 does not find it:

```bash
SID=$(cat /proc/sys/kernel/random/uuid)
curl -s -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $SID" -d '{"identifier":"CONT-400","scope":"allgates"}'
# x-poll-more: true, found 0
sleep 1
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d "{\"poll\":true,\"searchId\":\"$SID\"}"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

{
  "consignments": [],
  "countryCode": "",
  "found": 0,
  "identifier": "",
  "scope": "allgates"
}
```

At G2 itself, `{"identifier":"CONT-400","scope":"existence"}` is `registered: true` (above).

### allgates with a country filter

`{"identifier":"G2-AB123","countryCode":"LV","scope":"allgates"}` at G1: first call
`x-poll-more: true`, `"countryCode": "LV"`; the poll returns the G2 row `…0001`. With
`"countryCode":"EE"` the poll returns `found: 0`. The filter is sent to the peer as
`TransportMeansRegistrationCountryParameterScope`.

### Poll by another authority, or without EU02

The search above (`c554826b-…`) belongs to `auth-xroad-tm`. Another authority with EU02:

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000094/x' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"poll":true,"searchId":"c554826b-e5b0-4393-8734-6148911de6a6"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false

{
  "consignments": [],
  "countryCode": "",
  "found": 0,
  "identifier": "",
  "scope": "allgates"
}
```

The same poll with `DEV/GOV/70000099/testSubsystem` (no EU02) answers `403 FORBIDDEN_SUBSET`.

### No EU02 → 403

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"TST-001","scope":"existence"}'
```

```http
HTTP/1.1 403 Forbidden
content-type: application/json

{
  "authorityId": "auth-xroad-test",
  "code": "FORBIDDEN_SUBSET",
  "deniedSubsets": ["EU02"],
  "detail": "A transport-means lookup returns means-of-transport data (EU02), which this authority is not permitted to access.",
  "permittedSubsets": ["EU01"],
  "status": 403,
  "title": "Subset Access Denied",
  "type": "https://api.efti.ee/errors/forbidden-subset"
}
```

An authority with `subsets: []` (`DEV/GOV/70000096/x`) gets the same body with
`"authorityId": "auth-xroad-empty"` and `"permittedSubsets": []`.

### Input errors → 400

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" -d '{}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json

{
  "code": "BAD_REQUEST_GENERAL",
  "detail": "identifier is required and must be a non-empty string.",
  "status": 400,
  "title": "Bad Request",
  "type": "https://api.efti.ee/errors/bad-request"
}
```

`{"identifier":""}` and `{"identifier":123}` give the same answer.

```bash
curl -s -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"TST-001","scope":"everywhere"}'
```

```json
{
  "code": "BAD_REQUEST_GENERAL",
  "detail": "scope must be one of 'existence', 'local' or 'allgates'. Omit it for 'local'.",
  "status": 400,
  "title": "Bad Request",
  "type": "https://api.efti.ee/errors/bad-request"
}
```

`"scope":"Local"` is rejected the same way.

```bash
curl -s -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":"TST-001","countryCode":5}'
```

```json
{"error": "Field type mismatch in body: countryCode expected string, got number"}
```

A poll without `searchId`, `{"poll":true}`, answers the `400` "A poll needs …" shown on
[search](./search.md#poll-without-a-searchid--400).

### An array as identifier → 502

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/transport-means \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"identifier":["TST-001"]}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json

{
  "code": "GATEWAY_UNAVAILABLE",
  "detail": "The transport-means lookup failed in the gate's data layer. This is not an authorisation decision.",
  "failedStep": "get_consignments_by_transport_means",
  "resqlStatus": 400,
  "status": 502,
  "title": "Bad Gateway",
  "type": "https://api.efti.ee/errors/bad-gateway"
}
```

## Notes

- **Known issue: the poll loses the echo fields.** A poll answers `"identifier": ""` and
  `"countryCode": ""` unless the poll body repeats them; the route echoes the poll body, not the
  first call. The `consignments` are right. Listed in
  [Known issues](../../reference/known-issues.md).
- The DSL comment says a non-empty array `identifier` "yields 200 with found: 0". On this stack it
  is a `502` (ReSql rejects the parameter type), as shown above.
- The `normalize_failed` detail says "the drained results are lost - the caller must re-issue the
  original search". The DSL comment above it says the poll read is idempotent, and a repeated poll
  did return the same rows. The detail text is out of date.
- `allgates` shares the stored search with [`search`](./search.md): a `transport-means` poll can
  collect a `search` started by the same authority.
- Equipment ids match on remote gates only as a main transport means id. Remote rows have no
  `createdAt` and no `status`; the remote gate does not apply the `ACTIVE` filter.
- The integration guide says `local` returns up to 50 rows and the `allgates` local slice up to
  20. That matches the SQL (`LIMIT 50` and the ReSql default of 20).
- The guard comment names `vehicle.yml` as the EU02 route; the route is this file.
- Postman: **1. CA -> Gate / Transport means (EU02) / …** (existence, local, allgates with the
  environment's `peer_only_vessel` + poll, no EU02, missing identifier, invalid scope).
- Use case: [An authority checks a transport means](../../use-cases/authority-checks-transport-means.md).
