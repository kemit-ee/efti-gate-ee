# follow-up

Sends a free-text message (and optionally files) to the platform that holds a dataset, for
example "please confirm the gross weight". If the UIL names this gate, the gate forwards the
message to the platform. If it names another gate, the gate sends FTI025 over AS4 and that gate
forwards it. There is no subset check: a follow-up carries a message, not dataset content.

`POST /xroad/provide/v1/follow-up` · on ruuter-xroad (G1 :27087, G2 :28087) · X-Road guard ·
source `DSL/Ruuter-xroad/xroad/POST/provide/v1/follow-up.yml`, forwarding to
`DSL/Ruuter/efti/POST/api/v1/authority/follow-up.yml` and `…/api/v1/follow-up-local.yml`

The X-Road clients below are development fixtures and public in the repo.

## Request

| Header | Required | Value |
|---|---|---|
| `X-Road-Client` | yes | the caller |
| `X-Road-Id` | yes | a new UUID. A repeated id (also one used for a `dataset`) is rejected with `409`. It becomes the FTI025 query id and the `query_id` in `follow_up_log`. |
| `Content-Type` | yes | `application/json` |

| Body field | Required | Type | Meaning / validation |
|---|---|---|---|
| `uil` | yes | object | `{gateId, platformId, datasetId}` of the dataset the message is about |
| `referenceIds` | yes | array | the dataset requests this follow-up refers to. Use the `X-Road-Id` of your [`dataset`](./dataset.md) call. The first element is sent to a REST platform as `X-Dataset-Request-ID`. An empty array is accepted. |
| `message` | yes | string | the text |
| `files` | no | array of `{fileName, base64Content, mimeType}` | attachments, passed on to the platform / in FTI025 |

## Responses

| Status | When | Body |
|---|---|---|
| 201 | the platform (local) or the other gate (remote) accepted the message | the `uil` |
| 400 (engine) | `uil` not an object, `referenceIds` not an array, `message` not a string | `{"error":"Field type mismatch in body: message expected string, got number"}` |
| 400 `BAD_REQUEST_GENERAL` | `uil`, `referenceIds` or `message` missing | "The request body must be a JSON object with 'uil' (object), 'referenceIds' (array) and 'message' (string)." |
| 400 / 401 / 403 | guard | see [the guard](./index.md#the-guard) |
| 409 `DUPLICATE_REQUEST_ID` | this `X-Road-Id` was used before | `application/problem+json` |
| 201 with an error body | local: platform unknown or registry failed, `follow_up_log` insert failed (e.g. `datasetId` not a UUID), platform unreachable | `{"error":"Platform lookup failed (data layer unavailable)"}`, `{"error":"Failed to log follow-up message"}` etc. — a bug, see Notes |
| 502 `GATEWAY_UNAVAILABLE` | core answered ≥ 400 (remote: unknown gate, peer error) | `coreStatus`, `coreResponse` |
| 502 `GATEWAY_UNAVAILABLE` | core unreachable or no answer in 70 s | `transportError`. Not run locally. |

## Examples

### Local follow-up at G1

The `referenceIds` value is the `X-Road-Id` of a `dataset` call made just before.

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/follow-up \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},
       "referenceIds":["5b697314-77c0-47dc-baff-3d1b8b197939"],
       "message":"Please confirm the gross weight."}'
```

```http
HTTP/1.1 201 Created
content-type: application/json

{
  "datasetId": "11111111-0000-4000-8000-000000000001",
  "gateId": "G1",
  "platformId": "mock"
}
```

The gate wrote two `follow_up_log` rows for this `query_id`: `PENDING`, then `DELIVERED`.

### Remote follow-up: G1 → G2

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/follow-up \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G2","platformId":"mock","datasetId":"22222222-0000-4000-8000-000000000001"},
       "referenceIds":["8665317e-bf5a-4b4c-bdf7-3f399d050e6b"],
       "message":"Please confirm the gross weight."}'
```

```http
HTTP/1.1 201 Created
content-type: application/json

{
  "datasetId": "22222222-0000-4000-8000-000000000001",
  "gateId": "G2",
  "platformId": "mock"
}
```

The answer is the `uil` from G2's FTI030 response. The round trip took 0.47 s. G2's
`follow_up_log` has the `PENDING` and `DELIVERED` rows with `query_id` = the `X-Road-Id` of this
call; G1 does not log a remote follow-up.

### Remote follow-up: G2 → G1

```bash
curl -i -X POST http://127.0.0.1:28087/xroad/provide/v1/follow-up \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},
       "referenceIds":["8b055738-64a8-4eab-8bc1-7e27ee94ca6f"],
       "message":"Bitte Gewicht bestaetigen."}'
```

```http
HTTP/1.1 201 Created
content-type: application/json

{
  "datasetId": "11111111-0000-4000-8000-000000000001",
  "gateId": "G1",
  "platformId": "mock"
}
```

### With a file

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/follow-up \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},
       "referenceIds":["5b697314-77c0-47dc-baff-3d1b8b197939"],
       "message":"With a file.",
       "files":[{"fileName":"note.txt","base64Content":"aGVsbG8=","mimeType":"text/plain"}]}'
```

```http
HTTP/1.1 201 Created
content-type: application/json

{
  "datasetId": "11111111-0000-4000-8000-000000000001",
  "gateId": "G1",
  "platformId": "mock"
}
```

### An authority without subsets

No subset check: `DEV/GOV/70000096/x` (`subsets: []`) gets `201` for the same kind of request.

### Missing fields → 400

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/follow-up \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"message":"x"}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json

{
  "code": "BAD_REQUEST_GENERAL",
  "detail": "The request body must be a JSON object with 'uil' (object), 'referenceIds' (array) and 'message' (string).",
  "status": 400,
  "title": "Bad Request",
  "type": "https://api.efti.ee/errors/bad-request"
}
```

`{}` and a body without `message` give the same answer. A number as `message`:

```json
{"error": "Field type mismatch in body: message expected string, got number"}
```

### The same X-Road-Id twice → 409

Sending the local example again with the same `X-Road-Id`:

```http
HTTP/1.1 409 Conflict
content-type: application/problem+json

{
  "code": "DUPLICATE_REQUEST_ID",
  "detail": "This X-Road-Id has already been processed. Retry with a new request id if this is a new request.",
  "status": 409,
  "title": "Duplicate Request ID",
  "type": "https://api.efti.ee/errors/duplicate-request-id"
}
```

### Unknown gate → 502

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/follow-up \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"EU-XX","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"referenceIds":["r"],"message":"x"}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json

{
  "code": "GATEWAY_UNAVAILABLE",
  "coreResponse": {
    "detail": {
      "detail": "Unknown party: EU-XX",
      "instance": "/api/v1/send/EU-XX",
      "message": "Unknown party: EU-XX",
      "status": 400,
      "title": "Bad Request"
    },
    "error": "Remote gate follow-up failed"
  },
  "coreStatus": 400,
  "detail": "The gate's follow-up request failed. The gate's own status and response are carried in coreStatus and coreResponse.",
  "status": 502,
  "title": "Bad Gateway",
  "type": "https://api.efti.ee/errors/bad-gateway"
}
```

### Unknown local platform → 201 with an error body

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/follow-up \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"nope","datasetId":"11111111-0000-4000-8000-000000000001"},"referenceIds":["r"],"message":"x"}'
```

```http
HTTP/1.1 201 Created
content-type: application/json

{"error": "Platform lookup failed (data layer unavailable)"}
```

`follow_up_log` shows `PENDING`, then `FAILED` with `failure_reason = platform_lookup_failed`.

A `datasetId` that is not a UUID cannot be logged (`follow_up_log.dataset_request_id` is a
`uuid` column), and is also answered with `201`:

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/follow-up \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"not-a-uuid"},"referenceIds":["r"],"message":"x"}'
```

```http
HTTP/1.1 201 Created
content-type: application/json

{"error":"Failed to log follow-up message"}
```

## Notes

- **Bug: local failures are reported as `201`.** Core's `authority/follow-up.yml` calls
  `follow-up-local` as a `template:`, which returns only the body, not the status. Core checks the
  body only for `FOLLOW_UP_IN_PROGRESS` and otherwise answers `201`. So "Platform Not Found"
  (`404`), "Platform lookup failed" (`502`), "The destination platform is unreachable" (`502`),
  "Failed to log follow-up message" (`500`) and a platform's own error status all reach the caller
  as `201` (the second and fourth were run, the others follow from the code). Check the body for
  an `error` key.
- **Unknown platform is reported as an outage**, for the same reason as on
  [dataset](./dataset.md#notes): the registry's `404` is treated as a lookup failure.
- Core's "repeat" and "in progress" handling (`follow-up-local.yml`, same query id within 120 s)
  is not reachable through X-Road for a repeated `X-Road-Id`: the X-Road route answers `409
  DUPLICATE_REQUEST_ID` before core is called.
- `follow_up_log.requesting_gate_id` is filled with `uil.gateId`, the destination. A follow-up
  from G1 to G2 is stored at G2 with `requesting_gate_id = G2`.
  `dataset_request_id` holds `uil.datasetId`, not a dataset request id. The X-Road caller
  (authority) is not stored.
- Differences to the contract documents: the OpenAPI document and the integration guide say
  `referenceIds` is optional and the success status is `200`; the OpenAPI document types `files`
  as strings and says the route is "contract only - not implemented". The route requires
  `referenceIds`, takes `files` as objects, answers `201` and is implemented.
- The developer mock answers `200` with `{uil, status: "DELIVERED", message}` and checks nothing
  ([Developer mock](./developer-mock.md)).
- Postman: **1. CA -> Gate / Dataset and follow-up / follow-up - remote (peer gate, AS4 FTI025)**,
  **… - local**, **… - empty body -> 400**.
- Use case: [An authority sends a follow-up](../../use-cases/authority-follow-up.md).
