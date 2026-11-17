# Conventions

Rules that hold for every API of the gate: where each service listens, how requests are
correlated, which content types and error bodies to expect, how lists are paged, how dates and
ids are written, how stored data changes, and how long the gate waits.

The tokens, keys and X-Road clients on this page are development values. They are public in the
repository ([Ports and credentials](../reference/ports-and-credentials.md)).

## Base URLs

One gate is several services. Each one has its own base URL. Locally every port is bound to
`127.0.0.1`.

| Service | Paths | Who calls it | G1 | G2 | Inside the gate |
|---|---|---|---|---|---|
| ruuter | `/efti/**`, `/platforms/v1/**`, `/admin/v1/**`, `/auth/**`, `/ops/v1/**`, `/mock-platform/v1/**`, `/services/msh` | platforms, the admin UI, CronManager, peer gates (AS4 edge), the X-Road adapter | `http://127.0.0.1:27086` | `http://127.0.0.1:28086` | `http://ruuter:8086` |
| ruuter-xroad | `/xroad/provide/**`, `/xroad/health/ready` | competent authorities, through the X-Road Security Server | `http://127.0.0.1:27087` | `http://127.0.0.1:28087` | `http://ruuter-xroad:8087` |
| ruuter-xroad-mock | `/developer/**` | authority developers | `http://127.0.0.1:27088` | `http://127.0.0.1:28088` | `http://ruuter-xroad-mock:8088` |
| edelivery | `/api/v1/send/{party}`, `/services/msh`, `/health` | core (send), peer gates (MSH) | `http://127.0.0.1:27081` | `http://127.0.0.1:28081` | `http://edelivery:8081` |
| xml-mapper | `/api/v1/**`, `/health` | core only | `http://127.0.0.1:27082` | `http://127.0.0.1:28082` | `http://xml-mapper:8082` |
| resql | `/efti/<query>` | ruuter only | `http://127.0.0.1:27090` | `http://127.0.0.1:28090` | `http://resql:8090` |
| registry | `/gates.json`, `/gates/<id>.json`, `/platforms…`, `/authorities…` | ruuter, edelivery | `http://127.0.0.1:27080` | `http://127.0.0.1:28080` | `http://registry:8080` |

An authority does not call ruuter-xroad directly in production. It calls its own Security Server.
The consumer URL in `docs/developer/x_road_openapi.yaml` is
`https://{securityServer}/r1/DEV/GOV/70001231/efti-gate/efti/v1/<operation>`. The Security Server
strips `/r1/…/efti` and forwards to `/xroad/provide/v1/<operation>`. Not run locally: there is no
Security Server in the local stack, so the examples set `X-Road-Client` themselves.

`docs/specs/openapi.yaml` lists `https://api.dev.efti.ee`, `https://api.staging.efti.ee` and
`https://api.efti.ee` as servers. Those hosts are not part of this repository and were not checked.

## Request ids

### X-Road-Id

Every request to `/xroad/**` (and to the developer mock `/developer/**`) needs `X-Road-Id`.
The X-Road guard (`DSL/Ruuter-xroad/xroad/.guard.yml`) checks it after `X-Road-Client`:

| Check | Result |
|---|---|
| `X-Road-Client` missing, or not `instance/memberClass/memberCode[/subsystemCode]` | `401 UNAUTHORIZED` (checked first) |
| `X-Road-Id` missing or empty | `400 MISSING_REQUIRED_HEADER` |
| `X-Road-Id` not a UUID (`^[0-9a-fA-F]{8}-…-[0-9a-fA-F]{12}$`, any version, any case) | `400 INVALID_REQUEST_ID` |

The X-Road protocol allows any unique string here. The gate does not: it maps the value to its
internal request id, and edelivery parses that as a UUID.

How the id travels:

1. ruuter-xroad forwards it to core as `x-request-id`.
2. core passes the same `x-request-id` to xml-mapper, ReSql-backed steps and edelivery.
3. xml-mapper writes it into the FTI message as `ExchangedDocument/RequestedSpecifiedQuery/ID`.
   `ExchangedDocument/ID` is a new random UUID per message.
4. edelivery uses it in the AS4 conversation id `<to gate>:<id>:<from gate>`.
5. The peer gate's edelivery and xml-mapper log under the same id.

Seen in the logs after one G1 → G2 dataset request with
`X-Road-Id: c95398b3-5f65-40ad-bb8b-1462ec0229a0`:

```text
efti-g1-xml-mapper  trace.id=c95398b3-…  POST /api/v1/dataset/request-to-xml: 200
efti-g1-edelivery   trace.id=c95398b3-…  Sending message to http://g2-edelivery:8081/services/msh: conversationId=G2:c95398b3-5f65-40ad-bb8b-1462ec0229a0:G1
efti-g2-edelivery   trace.id=c95398b3-…  Handling 'FTI009GetCmdsRequest' from G1:c95398b3-5f65-40ad-bb8b-1462ec0229a0:G2
efti-g2-xml-mapper  trace.id=c95398b3-…  POST /api/v1/dataset/request-to-json: 200
efti-g2-xml-mapper  trace.id=c95398b3-…  POST /api/v1/dataset/response-to-xml: 200
efti-g1-xml-mapper  trace.id=c95398b3-…  POST /api/v1/dataset/response-to-json: 200
```

What else the id is used for:

| Route | Use of `X-Road-Id` |
|---|---|
| `search`, `transport-means` (`scope: allgates`) | it becomes the search id, returned as `x-search-id`. A poll sends a new `X-Road-Id` and the old one as `searchId`. |
| `dataset`, `follow-up` | it is recorded in `request_id_cache`. A second request with the same id gets `409 DUPLICATE_REQUEST_ID`. |
| `dataset` | a later follow-up names this id in `referenceIds` ([follow-up](./authorities/follow-up.md)). |

### X-Request-Id

Gate-internal APIs (`/efti/api/v1/**`, edelivery send, xml-mapper) use `X-Request-Id`.

| Service | Missing header | Non-UUID value | Echoed on the response |
|---|---|---|---|
| ruuter (core) | accepted; core passes nothing on | accepted by core, fails later where a UUID is parsed | no |
| xml-mapper | a random UUID is generated | accepted where no UUID is parsed | only on the `*/request-to-json` routes, set from the parsed message id |
| edelivery send | a random UUID is generated | `400` `{"detail":"Invalid UUID string: abc",…}` | no |

Use a fresh UUID per request. The Postman collection does that with `{{rid}}`.

### traceparent and x-trace-id

Every ruuter, ruuter-xroad and ruuter-xroad-mock response carries a W3C `traceparent` and an
`x-trace-id` header (the 32-hex trace id). If the request carries a `traceparent`, the gate keeps
its trace id. ruuter log lines are tagged `[t=<first 8 hex of the trace id>]`. xml-mapper and
edelivery do not send these headers; their JSON logs use the request id as `trace.id`.

```bash
curl -i http://127.0.0.1:27086/efti/health/ready \
  -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01'
```

```http
HTTP/1.1 200 OK
content-type: application/json
traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
x-trace-id: 0af7651916cd43dd8448eb211c80319c

"OK"
```

Without a `traceparent`, a new trace id is generated:

```http
HTTP/1.1 200 OK
content-type: application/json
traceparent: 00-ace46deafc43d114d6cd8b7e7d09a9f4-553459e2453a2002-01
x-trace-id: ace46deafc43d114d6cd8b7e7d09a9f4
```

Some early rejections carry neither header: the engine's `invalid JSON body` 400 and the
`/services/msh` 415 (both shown below).

## Content types

| Where | Request | Response |
|---|---|---|
| ruuter routes (core, X-Road, admin, ops) | `application/json` | `application/json` |
| Problem bodies from guards (X-Road guard, core service-token guard, platform `X-Api-Key` guard, admin JWT guard, ops guard, `/services/msh` guard, `DUPLICATE_REQUEST_ID`) | – | `application/problem+json` |
| Problem bodies from route steps (`FORBIDDEN_SUBSET`, `MISSING_SUBSET`, `BAD_REQUEST_GENERAL`, `GATEWAY_UNAVAILABLE`, …) | – | `application/json` |
| Platform upload `/platforms/v1/consignments[/{datasetId}]` | `text/xml` (raw FTI004 or `ParameterIDSetCriteria`) or `application/json` `{"xml": "…"}` | `application/json` |
| edelivery send, xml-mapper `*-to-xml` | `text/xml` or `application/json` | `text/xml; charset=UTF-8` |
| AS4 edge `/services/msh` | `multipart/related` only, else `415` | AS4 |
| registry | – | `application/json`, `Cache-Control: no-store` |

FTI XML in a JSON body is a string (the `xml` field of a dataset answer, the `xml` field of an
upload).

## Error bodies

### RFC 7807 problem objects

Most errors are RFC 7807 problem objects. Switch on `code`, not on `title` or `detail`.

| Field | Always | Meaning |
|---|---|---|
| `type` | yes | `https://api.efti.ee/errors/<kebab-case>` (the URIs are identifiers; they do not resolve) |
| `code` | almost | upper-case code from the [error catalogue](../data/errors.md). Missing on the platform and admin guard answers. |
| `title` | yes | short text |
| `status` | yes | the HTTP status again |
| `detail` | yes | one sentence for a human |
| `instance` | no | ruuter never sets it. xml-mapper and edelivery do (see below). |

Extension members:

| Code | Extra fields |
|---|---|
| `FORBIDDEN_SUBSET` | `authorityId`, `deniedSubsets`, `permittedSubsets` |
| `GATEWAY_UNAVAILABLE` (core answered) | `coreStatus`, `coreResponse` (core's own body, verbatim) |
| `GATEWAY_UNAVAILABLE` (core unreachable) | `transportError`: `timeout`, `connect`, `request`, `body`, `decode` or `unknown` |

Example, from the X-Road guard:

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/problem+json
traceparent: 00-ae859bb935397d4b2ed7a13140561b8d-a2fcfaebb9d081f9-01
x-trace-id: ae859bb935397d4b2ed7a13140561b8d

{"code":"MISSING_REQUIRED_HEADER","detail":"X-Road-Id header is required on every X-Road request.","status":400,"title":"Missing Required Header","type":"https://api.efti.ee/errors/missing-required-header"}
```

### Other shapes

Some layers still answer in their own shape. The table lists every shape seen on the local
stack.

| Source | Shape | Example |
|---|---|---|
| Ruuter engine: body field of the wrong type (declared in `allowlist.body`) | `{"error": …}`, 400 | `{"error":"Field type mismatch in body: subsets expected array, got string"}` |
| Ruuter engine: body is not JSON | `{"error": …}`, 400, no trace headers | `{"error":"invalid JSON body: expected ident at line 1 column 2"}` |
| Ruuter engine: no such route | `{"error": …}`, 404 | `{"error":"Not Found"}` |
| Ruuter engine: route exists, other method | `{"allow": […], "error": …}`, 405 | `{"allow":["POST"],"error":"Method Not Allowed"}` |
| core handlers (`authority/dataset`, `dataset-local`, `follow-up-local`, …) | `{"error": …}` or `{"error": …, "detail": …}` | `{"error":"Platform lookup failed (data layer unavailable)"}`. The X-Road adapter wraps these in `coreResponse`. |
| platform upload | `{"error": …, "status": …}` | `{"error":"Invalid consignment XML","status":400}` |
| admin list routes, ReSql call failed | `{"error": …}`, 502 | `{"error":"Data layer unavailable"}` |
| xml-mapper, edelivery (Klite) | `{"detail","instance","message","status","title"}`, no `type`, no `code` | `{"detail":"Invalid UUID string: abc","instance":"/api/v1/send/G2","message":"Invalid UUID string: abc","status":400,"title":"Bad Request"}` |
| edelivery, unknown path | `text/html` | `<h1>404 Not Found</h1>No context found for request` |
| registry, unknown document | `{"error":"not found"}`, 404 | `GET /gates/G1.json` (upper case) |

Captured:

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" -d 'not json'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json

{"error":"invalid JSON body: expected ident at line 1 column 2"}
```

```bash
curl -i -X DELETE http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 405 Method Not Allowed
content-type: application/json
traceparent: 00-74f5143e5c6d686bf2d86f7182e1f079-45bc75c95df4b37b-01
x-trace-id: 74f5143e5c6d686bf2d86f7182e1f079

{"allow":["POST"],"error":"Method Not Allowed"}
```

```bash
curl -i -X POST http://127.0.0.1:27086/services/msh -H 'Content-Type: application/json' -d '{}'
```

```http
HTTP/1.1 415 Unsupported Media Type
content-type: application/problem+json

{"code":"UNSUPPORTED_MEDIA_TYPE","detail":"Content-Type must be multipart/related. The AS4 MSH endpoint does not accept other media types.","status":415,"title":"Unsupported Media Type","type":"https://api.efti.ee/errors/unsupported-media-type"}
```

## Pagination

Only the admin lists page. They take `limit` and `offset` as query parameters. Pages are cut in
two places: ReSql clamps `LIMIT LEAST(GREATEST(COALESCE(:limit, d), 0), 1000)` and
`OFFSET GREATEST(COALESCE(:offset, 0), 0)`; the registry lists are cut in the DSL with the same
rules.

| Endpoint | Default `limit` | Max | Cut in |
|---|---|---|---|
| `GET /admin/v1/users` | 20 | 1000 | `get_users.sql` |
| `GET /admin/v1/audit` | 20 | 1000 | `get_audit_log.sql` |
| `GET /admin/v1/consignments` | 100 (set by the route) | 1000 | `get_consignments.sql` |
| `GET /admin/v1/consignment-history` | 100 | 1000 | `get_consignment_history.sql` (live and archive each) |
| `GET /admin/v1/consignment-counts`, `consignment-summary` | 100 | 1000 | `get_consignment_counts.sql`, `get_consignment_summary.sql` |
| `GET /admin/v1/gates`, `platforms`, `authorities` (list mode) | 20 | 1000 | the route DSL (`slice`) |
| `GET /efti/api/v1/follow-up/{datasetId}` | 20 | 1000 | `get_follow_up_messages.sql` |

Behaviour at the edges:

- A negative `limit` becomes 0 (an empty page); a negative `offset` becomes 0.
  `GET /admin/v1/gates?limit=-1&offset=-5` answered `[]`.
- A non-numeric `limit` on a ReSql-backed list fails in ReSql and is reported as an outage:
  `GET /admin/v1/users?limit=abc` answered `502 {"error":"Data layer unavailable"}`.
- There is no total count and no next link. Ask for the next page until it is shorter than
  `limit`.
- Each list has a fixed order (users by `id`; the audit and consignment lists by time). Rows
  added while you page can shift the window, so a row can appear twice or be skipped.

Lists that do not page:

| Endpoint | Rows |
|---|---|
| X-Road `search` (local part) | at most 20: core calls `get_consignments` without `limit`, so the ReSql default 20 applies. There is no `offset`. |
| X-Road `transport-means` | at most 50 (`LIMIT 50` in `get_consignments_by_transport_means.sql`, on purpose) |

## Dates and times

| Where | Format | Example |
|---|---|---|
| JSON timestamps written by the gate (`createdAt`, `tokenRevokedAt`, …) | ISO 8601, UTC, `Z`, microseconds | `"2026-11-03T16:27:40.279424Z"` |
| JSON search results (`acceptanceDate`, `loadingDate`, …) | ISO 8601 UTC, converted from the XML value | `"2021-09-24T00:00:00Z"` |
| Search criteria | ISO 8601 with offset | `{"operator":"GE","date":"2026-11-01T00:00:00Z"}` ([Search criteria](../data/search-criteria.md)) |
| FTI XML `udt:DateTimeString` | EDIFACT code in `@format` | see below |
| `consignment` JSON of a dataset | the XML value, not converted | `{"dateTimeString":"202609140730+0300","dateTimeString@format":"205"}` |
| HTTP `date` header | RFC 9110 | `Tue, 03 Nov 2026 16:23:45 GMT` |

EDIFACT formats xml-mapper reads and writes (`code/xml-mapper/src/efti/xml/Formats.kt`):

| `@format` | Pattern | Example |
|---|---|---|
| `102` | `CCYYMMDD` | `20260914` |
| `203` | `CCYYMMDDHHMM` | `202609140730` |
| `204` | `CCYYMMDDHHMMSS` | `20260914073000` |
| `205` | `CCYYMMDDHHMM` + offset | `202609140730+0300` |
| `207` | `CCYYMMDDHHMMSS` + offset | `20261103162602Z` |

Values without an offset are read as UTC. The gate writes `IssueDateTime` in format `207`; it
renders UTC as `Z`, not `+0000` as in the XSD samples.

## Ids and case

| Id | Format | Case |
|---|---|---|
| `datasetId`, `rowId`, user `id`, request ids | UUID. In FTI XML: `<DatasetID schemeID="RFC 9562-4">`. | lower-case as stored |
| gate id | `G1`, `G2`: ASCII, `A-Za-z0-9._-`, max 64 | see below |
| platform id | `mock`, `mock-edelivery` | case-insensitive |
| authority id | `auth-xroad-tm` | – |
| authority `registryCode` | the Business Registry code, a string (`'70000093'`) | compared with `===` |

The registry is served as static files with lower-cased names (`/gates/g1.json`). The DSL
lower-cases an id before every by-id lookup, so `mock`, `MOCK` and `Mock` all find the platform.
Fetching the file yourself is case-sensitive:

```bash
curl -i http://127.0.0.1:27080/gates/G1.json
```

```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{"error":"not found"}
```

`/gates/g1.json` answers `200` with `"id": "G1"`. The id inside the document keeps its
canonical case. The database stores `gate_id` and `platform_id` as `CITEXT`.

One check is case-sensitive: the decision whether a UIL is local. Core compares `uil.gateId` with
its own id using `!=`. `g1` sent to G1 is therefore treated as another gate. edelivery
finds the AS4 party case-insensitively, so G1 sends the FTI009 to itself over AS4 and answers
`200`. See [UIL](../data/uil.md#routing).

## Append-only data

The gate's own tables are append-only. A change is a new row; nothing is updated in place.

- `consignments`: the current state of a dataset is the newest row for its
  `(platform_id, dataset_id)`, ordered by `(created_at, revision)`.
- `status` is `ACTIVE`, `INACTIVE` or `DELETED`. A re-upload adds an `ACTIVE` row. The expiry job
  adds an `INACTIVE` copy of a road row 14 days after acceptance
  ([Scheduled jobs](./operations/jobs.md)). An admin delete adds a `DELETED` row.
- Search and dataset lookups use only the newest row. `DELETED` rows are never returned.
- `users` follows the same rule: `get_users.sql` keeps the newest row per `id` and drops
  inactive users. `sessions` and `audit_log` only ever get new rows.
- Gates, platforms and authorities are not tables. They are YAML files in `registry/`, converted by
  `registry-build` and served by the registry service. Gates and platforms are `ONLINE` or `DISABLED`; authorities have no
  status, an authority exists while its file exists.

A dataset uploaded twice and then deleted. `GET /admin/v1/consignment-history?datasetId=…`,
the `live` rows reduced to `createdAt status mainTransportId`, newest first:

```text
2026-11-03T16:31:40.431642Z DELETED BOOK-F-UIL-2
2026-11-03T16:31:27.468074Z ACTIVE BOOK-F-UIL-2
2026-11-03T16:27:40.279424Z ACTIVE BOOK-F-UIL
```

After the second upload, a search for `BOOK-F-UIL` found nothing locally (the older row is not
current). After the delete, a search for `BOOK-F-UIL-2` found nothing locally either. Both
searches then asked the other gates (`x-poll-more: true`). The calls are in
[Consignments](./admin/consignments.md) and [search](./authorities/search.md).

## Retries and idempotency

The gate does not retry on its own. `docs/specs/non-functional.md` §4.2: a platform or peer
failure goes straight back to the caller, and the `retryable` flag in `errors.json` is a hint
for clients, never sent on the wire.

| Request | Safe to repeat? | How |
|---|---|---|
| X-Road `subsets`, `echo`, `search`, `transport-means` | yes | new `X-Road-Id` each time. To collect a fan-out, poll with `{poll: true, searchId}`. |
| X-Road `dataset` | yes, with a new `X-Road-Id` | the same id is rejected with `409 DUPLICATE_REQUEST_ID` before anything is sent |
| X-Road `follow-up` | with a new `X-Road-Id` | same `409` rule. Core answers `409 FOLLOW_UP_IN_PROGRESS` (retryable) while the same follow-up is not confirmed; after a failure or 120 s it is delivered again. |
| platform upload | yes | each upload adds a row; the newest wins |
| admin delete consignment | yes | adds another `DELETED` row |
| ops jobs | see [Scheduled jobs](./operations/jobs.md) | |

Retry `502`, `503` and `504` with backoff. Do not retry `400`, `401`, `403`, `404` or `409`
unless the error says so (`FOLLOW_UP_IN_PROGRESS`).

## Timeouts

Each hop waits a fixed time. The outer hop always waits longer than the inner one, so the caller
sees the inner error rather than a generic timeout.

| Hop | Timeout | Source |
|---|---|---|
| Any request to ruuter / ruuter-xroad | 90 s | `request_timeout_ms` in `ruuter.yaml`, `ruuter-xroad.yaml` |
| X-Road `dataset`, `follow-up` → core | 70 s | `DSL/Ruuter-xroad/xroad/POST/provide/v1/{dataset,follow-up}.yml` |
| X-Road `search` → core | 40 s | `…/search.yml` |
| X-Road `transport-means` → core | 15 s (first call), 40 s (poll) | `…/transport-means.yml` |
| core `dataset`, `follow-up` → edelivery (remote gate or eDelivery platform) | 65 s | `DSL/Ruuter/efti/POST/api/v1/authority/dataset.yml`, `dataset-local.yml`, `follow-up-local.yml` |
| core search fan-out → each peer | 62 s per peer, 90 s overall, 32 in parallel | `…/authority/search.yml` |
| core → REST platform | Ruuter default (15 s) | `dataset-local.yml` sets none |
| edelivery waiting for the AS4 answer | 60 s (`EDELIVERY_TIMEOUT_SECONDS`) | `code/edelivery/src/edelivery/EDeliveryClient.kt` |

A peer that never answers shows up after about 60 s as core status 504 with
`"No data received after 1m"` inside a `502 GATEWAY_UNAVAILABLE`
([dataset](./authorities/dataset.md)).

## Notes

- Rate limiting (`429 RATE_LIMIT_EXCEEDED`, `Retry-After`) belongs to the reverse proxy in front
  of the gate (`non-functional.md` §4.2). The local stack has no proxy. Not run locally.
- `non-functional.md` §4.1 names `GATE_BROADCAST_TIMEOUT_MS` (8 s) and `PLATFORM_TIMEOUT_MS`
  (30 s). No service reads them; the values above are the ones in the code.
- `errors.json` says every error has `Content-Type: application/problem+json` and an `instance`.
  The stack does not do that: route-level problem bodies are `application/json`, and ruuter never
  sets `instance`.
- `docs/specs/openapi.yaml` describes an `X-Request-ID` echo on responses. ruuter does not echo it.
