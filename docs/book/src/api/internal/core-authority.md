# Core authority API

The core side of every authority request. ruuter-xroad checks the X-Road caller and its subsets,
then forwards to these routes on the main Ruuter. They decide local versus remote, call the local
platform or the peer gate over AS4, and map the XML back to JSON.

All routes are on ruuter (G1 :27086, G2 :28086) and need the gate-internal token. They are
identity-blind: they trust that the caller has already authorised the request.

## Common request headers

| Header | Required | Value |
|---|---|---|
| `X-Internal-Service-Token` | yes | `dev-internal-service-token-change-me` (local; public in the repository) |
| `X-Request-Id` | yes in practice | a UUID. Forwarded to xml-mapper and edelivery; it becomes the FTI `RequestedSpecifiedQuery` id, the AS4 conversation key and the search id. edelivery requires a UUID |
| `Content-Type` | with a body | `application/json` |

Without the token every route answers:

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 176

{"code":"UNAUTHORIZED","detail":"This endpoint requires a valid X-Internal-Service-Token.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

## How ruuter-xroad maps onto these routes

| X-Road route | Core call | Notes |
|---|---|---|
| `POST /xroad/provide/v1/search` (first call) | `POST /efti/api/v1/authority/search`, `x-request-id = X-Road-Id`, body as received | result normalised by xml-mapper `/api/v1/transport-means/normalize`; a pending search returns `x-search-id = X-Road-Id` |
| `POST /xroad/provide/v1/search` `{"poll":true,"searchId":…}` | same route, `x-request-id = searchId`, `x-poll: true`, body `{}` | |
| `POST /xroad/provide/v1/transport-means` `scope: allgates` | `authority/search` (first call and poll, as above) | `local` and `existence` go straight to ReSql |
| `POST /xroad/provide/v1/dataset` | `POST /efti/api/v1/authority/dataset`, `x-request-id = X-Road-Id` | subsets checked in ruuter-xroad first; dedup by `check_and_record_request_id` |
| `POST /xroad/provide/v1/follow-up` | `POST /efti/api/v1/authority/follow-up`, `x-request-id = X-Road-Id` | dedup by `check_and_record_request_id` |

See [Competent authorities (X-Road)](../authorities/index.md) for the outer contract.

## POST /efti/api/v1/authority/search

Identifier search, local first. A local match is returned at once with `x-poll-more: false`: this
gate is authoritative for its own consignments. With no local match the route registers the
search, starts a background broadcast to every ONLINE peer gate, and answers `[]` with
`x-poll-more: true`. The caller collects the peers' result with a poll.

`POST /efti/api/v1/authority/search` · on ruuter (G1 :27086) · internal token · source `DSL/Ruuter/efti/POST/api/v1/authority/search.yml`

### Request

| Header | Required | Value |
|---|---|---|
| `X-Internal-Service-Token` | yes | internal token |
| `X-Request-Id` | yes for a broadcast and a poll | UUID. Becomes the `search_results` key |
| `X-Poll` | for a poll | any non-empty value (`true`) |

| Body | Required | Type | Meaning |
|---|---|---|---|
| criteria object | yes | object | `{operator, id}`-style criteria, e.g. `{"mainTransportId":{"operator":"EQ","id":"TST-001"}}`. Passed verbatim to ReSql `get_consignments` (local) and xml-mapper `search/request-to-xml` (FTI019). See [Search criteria](../../data/search-criteria.md) |
| `{}` | for a poll | object | the body of a poll is ignored |

### The first call

1. ReSql `get_consignments` with `gateId = OWN_GATE_ID` and the criteria (at most 20 rows).
2. Rows found → `200`, the rows, `x-poll-more: false`. Done.
3. No rows → xml-mapper builds the FTI019; the registry `gates.json` gives the peers (ONLINE, not
   this gate); `insert_search_pending` writes a `pending` row; a detached task starts.
4. The route answers `200`, `[]`, `x-poll-more: true`.

The detached task sends the FTI019 to every peer through edelivery `POST /api/v1/send/{peer}`
(parallel, 62 s per peer, 90 s overall), keeps the 2xx answers that contain a
`ParameterIDSetCriteria`, maps each FTI021 to JSON (`search/response-to-json`), and writes the
flattened array with `insert_search_complete`. With no peers it logs
`WARN search rid=… registry has no peer gates` and writes a `search.no_peers` audit row.

### The poll

With `X-Request-Id` and `X-Poll`, the route reads `get_search_result` for the id. It retries every
500 ms, up to 60 times (about 30 s):

| Stored state | Answer |
|---|---|
| a `complete` row | `200`, the stored array, `x-poll-more: false` |
| no row (never registered, or purged after 10 min) | `200`, `[]`, `x-poll-more: false` |
| still `pending` after 60 tries | `200`, `[]`, `x-poll-more: true` — poll again |

### Responses

| Status | When | Body | `x-poll-more` |
|---|---|---|---|
| 200 | local hit | local `get_consignments` rows | `false` |
| 200 | no local hit, broadcast started | `[]` | `true` |
| 200 | poll, result complete | peer rows (`ConsignmentRow` from xml-mapper) | `false` |
| 200 | poll, unknown or purged id | `[]` | `false` |
| 200 | poll, still pending after ~30 s | `[]` | `true` |
| 401 | token missing or wrong | problem+json `UNAUTHORIZED` | — |
| 502 | `get_consignments` failed | `GATEWAY_UNAVAILABLE`, "Searching this gate's consignments failed in the data layer." | — |
| 502 | registry not 200 or not an array; `insert_search_pending` failed (e.g. no `X-Request-Id`) | `GATEWAY_UNAVAILABLE`, "Registering the cross-gate broadcast failed in the gate's data layer." | — |
| 502 | poll: `get_search_result` failed | `GATEWAY_UNAVAILABLE`, "Polling the cross-gate search results failed …", plus `resqlStatus` | — |

### Examples

#### Local hit (TST-001 on G1)

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/authority/search \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -H 'Content-Type: application/json' \
  -d '{"mainTransportId":{"operator":"EQ","id":"TST-001"}}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false
x-trace-id: ad2b12e295fc4ceea0349a6393c3c29c
content-length: 1850
[{"acceptanceCountry":"EE","acceptanceDate":"2026-11-03T00:00:00Z","carriedEquipmentCategories":null,"carriedEquipmentIds":null,"carriedEquipmentSeq":null,"createdAt":"2026-11-03T12:03:37.807711Z","dangerousGoods":"0","datasetId":"11111111-0000-4000-8000-000000000005","deliveryCountry":"FI","deliveryDate":"2026-11-03T00:00:00Z","gateId":"G1","loadingCountry":"EE","loadingDate":"2026-11-03T00:00:00Z","mainTransportId":"TST-001","mainTransportType":"31","platformId":"mock","rowId":"50c68f4a-6f59-4089-8375-2aff992bc7b3","status":"ACTIVE","transportMode":"3","transportRegCountry":"EE","unloadingCountry":"FI","unloadingDate":"2026-11-03T00:00:00Z","usedEquipmentCategories":null,"usedEquipmentCountries":null,"usedEquipmentIds":null,"usedEquipmentSeq":null,"xml":"<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>TST-001</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>"},{"acceptanceCountry":"EE","acceptanceDate":"2026-11-01T00:00:00Z",…,"datasetId":"11111111-0000-4000-8000-000000000001",…}]
```

#### Cross-gate: G2-AB123 exists only at G2

First call:

```bash
RID=$(cat /proc/sys/kernel/random/uuid)
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/authority/search \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $RID" -H 'Content-Type: application/json' \
  -d '{"mainTransportId":{"operator":"EQ","id":"G2-AB123"}}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: true
x-trace-id: 65244ed562ea4e2a9dbe23ab1fc27606
content-length: 2

[]
```

Poll with the same `X-Request-Id` (here `1a72acca-31ca-436b-ab4c-73241d456b78`):

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/authority/search \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $RID" -H 'X-Poll: true' \
  -H 'Content-Type: application/json' -d '{}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false
x-trace-id: 7d975eb5a5030af4b1b421251d9f1f37
content-length: 532
[{"carriedEquipmentCategories":[],"carriedEquipmentIds":[],"carriedEquipmentSeq":[],"datasetId":"22222222-0000-4000-8000-000000000001","gateId":"G2","mainTransportId":"G2-AB123","platformId":"mock","usedEquipmentCategories":[],"usedEquipmentCountries":[],"usedEquipmentIds":[],"usedEquipmentSeq":[],"xml":"<ParameterIDSetCriteria xmlns:udt=\"urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34\"><MainCarriageTransportMeansIDParameterScope><ID>G2-AB123</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>"}]
```

The poll took 0.5 s: the broadcast had already finished.

#### No match anywhere

```bash
curl … -H "X-Request-Id: $RID" -d '{"mainTransportId":{"operator":"EQ","id":"NOPE-000"}}'
# 3 s later
curl … -H "X-Request-Id: $RID" -H 'X-Poll: true' -d '{}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: true
content-length: 2

[]
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false
content-length: 2

[]
```

#### Poll for an unknown id

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/authority/search \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" -H 'X-Poll: true' \
  -H 'Content-Type: application/json' -d '{}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false
x-trace-id: 0e11323e7c8e2ceec78176ca8b0da958
content-length: 2

[]
```

#### No local hit and no X-Request-Id

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/authority/search \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H 'Content-Type: application/json' \
  -d '{"mainTransportId":{"operator":"EQ","id":"NOPE-001"}}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
x-trace-id: 818f3e46ca052db02a316ceace3f5bd0
content-length: 194

{"code":"GATEWAY_UNAVAILABLE","detail":"Registering the cross-gate broadcast failed in the gate's data layer.","status":502,"title":"Bad Gateway","type":"https://api.efti.ee/errors/bad-gateway"}
```

#### No body, `null` or `{}` (not a poll)

```bash
curl -s -X POST http://127.0.0.1:27086/efti/api/v1/authority/search \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" -H 'Content-Type: application/json' -d 'null'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-poll-more: false
content-length: 46143

[{"acceptanceCountry":"DE","acceptanceDate":"2026-09-24T00:00:00Z",…},…]
```

The route returned 20 consignments of this gate, unfiltered.

Not run locally: the "still pending after ~30 s" poll (it needs a peer that does not answer) and the
502s caused by ReSql or the registry being down.

## POST /efti/api/v1/authority/dataset

Fetches one dataset by UIL. If `uil.gateId` is this gate, the `api/v1/dataset-local` template asks
the platform. Otherwise xml-mapper builds an FTI009, edelivery sends it to the peer gate and waits
for the FTI010. Both paths end in xml-mapper `dataset/response-to-json`.

`POST /efti/api/v1/authority/dataset` · on ruuter (G1 :27086) · internal token · source `DSL/Ruuter/efti/POST/api/v1/authority/dataset.yml`

### Request

| Body field | Required | Type | Meaning / validation |
|---|---|---|---|
| `uil` | yes | object | `{gateId, platformId, datasetId}`. See [UIL](../../data/uil.md) |
| `subsets` | yes | array | non-empty; every item one of `EU01`…`EU07` |

### Responses

| Status | When | Body |
|---|---|---|
| 200 | dataset found | `{"consignment": {…}, "xml": "<SpecifiedSupplyChainConsignment …>"}` |
| 400 | `uil` or `subsets` missing, empty, not an array, or a code outside `EU01`…`EU07` | `BAD_REQUEST_GENERAL` problem object |
| 401 | token | `UNAUTHORIZED` |
| 404 | local path: the platform call gave no `xml` (unknown platform, registry error, platform error) | the template's body, e.g. `{"error":"Platform lookup failed (data layer unavailable)"}` |
| 4xx/5xx | remote path: edelivery answered non-200 | `{"error":"Remote gate dataset request failed","detail":<edelivery body>}` with edelivery's status |
| 502 | xml-mapper unreachable | `{"error":"XML mapper unavailable"}` |
| 502 | edelivery unreachable (transport status 0) | `{"error":"The remote gate is unreachable","transportError":"<kind>"}` |

### Examples

#### Local (G1)

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/authority/dataset \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 39792bde17ffe12038993bd5c90fecef
content-length: 1021

{"consignment":{"grossWeightMeasure":"15000.00","grossWeightMeasure@unitCode":"KGM","mainCarriageLogisticsTransportMovement":{"modeCode":"3","usedLogisticsTransportMeans":{"Id":"MOCK-PLATE-1","Id@schemeAgencyId":"5","registrationTradeCountry":{"Id":"EE"}}},"packageQuantity":"120"},"xml":"<SpecifiedSupplyChainConsignment\n    xmlns:rsm=\"urn:eu:move:eFTI:data:standard:FTI010GetCmdsResponse:1\"\n    xmlns=\"urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34\"\n    xmlns:udt=\"urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34\">\n  <GrossWeightMeasure unitCode=\"KGM\">15000.00</GrossWeightMeasure>\n  <PackageQuantity>120</PackageQuantity>\n  …</SpecifiedSupplyChainConsignment>\n"}
```

#### Remote (G2, over AS4)

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/authority/dataset \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G2","platformId":"mock","datasetId":"22222222-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 6f3a0a5c364bce536d4fd04ff69c6588
content-length: 844

{"consignment":{"grossWeightMeasure":"15000.00","grossWeightMeasure@unitCode":"KGM","mainCarriageLogisticsTransportMovement":{"modeCode":"3","usedLogisticsTransportMeans":{"Id":"MOCK-PLATE-1","Id@schemeAgencyId":"5","registrationTradeCountry":{"Id":"EE"}}},"packageQuantity":"120"},"xml":"<SpecifiedSupplyChainConsignment xmlns:udt=\"urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34\"><GrossWeightMeasure unitCode=\"KGM\">15000.00</GrossWeightMeasure>\n  <PackageQuantity>120</PackageQuantity>\n  …"}
```

#### Subset not in EU01…EU07, or an empty body

```bash
curl … -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU09"]}'
curl … -d '{}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 236

{"code":"BAD_REQUEST_GENERAL","detail":"The request body must be a JSON object with 'uil' (object) and a non-empty 'subsets' array of EU01..EU07 codes.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

#### Unknown platform on this gate

```bash
curl … -d '{"uil":{"gateId":"G1","platformId":"nope","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
x-trace-id: ca3fcecc5b19e416f017de3f88c502e2
content-length: 59

{"error":"Platform lookup failed (data layer unavailable)"}
```

#### Unknown gate

```bash
curl … -d '{"uil":{"gateId":"EU-XX","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
x-trace-id: 5fb6e2b7869dd9dd7e908a04deec4307
content-length: 189

{"detail":{"detail":"Unknown party: EU-XX","instance":"/api/v1/send/EU-XX","message":"Unknown party: EU-XX","status":400,"title":"Bad Request"},"error":"Remote gate dataset request failed"}
```

Not run locally: the 502 cases (xml-mapper or edelivery down).

## GET /efti/api/v1/authority/dataset/\{gateId\}/\{platformId\}/\{datasetId\}

The same request as the POST form, with the UIL in the path and the subsets in the query.

`GET /efti/api/v1/authority/dataset/{gateId}/{platformId}/{datasetId}?subsets=EU01[,EU02…]` · on ruuter (G1 :27086) · internal token · source `DSL/Ruuter/efti/GET/api/v1/authority/dataset.yml`

### Request

| Path / Query parameter | Required | Type | Meaning |
|---|---|---|---|
| `gateId` | yes | path | gate of the UIL |
| `platformId` | yes | path | platform of the UIL |
| `datasetId` | yes | path, UUID | dataset of the UIL |
| `subsets` | yes | query | comma-separated, or repeated (`?subsets=EU01&subsets=EU03`); each one of `EU01`…`EU07` |

### Responses

As for the POST form, except the validation error:

| Status | When | Body |
|---|---|---|
| 400 | `subsets` missing, empty or with a code outside `EU01`…`EU07` | `BAD_REQUEST_GENERAL`, "The query parameter 'subsets' must list at least one subset, each one of EU01..EU07." |

### Examples

#### Remote, path form

```bash
curl -s -i 'http://127.0.0.1:27086/efti/api/v1/authority/dataset/G2/mock/22222222-0000-4000-8000-000000000001?subsets=EU01' \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 1ea933220c75b730359c69032a11c3c1
content-length: 844

{"consignment":{"grossWeightMeasure":"15000.00",…},"xml":"<SpecifiedSupplyChainConsignment xmlns:udt=\"urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34\">…"}
```

`?subsets=EU01,EU02` and `?subsets=EU01&subsets=EU03` on G1 also answered `200`.

#### Without subsets

```bash
curl -s -i 'http://127.0.0.1:27086/efti/api/v1/authority/dataset/G1/mock/11111111-0000-4000-8000-000000000001' \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
x-trace-id: 0b8c3300a17815b784fab51363791a57
content-length: 209

{"code":"BAD_REQUEST_GENERAL","detail":"The query parameter 'subsets' must list at least one subset, each one of EU01..EU07.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

#### Query form (not supported)

```bash
curl -s -i 'http://127.0.0.1:27086/efti/api/v1/authority/dataset?gateId=G1&platformId=mock&datasetId=11111111-0000-4000-8000-000000000001&subsets=EU01' \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
x-trace-id: 45e414e80b7edd477f7f9fccf367918d
content-length: 141

{"detail":{"detail":"/api/v1/send/","message":"/api/v1/send/","status":404,"title":"Not Found"},"error":"Remote gate dataset request failed"}
```

Without path parameters `gateId` is undefined, so the route takes the remote branch and calls
edelivery `/api/v1/send/` with an empty party.

## POST /efti/api/v1/authority/follow-up

Sends a follow-up message (FTI025) to the platform that holds the dataset. Local gate: the
`api/v1/follow-up-local` template. Other gate: xml-mapper builds the FTI025, edelivery sends it,
xml-mapper maps the FTI030 answer.

`POST /efti/api/v1/authority/follow-up` · on ruuter (G1 :27086) · internal token · source `DSL/Ruuter/efti/POST/api/v1/authority/follow-up.yml`

### Request

| Body field | Required | Type | Meaning |
|---|---|---|---|
| `uil` | yes | object | `{gateId, platformId, datasetId}` |
| `referenceIds` | yes | array | ids of earlier messages this follow-up refers to (UUIDs in the FTI025) |
| `message` | yes | string | the text |
| `files` | no | array | `{fileName, base64Content, mimeType}` |

`X-Request-Id` is the follow-up's query id. A repeat with the same `X-Request-Id` and the same
content is recognised (see [follow-up-local](./local-handlers.md#post-eftiapiv1follow-up-local)).

### Responses

| Status | When | Body |
|---|---|---|
| 201 | delivered (local or remote), and also for a recognised local repeat | the UIL `{datasetId, gateId, platformId}` |
| 400 | `uil`, `referenceIds` or `message` missing | `BAD_REQUEST_GENERAL` |
| 401 | token | `UNAUTHORIZED` |
| 409 | local: the same follow-up is still pending | `FOLLOW_UP_IN_PROGRESS` problem object |
| 4xx/5xx | remote: edelivery answered non-200 | `{"error":"Remote gate follow-up failed","detail":<edelivery body>}` |
| 502 | xml-mapper unreachable | `{"error":"XML mapper unavailable"}` |
| 502 | edelivery unreachable | `{"error":"The destination gate is unreachable","transportError":"<kind>"}` |

### Examples

These use datasets uploaded for this page (`f8e61aaf-…` on G1, `d82c6c61-…` on G2), not the
seed data.

#### Local

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/authority/follow-up \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H 'X-Request-Id: 621cf68e-2d68-4074-97cf-b6e377ace605' -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7"},"referenceIds":["<uuid>"],"message":"Please show the CMR"}'
```

```http
HTTP/1.1 201 Created
content-type: application/json
x-trace-id: e2af4aaff643b969e98e6aab44eb3036
content-length: 86
{"datasetId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7","gateId":"G1","platformId":"mock"}
```

The same request again (same `X-Request-Id`, same content) answered the same `201`.

#### Remote (G2)

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/authority/follow-up \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G2","platformId":"mock","datasetId":"d82c6c61-fb5a-4eed-8b30-59b05066918a"},"referenceIds":["<uuid>"],"message":"Please show the CMR"}'
```

```http
HTTP/1.1 201 Created
content-type: application/json
x-trace-id: 313ecadc3848694fabd5ef87df1c44b8
content-length: 86
{"datasetId":"d82c6c61-fb5a-4eed-8b30-59b05066918a","gateId":"G2","platformId":"mock"}
```

#### Missing fields

```bash
curl … -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"x"}}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
x-trace-id: 3585e7ddbbedc10b8dd156dec398d2cb
content-length: 231

{"code":"BAD_REQUEST_GENERAL","detail":"The request body must be a JSON object with 'uil' (object), 'referenceIds' (array) and 'message' (string).","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

#### Unknown gate

```http
HTTP/1.1 400 Bad Request
content-type: application/json
x-trace-id: b612536751ceed61627d969153a0af8c
content-length: 183

{"detail":{"detail":"Unknown party: EU-XX","instance":"/api/v1/send/EU-XX","message":"Unknown party: EU-XX","status":400,"title":"Bad Request"},"error":"Remote gate follow-up failed"}
```

Not run locally: the `409` (it needs a repeat within 120 s of a still-`PENDING` row) and the 502s.

## GET /efti/api/v1/follow-up/\{datasetId\}

Lists the follow-up log rows of a dataset, newest first.

`GET /efti/api/v1/follow-up/{datasetId}` · on ruuter (G1 :27086) · internal token · source `DSL/Ruuter/efti/GET/api/v1/follow-up.yml`

### Request

| Path / Query parameter | Required | Type | Meaning |
|---|---|---|---|
| `datasetId` | yes | path, UUID | the dataset (`follow_up_log.dataset_request_id`) |
| `requestId` | no | query, UUID | filters on `follow_up_id` (one log row's follow-up id) |
| `limit` | no | number | default 20, max 1000 |
| `offset` | no | number | default 0 |

### Responses

| Status | When | Body |
|---|---|---|
| 200 | always for a UUID | array of rows: `rowId, followUpId, requestingGateId, datasetRequestId, destinationGateId, destinationPlatformId, payload, status, failureReason, receivedAt, createdAt`; `[]` if none |
| 502 | no path parameter, a non-UUID, or ReSql failed | `{"error":"Data layer unavailable"}` |

### Examples

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/follow-up/f8e61aaf-ad38-4875-935d-b155dc47c6e7 \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 1d044a3fddebe15a5a1f900e0a1303d4
content-length: 2400
[{"createdAt":"2026-11-03T16:04:27.494128Z","datasetRequestId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7","destinationGateId":"G1","destinationPlatformId":"nope","failureReason":"platform_lookup_failed","followUpId":"d7a2eaf6-7212-4772-a0e6-16c5ee335770","payload":"x","receivedAt":"2026-11-03T16:04:27.494128Z","requestingGateId":"G1","rowId":"8829cbd0-3c13-41f9-a51b-61d760ac1848","status":"FAILED"},{"createdAt":"2026-11-03T16:04:27.477755Z",…,"status":"PENDING"},{"createdAt":"2026-11-03T16:04:27.367855Z",…,"destinationPlatformId":"mock",…,"followUpId":"7b0edc9a-76e7-4ad3-a1c6-fd26798102d0","payload":"Direct local follow-up",…,"status":"DELIVERED"},…]
```

```bash
curl -s -i 'http://127.0.0.1:27086/efti/api/v1/follow-up/f8e61aaf-ad38-4875-935d-b155dc47c6e7?requestId=7b0edc9a-76e7-4ad3-a1c6-fd26798102d0' \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 5f4cd136337f83aa04a3e6629a6d3608
content-length: 402
[{"createdAt":"2026-11-03T16:04:27.367855Z","datasetRequestId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7","destinationGateId":"G1","destinationPlatformId":"mock","failureReason":null,"followUpId":"7b0edc9a-76e7-4ad3-a1c6-fd26798102d0","payload":"Direct local follow-up","receivedAt":"2026-11-03T16:04:27.367855Z","requestingGateId":"G1","rowId":"2d104369-059c-4d05-8f59-808736d41ac4","status":"DELIVERED"}]
```

`?limit=1&offset=1` returned the second row only. An unknown UUID returns `[]`.

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/follow-up/not-a-uuid -H 'X-Internal-Service-Token: dev-internal-service-token-change-me'
curl -s -i http://127.0.0.1:27086/efti/api/v1/follow-up -H 'X-Internal-Service-Token: dev-internal-service-token-change-me'
```

Both:

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
content-length: 34

{"error":"Data layer unavailable"}
```

## GET /efti/api/v1/status/\{datasetId\}

Tells whether a dataset is registered at this gate and its current status. One current row per
platform.

`GET /efti/api/v1/status/{datasetId}` · on ruuter (G1 :27086) · internal token · source `DSL/Ruuter/efti/GET/api/v1/status.yml`

### Request

| Path / Query parameter | Required | Type | Meaning |
|---|---|---|---|
| `datasetId` | yes | path, UUID | the dataset |
| `platformId` | no | query | narrow to one platform |
| `gateId` | no | query | narrow to one gate |

### Responses

| Status | When | Body |
|---|---|---|
| 200 | at least one current row | `[{rowId, datasetId, platformId, gateId, status, createdAt}]` |
| 404 | no row (unknown id, or filtered out) | `{"error":"Not Found"}` |
| 502 | no path parameter, a non-UUID, or ReSql failed | `{"error":"Data layer unavailable"}` |

### Examples

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/status/f8e61aaf-ad38-4875-935d-b155dc47c6e7 \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: ad0da3e294a17c38d8fb5d2c2cf5e657
content-length: 195
[{"createdAt":"2026-11-03T16:04:03.528827Z","datasetId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7","gateId":"G1","platformId":"mock","rowId":"aa400902-8fcf-4388-8a56-2d8dfb1e6307","status":"ACTIVE"}]
```

`?platformId=mock&gateId=G1` gives the same row.

```bash
curl -s -i 'http://127.0.0.1:27086/efti/api/v1/status/f8e61aaf-ad38-4875-935d-b155dc47c6e7?gateId=G2' \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me'
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
x-trace-id: 804a6da35435887b34da9b6ff2b1543d
content-length: 21

{"error":"Not Found"}
```

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/status \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
x-trace-id: 1d066dcd1ee454259338accbfd5a0ed0
content-length: 34

{"error":"Data layer unavailable"}
```

## Notes

- `authority/search` never answers its own `400`. Its `validate_input` checks
  `incoming.body == null`, but Ruuter hands an absent body or a JSON `null` to the DSL as `{}`. A
  first call with no criteria therefore returns this gate's first 20 consignments with
  `x-poll-more: false`. ruuter-xroad has the same `== null` check, so the X-Road path is affected
  too: `POST /xroad/provide/v1/search` with body `{}` (client `DEV/GOV/70000099/testSubsystem`)
  answered `200`, `x-poll-more: false` and 12 588 bytes of unfiltered consignments.
- Local and remote search rows have different shapes. Local rows are raw `get_consignments` rows
  (`rowId`, `status`, dates, `null` arrays). Remote rows are xml-mapper `ConsignmentRow`s (no
  `rowId`, `status` or `createdAt`; empty arrays). ruuter-xroad normalises both through
  `transport-means/normalize`.
- In `authority/dataset` an unknown platform answers `404` with the text
  "Platform lookup failed (data layer unavailable)": the registry's `404` for the unknown id is
  caught by the `>= 300` check before the "Platform Not Found" branch. See
  [Local handlers](./local-handlers.md#notes).
- The mock platform returns the same canned dataset for any `datasetId`, also for one that is not
  registered. A real platform decides that itself.
- `GET /follow-up` declares `requestId` as "filter to one dataset request"; the SQL filters on
  `follow_up_id`.
- `status` and `follow-up` answer `502 Data layer unavailable` for a malformed id: ReSql answers
  `400` (invalid UUID), and the routes treat every status ≥ 300 as a data-layer failure.
- Postman: folder **4. Core authority API (gate-internal)** ("authority/search TST-001",
  "GET status/{datasetId}", …).
