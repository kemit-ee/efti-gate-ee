# Local platform handlers

Three gate-internal routes that act on **this** gate's platforms. The authority routes and the G2G
inbound handlers reuse the first two as a `template:`; they can also be called directly.

| Route | Called by |
|---|---|
| `POST /efti/api/v1/dataset-local` | `authority/dataset` (POST and GET form) and `dataset-xml` (FTI009 from a peer), as `template: api/v1/dataset-local` |
| `POST /efti/api/v1/follow-up-local` | `authority/follow-up` and `follow-up-xml` (FTI025 from a peer), as `template: api/v1/follow-up-local` |
| `POST /efti/api/v1/ping` | a reachability check; nothing in the DSL calls it |

All three are on ruuter (G1 :27086) behind `efti/POST/api/v1/.guard.yml`: they need
`X-Internal-Service-Token` (local value `dev-internal-service-token-change-me`, public in the
repository). A template call passes the guard again, so the calling route forwards the token.

## Platform lookup

Both handlers fetch `GET [#REGISTRY_URL]/platforms/<platformId lower-case>.json` and then:

| Registry answer | Result |
|---|---|
| transport failure or status ≥ 300 (also the registry's `404` for an unknown id) | `502 {"error":"Platform lookup failed (data layer unavailable)"}` |
| `200`, but no `id` or `status` not `ONLINE` | `404 {"error":"Platform Not Found"}` |
| `200`, ONLINE, with `eDeliveryCert` | eDelivery platform: xml-mapper builds the FTI009/FTI025, edelivery sends it to the platform's party id |
| `200`, ONLINE, no `eDeliveryCert` | REST platform: HTTP call to `baseUrl` with the registry `headers` |

What the gate calls on a REST platform is on
[What the gate calls on a platform](../platforms/platform-side-api.md).

## POST /efti/api/v1/dataset-local

Asks a platform of this gate for a dataset and returns the raw XML. No subset check here: the
callers check subsets before.

`POST /efti/api/v1/dataset-local` · on ruuter (G1 :27086) · internal token · source `DSL/Ruuter/efti/POST/api/v1/dataset-local.yml`

### Request

| Header | Required | Value |
|---|---|---|
| `X-Internal-Service-Token` | yes | internal token |
| `X-Request-Id` | yes in practice | UUID; sent to the platform as `x-request-id` |

| Body field | Required | Type | Meaning / validation |
|---|---|---|---|
| `uil` | yes | object | `{gateId, platformId, datasetId}` |
| `subsets` | yes | array | joined with `,` into `?subsets=` (REST) or put in the FTI009 (eDelivery). Codes are not checked here |

REST call: `GET <baseUrl>/v1/dataset/<datasetId>?subsets=<a,b>` with the registry `headers` plus
`x-request-id`. eDelivery call: `POST [#EDELIVERY_URL]/api/v1/send/<platform id>` (65 s timeout).

### Responses

| Status | When | Body |
|---|---|---|
| platform's status | the platform answered (any status) | `{"uil": {…}, "subsets": […], "xml": <platform body>}` |
| 400 | `uil` or `subsets` missing (Ruuter allowlist) | `{"error":"Field missing: <field>"}` |
| 401 | token | `UNAUTHORIZED` |
| 404 | platform exists but is not ONLINE | `{"error":"Platform Not Found"}` |
| 502 | registry failed or the id is unknown | `{"error":"Platform lookup failed (data layer unavailable)"}` |
| 502 | xml-mapper unreachable (eDelivery platform) | `{"error":"XML mapper unavailable"}` |
| 502 | platform unreachable | `{"error":"The platform is unreachable","transportError":"<kind>"}` |

### Examples

The dataset `f8e61aaf-…` was uploaded for this page.

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/dataset-local \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 781c27dfa2c0a14c1669a84ca2e31166
content-length: 862
{"subsets":["EU01"],"uil":{"datasetId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7","gateId":"G1","platformId":"mock"},"xml":"<rsm:SpecifiedSupplyChainConsignment\n    xmlns:rsm=\"urn:eu:move:eFTI:data:standard:FTI010GetCmdsResponse:1\"\n    xmlns=\"urn:eu:move:eFTI:data:standard:ReusableAggregateBusinessInformationEntity:34\"\n    xmlns:udt=\"urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34\">\n  <GrossWeightMeasure unitCode=\"KGM\">15000.00</GrossWeightMeasure>\n  <PackageQuantity>120</PackageQuantity>\n  <MainCarriageLogisticsTransportMovement>\n    <ModeCode>3</ModeCode>\n    <UsedLogisticsTransportMeans>\n      <ID schemeAgencyID=\"5\">MOCK-PLATE-1</ID>\n      <RegistrationTradeCountry><ID>EE</ID></RegistrationTradeCountry>\n    </UsedLogisticsTransportMeans>\n  </MainCarriageLogisticsTransportMovement>\n</rsm:SpecifiedSupplyChainConsignment>\n"}
```

```bash
curl … -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7"}}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
x-trace-id: 0117904241bffb67228d0ae1084020a9
content-length: 34

{"error":"Field missing: subsets"}
```

```bash
curl … -d '{"uil":{"gateId":"G1","platformId":"nope","datasetId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
x-trace-id: 055a309352ce4bb1b55557e8dd28dc6b
content-length: 59

{"error":"Platform lookup failed (data layer unavailable)"}
```

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/dataset-local -H 'Content-Type: application/json' -d '{}'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
x-trace-id: 224e0903ece8a3f4a41007557224dced
content-length: 176

{"code":"UNAUTHORIZED","detail":"This endpoint requires a valid X-Internal-Service-Token.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

Not run locally: the eDelivery platform branch (`mock-edelivery`), the not-ONLINE `404`, and the
transport failures.

## POST /efti/api/v1/follow-up-local

Delivers a follow-up to a platform of this gate and records it in `follow_up_log` (append-only).

`POST /efti/api/v1/follow-up-local` · on ruuter (G1 :27086) · internal token · source `DSL/Ruuter/efti/POST/api/v1/follow-up-local.yml`

### Request

| Header | Required | Value |
|---|---|---|
| `X-Internal-Service-Token` | yes | internal token |
| `X-Request-Id` | recommended | UUID. If it is a UUID it becomes the `query_id`, which makes repeats recognisable |

| Body field | Required | Type | Meaning |
|---|---|---|---|
| `uil` | yes | object | `{gateId, platformId, datasetId}` |
| `message` | yes (DSL allowlist) | string | the text; stored as `payload` |
| `referenceIds` | yes (DSL allowlist) | array | the first id is sent to a REST platform as `X-Dataset-Request-ID` |
| `files` | no | array | `{fileName, base64Content, mimeType}` |

### What it does

1. Inserts a `PENDING` row (`insert_follow_up_log`). The `follow_up_log_once` trigger skips the
   insert when the latest row with the same `query_id`, platform, dataset and content hash is
   `DELIVERED`, or `PENDING` and younger than 120 s.
2. Insert skipped → `get_follow_up_state`: `DELIVERED` → `200` (a finished repeat; nothing is
   sent again); otherwise → `409 FOLLOW_UP_IN_PROGRESS`.
3. Platform lookup (table above).
4. REST platform: `POST <baseUrl>/v1/dataset/<datasetId>/follow-up` with the body. eDelivery
   platform: FTI025 via xml-mapper and edelivery.
5. Success → a `DELIVERED` row, answer with the platform's status and the UIL.
6. Failure → a `FAILED` row with `failure_reason` (`platform_not_found`,
   `platform_lookup_failed`, `forward_unreachable`, `forward_status_<code>`).

### Responses

| Status | When | Body |
|---|---|---|
| platform's status (201 from the mock) | delivered | the UIL |
| 200 | repeat of a delivered follow-up (same `X-Request-Id` and content) | the UIL |
| 400 | no `uil` | `BAD_REQUEST_GENERAL`, "The request body must be a JSON object with a 'uil' object." |
| 401 | token | `UNAUTHORIZED` |
| 404 | platform not ONLINE | `{"error":"Platform Not Found"}` |
| 409 | same follow-up still pending | `FOLLOW_UP_IN_PROGRESS` |
| 500 | the log insert failed, or it was skipped without a UUID `X-Request-Id` | `{"error":"Failed to log follow-up message"}` |
| 502 | registry failed or unknown platform | `{"error":"Platform lookup failed (data layer unavailable)"}` |
| 502 | platform unreachable | `{"error":"The destination platform is unreachable"}` |
| platform's status | the platform answered ≥ 300 | the UIL (the row is `FAILED`) |

### Examples

First delivery:

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/follow-up-local \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H 'X-Request-Id: 76519c10-a7d5-44f1-a6e5-e708442bd5eb' -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7"},"referenceIds":["<uuid>"],"message":"Direct local follow-up"}'
```

```http
HTTP/1.1 201 Created
content-type: application/json
x-trace-id: 3c8c9b64c2fcf287e8d5c064a564476f
content-length: 86
{"datasetId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7","gateId":"G1","platformId":"mock"}
```

The same request again:

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 2b1c316ddae00e33a0ac69ccb1064168
content-length: 86
{"datasetId":"f8e61aaf-ad38-4875-935d-b155dc47c6e7","gateId":"G1","platformId":"mock"}
```

No `uil`:

```http
HTTP/1.1 400 Bad Request
content-type: application/json
x-trace-id: bec613654629d7581a0d8d037d9001ce
content-length: 184

{"code":"BAD_REQUEST_GENERAL","detail":"The request body must be a JSON object with a 'uil' object.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

Unknown platform `nope` (a `PENDING` and then a `FAILED` row with
`failureReason: platform_lookup_failed` were written; see
[GET follow-up](./core-authority.md#get-eftiapiv1follow-updatasetid)):

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
x-trace-id: 4de1e7c2ea7be1e424b1f06b7cfe02cc
content-length: 59

{"error":"Platform lookup failed (data layer unavailable)"}
```

Not run locally: `409`, `500`, the not-ONLINE `404` and the eDelivery platform branch.

## POST /efti/api/v1/ping

Reachability check. Answers `204`.

`POST /efti/api/v1/ping` · on ruuter (G1 :27086) · internal token · source `DSL/Ruuter/efti/POST/api/v1/ping.yml`

### Responses

| Status | When | Body |
|---|---|---|
| 204 | token valid | none, but `content-length: 2` (known issue) |
| 401 | token missing or wrong | `UNAUTHORIZED` |
| 405 | another method | `{"allow":["POST"],"error":"Method Not Allowed"}` |

### Examples

```bash
curl -v --max-time 5 -X POST http://127.0.0.1:27086/efti/api/v1/ping \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```text
> POST /efti/api/v1/ping HTTP/1.1
> Host: 127.0.0.1:27086
> User-Agent: curl/8.5.0
> Accept: */*
> X-Internal-Service-Token: dev-internal-service-token-change-me
> X-Request-Id: 46a4d2b1-f691-497c-afd1-ea4654a85703
>
< HTTP/1.1 204 No Content
< content-type: application/json
< traceparent: 00-18af48dc89741e0ebec01c7f3b6de9f2-40f2955cba32630f-01
< x-trace-id: 18af48dc89741e0ebec01c7f3b6de9f2
< content-length: 2
< date: Tue, 03 Nov 2026 16:04:45 GMT
<
* Connection #0 to host 127.0.0.1 left intact
```

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/ping -H 'X-Internal-Service-Token: dev-internal-service-token-change-me'
```

```http
HTTP/1.1 405 Method Not Allowed
content-type: application/json
allow: POST
x-trace-id: f4de0bc0cd0759d236d5d2f66a4ae6ea
content-length: 47

{"allow":["POST"],"error":"Method Not Allowed"}
```

## Notes

- `ping` sends `204` with `content-length: 2` and no body. curl ignores it; Postman/Newman and
  other clients that trust Content-Length wait for the 2 bytes and hang. See
  [Known issues](../../reference/known-issues.md#http-protocol).
  Postman: the separate known-issues collection.
- The "Platform Not Found" `404` is only reached for a platform that exists but is not ONLINE. An
  unknown platform id gets the registry's `404`, which the `>= 300` check turns into
  `502 Platform lookup failed (data layer unavailable)`. The message is misleading: the data layer
  is fine.
- `follow-up-local` writes `requestingGateId = uil.gateId`, i.e. this gate, also when the follow-up
  came from a peer over `follow-up-xml`. `destinationGateId` gets the same value.
- The trigger only skips after `DELIVERED` or a `PENDING` row younger than 120 s. A repeat while a
  delivery is still running gets `409`. A repeat after a `FAILED` row, or after a `PENDING` row
  older than 120 s (a crashed run), inserts a new `PENDING` row and tries again.
