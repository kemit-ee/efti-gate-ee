# What the gate calls on a platform

The gate keeps only identifiers. When an authority (local, or through a peer gate) wants the
dataset or sends a follow-up, the gate calls the platform that owns the UIL. This page is the
contract the platform must implement.

Callers on the gate side: `POST /efti/api/v1/dataset-local` and `POST /efti/api/v1/follow-up-local`
(gate-internal, `X-Internal-Service-Token`) · source `DSL/Ruuter/efti/POST/api/v1/dataset-local.yml`,
`DSL/Ruuter/efti/POST/api/v1/follow-up-local.yml` · reached from the X-Road
[dataset](../authorities/dataset.md) and [follow-up](../authorities/follow-up.md) routes and from
inbound AS4 [FTI009 / FTI025](../gates/inbound-handlers.md). Details of the two handlers:
[Local platform handlers](../internal/local-handlers.md).

The examples call the handlers directly with the dev internal token
`dev-internal-service-token-change-me` (public in the repository).

## How the gate picks the platform

1. `uil.platformId` (lower-cased) is looked up as `/platforms/<id>.json` in the registry.
2. Registry answer not `2xx`, or not reachable: `502 Platform lookup failed (data layer unavailable)`.
   This includes an **unknown** platform id, because the registry answers `404` for it (see Notes).
3. `status` not `ONLINE`: `404 Platform Not Found`.
4. `eDeliveryCert` set: the platform is called over **AS4** through edelivery.
5. Otherwise: **REST** on `baseUrl`.

The gate does not check its identifier store first. Any `datasetId` is forwarded to the platform.

## REST: dataset

```text
GET <baseUrl>/v1/dataset/{datasetId}?subsets=<codes, comma-separated>
```

| Part | Value |
|---|---|
| `{datasetId}` | `uil.datasetId`, URL-encoded |
| `subsets` | the requested subset codes joined with `,` and URL-encoded (`EU01%2CEU02`) |
| Headers | every entry of `headers` from the registry file (e.g. `X-Api-Key: mock-secret-key` for `mock`), plus `x-request-id` (the caller's request id) |
| Body | none |
| Timeout | none set in the DSL; Ruuter's default for `http.get` (15 s, see the Ruuter book, `dsl/steps/http.md`) |

The platform answers:

| Status | Body |
|---|---|
| `200` | `Content-Type: text/xml`, a bare `<rsm:SpecifiedSupplyChainConsignment>` element in namespace `urn:eu:move:eFTI:data:standard:FTI010GetCmdsResponse:1`, without the FTI010 envelope. Example: `docs/developer/examples/platforms/dataset-response.xml`; fields: [Dataset content (FTI010)](../../data/dataset-fields.md). |
| other | Passed through: `dataset-local` answers with the platform's status and puts the platform's body into `xml`. |

The platform must limit the content to the requested subsets. The gate does not filter the answer.
A request from a peer gate is not checked by this gate, so `subsets` can be empty or hold codes the
platform does not know. See [eFTI subsets](../../data/subsets.md).

Real call, captured in the G1 Ruuter log while `dataset-local` ran:

```text
16:04:51.812 INFO  [t=18d3a15a mock-platform] ⏹ GET /mock-platform/v1/dataset/803dab72-dc75-4879-8386-50127588196b 200 6.6ms  from 172.24.0.7
16:04:51.813 INFO  [t=18d3a15a efti] ▸ query_rest_platform (http) 9.4ms → check_dataset_xml  method="GET" url="http://ruuter:8086/mock-platform/v1/dataset/803dab72-dc75-4879-8386-50127588196b?subsets=EU01%2CEU02" status=200
```

## REST: follow-up

```text
POST <baseUrl>/v1/dataset/{datasetId}/follow-up
```

| Part | Value |
|---|---|
| Headers | registry `headers`, plus `Content-Type: application/json`, `X-Dataset-Request-ID` (= `referenceIds[0]`), `x-request-id` |
| Body | the follow-up as the gate received it: `uil`, `referenceIds`, `message`, and `files` if present |
| Timeout | Ruuter default (15 s) |

```json
{
  "uil": { "gateId": "G1", "platformId": "mock", "datasetId": "803dab72-dc75-4879-8386-50127588196b" },
  "referenceIds": ["c9243fb0-3690-4434-9309-4b8d75174d47"],
  "message": "Please send the CMR scan."
}
```

The platform answers `2xx` (the mock answers `201`). The gate passes the platform's status on to
the caller with the `uil` as body. Any status `>= 300` is also passed through, and the follow-up log
gets a `FAILED` row with reason `forward_status_<status>`.

Real call (mock platform log):

```text
16:06:52.118 INFO  [t=5d8b4d3a mock-platform] ▸ log_request (log) → msg="Follow-up request for datasetId=803dab72-dc75-4879-8386-50127588196b: {"message":"Please send the CMR scan.","referenceIds":["c9243fb0-3690-4434-9309-4b8d75174d47"],"uil":{"datasetId":"803dab72-…","gateId":"G1","platformId":"mo…"
16:06:52.119 INFO  [t=5d8b4d3a mock-platform] ⏹ POST /mock-platform/v1/dataset/803dab72-dc75-4879-8386-50127588196b/follow-up 201 8.0ms  from 172.24.0.7
```

## AS4 platforms

For a platform with `eDeliveryCert`, the gate maps the request to XML in xml-mapper and posts it to
edelivery `POST /api/v1/send/<platform id>` (timeout 65 s on the Ruuter side; edelivery waits 1 min
for the answer).

| Gate sends | Platform answers |
|---|---|
| `FTI009GetCmdsRequest` (UIL, `SubsetID`s) | `FTI010GetCmdsResponse`, the **full** envelope with `SpecifiedSupplyChainConsignment` inside |
| `FTI025LodgeFollowUpCommRequest` | `FTI030LodgeFollowUpCommResponse` |

No answer within 1 minute: edelivery answers `504`, which `dataset-local` passes through.

## Health and ping

The gate does not call any health or ping endpoint on a platform. There is no ping job (see
`registry/README.md`). `/mock-platform/health` exists but nothing calls it.
`POST /efti/api/v1/ping` is a gate route that answers `204` itself; it does not reach a platform
(and has the 204-with-`content-length: 2` defect, see
[Known issues](../../reference/known-issues.md)):

```http
HTTP/1.1 204 No Content
content-type: application/json
content-length: 2
```

## `dataset-local` responses

| Status | When | Body |
|---|---|---|
| `200` (or the platform's status) | Platform answered | `{"uil": …, "subsets": […], "xml": "<platform body>"}` |
| `400` | `uil` or `subsets` missing | `{"error":"Field missing: subsets"}` (Ruuter allowlist) |
| `401` | No / wrong internal token | RFC 7807 `UNAUTHORIZED` |
| `404` | Platform `DISABLED` | `{"error":"Platform Not Found"}` |
| `502` | Registry `404` (unknown platform) or unreachable | `{"error":"Platform lookup failed (data layer unavailable)"}` |
| `502` | AS4 platform: xml-mapper unreachable | `{"error":"XML mapper unavailable"}` |
| `502` | Platform unreachable (connection refused, timeout) | `{"error":"The platform is unreachable","transportError":"<kind>"}` |

## `follow-up-local` responses

| Status | When | Body |
|---|---|---|
| platform's status (`201` for the mock REST platform, `200` over AS4) | Delivered | the `uil` |
| `200` | Same `x-request-id` and same content already `DELIVERED` | the `uil` (not sent again) |
| `400` | No `uil` | RFC 7807 `BAD_REQUEST_GENERAL` |
| `401` | No / wrong internal token | RFC 7807 `UNAUTHORIZED` |
| `404` | Platform `DISABLED` | `{"error":"Platform Not Found"}` |
| `409` | Same request id and content still `PENDING` (< 120 s) | RFC 7807 `FOLLOW_UP_IN_PROGRESS` |
| `500` | Follow-up log write failed | `{"error":"Failed to log follow-up message"}` |
| `502` | Unknown platform / registry down | `{"error":"Platform lookup failed (data layer unavailable)"}` |
| `502` | Platform unreachable | `{"error":"The destination platform is unreachable"}` |

## How platform errors reach the authority

On the X-Road [dataset](../authorities/dataset.md) route, core's `authority/dataset` treats any
`dataset-local` answer without `xml` as "local platform error" and returns it with status `404`.
The X-Road layer then answers `502 GATEWAY_UNAVAILABLE` and carries core's status and body in
`coreStatus` / `coreResponse`. Run with an unknown platform id:

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"nope","datasetId":"803dab72-dc75-4879-8386-50127588196b"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
content-length: 330

{"code":"GATEWAY_UNAVAILABLE","coreResponse":{"error":"Platform lookup failed (data layer unavailable)"},"coreStatus":404,"detail":"The gate's dataset request failed. The gate's own status and response are carried in coreStatus and coreResponse.","status":502,"title":"Bad Gateway","type":"https://api.efti.ee/errors/bad-gateway"}
```

A platform that answers with an error status and a body still produces an `xml` field, so the
answer goes on to xml-mapper's `response-to-json` instead. Not run locally: the registry headers
of `mock` are fixed and the mock never answers an error to the gate.

## Examples

### Dataset from the REST platform

```bash
curl -i -X POST http://127.0.0.1:27086/efti/api/v1/dataset-local \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H 'Content-Type: application/json' -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"803dab72-dc75-4879-8386-50127588196b"},"subsets":["EU01","EU02"]}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 869
{"subsets":["EU01","EU02"],"uil":{"datasetId":"803dab72-dc75-4879-8386-50127588196b","gateId":"G1","platformId":"mock"},"xml":"<rsm:SpecifiedSupplyChainConsignment\n    xmlns:rsm=\"urn:eu:move:eFTI:data:standard:FTI010GetCmdsResponse:1\"\n …  <ID schemeAgencyID=\"5\">MOCK-PLATE-1</ID>\n …</rsm:SpecifiedSupplyChainConsignment>\n"}
```

### Dataset from the AS4 platform

```bash
curl -i -X POST http://127.0.0.1:27086/efti/api/v1/dataset-local \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me' \
  -H 'Content-Type: application/json' -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock-edelivery","datasetId":"5a313ebf-25f6-4a97-bfc7-b951fe70da38"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 2295
{"subsets":["EU01"],"uil":{"datasetId":"5a313ebf-25f6-4a97-bfc7-b951fe70da38","gateId":"G1","platformId":"mock-edelivery"},"xml":"<rsm:FTI010GetCmdsResponse\n …<TypeCode>010</TypeCode>\n …<RequestedSpecifiedQuery>\n    <ID schemeID=\"RFC 9562-4\">1f52e663-174d-4995-9b58-cf93a182b5be</ID>\n …<GateID>POC</GateID>\n      <PlatformID>demo</PlatformID>\n …"}
```

The round trip took 0.16 s through the AS4 loopback (edelivery log: `GET http://ruuter:8086/mock-platform/v1/dataset-edelivery … 200`,
then `Handling 'FTI010GetCmdsResponse' from mock-edelivery:1f52e663-…:G1`).

### Unknown platform, missing field, no token

```bash
T='X-Internal-Service-Token: dev-internal-service-token-change-me'
curl -i -X POST http://127.0.0.1:27086/efti/api/v1/dataset-local -H "$T" -H 'Content-Type: application/json' \
  -d '{"uil":{"gateId":"G1","platformId":"nope","datasetId":"803dab72-dc75-4879-8386-50127588196b"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
content-length: 59

{"error":"Platform lookup failed (data layer unavailable)"}
```

Without `subsets`:

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 34

{"error":"Field missing: subsets"}
```

Without the token:

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 176

{"code":"UNAUTHORIZED","detail":"This endpoint requires a valid X-Internal-Service-Token.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

### Follow-up to the REST platform, then the same again

```bash
curl -i -X POST http://127.0.0.1:27086/efti/api/v1/follow-up-local -H "$T" -H 'Content-Type: application/json' \
  -H 'X-Request-Id: 0dbc175e-61b6-493a-8d89-d78aea0f865b' \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"803dab72-dc75-4879-8386-50127588196b"},"referenceIds":["c9243fb0-3690-4434-9309-4b8d75174d47"],"message":"Please send the CMR scan."}'
```

```http
HTTP/1.1 201 Created
content-type: application/json
content-length: 86
{"datasetId":"803dab72-dc75-4879-8386-50127588196b","gateId":"G1","platformId":"mock"}
```

The same request again (same `X-Request-Id`, same content) is not forwarded:

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 86
{"datasetId":"803dab72-dc75-4879-8386-50127588196b","gateId":"G1","platformId":"mock"}
```

### Follow-up to the AS4 platform

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 96
{"datasetId":"5a313ebf-25f6-4a97-bfc7-b951fe70da38","gateId":"G1","platformId":"mock-edelivery"}
```

### Follow-up errors

Unknown platform:

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
content-length: 59

{"error":"Platform lookup failed (data layer unavailable)"}
```

Body without `uil` (`{"message":"x","referenceIds":[]}`):

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 184

{"code":"BAD_REQUEST_GENERAL","detail":"The request body must be a JSON object with a 'uil' object.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

### Not run locally

- `404 Platform Not Found` for a `DISABLED` platform, a platform that answers an error status,
  an unreachable platform (`502 … unreachable`), the `409` in-progress case and the `500` log
  failure: they need a changed registry, a stopped container or a slow platform.

## Notes

- **Unknown platform gives `502`, not `404`.** Both handlers send every registry status `>= 300`
  to the "lookup failed" branch. The registry answers `404 {"error":"not found"}` for an id it
  does not serve, so an unknown platform id is reported as a data-layer outage. The comment in
  both DSL files says the `404` case was meant to reach "Platform Not Found".
- The mock's AS4 answer carries a fixed UIL (`POC` / `demo` / `550e8400-…0000`), not the UIL that
  was asked for. The gate does not compare them.
- `docs/developer/platform_api_integration_guide.md` (Estonian) describes the same REST contract.
  It says headers are configured in the admin UI; they are the `headers` of the registry file.
