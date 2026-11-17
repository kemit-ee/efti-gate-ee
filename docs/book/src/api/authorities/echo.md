# echo

A connectivity test. It returns the authority the gate resolved from `X-Road-Client`, the
X-Road headers it received and the request body. Call it once when you set up the connection to
check that your registry code and subsets are what you expect. It is also the simplest way to
see every guard error.

`POST /xroad/provide/v1/echo` · on ruuter-xroad (G1 :27087, G2 :28087) · X-Road guard ·
source `DSL/Ruuter-xroad/xroad/POST/provide/v1/echo.yml`

The X-Road clients below are development fixtures and public in the repo.

## Request

| Header | Required | Value |
|---|---|---|
| `X-Road-Client` | yes | `instance/memberClass/memberCode[/subsystemCode]` |
| `X-Road-Id` | yes | a UUID |
| `X-Road-UserId` | no | echoed in `xRoadUserId` |
| `Content-Type` | no | `application/json` |

| Body | Required | Type | Meaning |
|---|---|---|---|
| any JSON | no | any | reflected verbatim in `echo`. A non-object (array, number, `null`) comes back wrapped as `{"value": …}`; no body comes back as `{}`. |

## Responses

| Status | When | Body |
|---|---|---|
| 200 | the guard passed | `xRoadClient`, `xRoadId`, `xRoadUserId` (or `null`), `authority` (the registry row: `id`, `name`, `registryCode`, `subsets`), `echo` |
| 400 `MISSING_REQUIRED_HEADER` | no `X-Road-Id` | RFC 7807 |
| 400 `INVALID_REQUEST_ID` | `X-Road-Id` is not a UUID | RFC 7807 |
| 400 (engine) | the body is not valid JSON | `{"error":"invalid JSON body: …"}` |
| 400 `BAD_REQUEST_GENERAL` | `incoming.body == null` in the DSL | RFC 7807. Not reachable in practice, see Notes. |
| 401 `UNAUTHORIZED` | `X-Road-Client` missing or malformed | RFC 7807 |
| 403 `FORBIDDEN` | the member code resolves to no authority, or to more than one | RFC 7807 |
| 405 | not POST | `{"allow":["POST"],"error":"Method Not Allowed"}` |

## Examples

### Known client

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/echo \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -H 'X-Road-UserId: DEV30101010007' \
  -d '{"hello":"world"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "authority": {
    "id": "auth-xroad-test",
    "name": "X-Road Test Authority",
    "registryCode": "70000099",
    "subsets": ["EU01"]
  },
  "echo": {"hello": "world"},
  "xRoadClient": "DEV/GOV/70000099/testSubsystem",
  "xRoadId": "d221c931-1a4c-448c-980e-e0dfca21d6b7",
  "xRoadUserId": "DEV30101010007"
}
```

### Member-level client (3 parts), at G2

```bash
curl -i -X POST http://127.0.0.1:28087/xroad/provide/v1/echo \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000093' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{}'
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "authority": {
    "id": "auth-xroad-tm",
    "name": "X-Road Transport Means Authority",
    "registryCode": "70000093",
    "subsets": ["EU01", "EU02"]
  },
  "echo": {},
  "xRoadClient": "DEV/GOV/70000093",
  "xRoadId": "c5518925-b4b4-49f3-bec3-ca6b40ca445c",
  "xRoadUserId": null
}
```

G2 loads the same authority files as G1, so both gates resolve the same client the same
way.

### A non-object body

```bash
curl -s -X POST http://127.0.0.1:27087/xroad/provide/v1/echo \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '[1,2]'
```

```json
{
  "authority": {"id": "auth-xroad-test", "name": "X-Road Test Authority", "registryCode": "70000099", "subsets": ["EU01"]},
  "echo": {"value": [1, 2]},
  "xRoadClient": "DEV/GOV/70000099/testSubsystem",
  "xRoadId": "1276e97b-d147-4c3f-866c-d9f03841e6e2",
  "xRoadUserId": null
}
```

### No `X-Road-Client` → 401

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/echo \
  -H 'Content-Type: application/json' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" -d '{}'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json

{
  "code": "UNAUTHORIZED",
  "detail": "X-Road-Client header is missing or malformed. Expected instance/memberClass/memberCode[/subsystemCode].",
  "status": 401,
  "title": "Authentication Required",
  "type": "https://api.efti.ee/errors/unauthorized"
}
```

`X-Road-Client: DEV/GOV/70000099/` (trailing slash) and `DEV/GOV/70000099/a/b` (5 parts) give the
same `401`.

### Unknown client → 403

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/echo \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/99999999/x' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" -d '{}'
```

```http
HTTP/1.1 403 Forbidden
content-type: application/problem+json

{
  "code": "FORBIDDEN",
  "detail": "The X-Road client does not resolve to an active competent authority.",
  "status": 403,
  "title": "Forbidden",
  "type": "https://api.efti.ee/errors/forbidden"
}
```

### Registry code used by two authorities → 403

The fixtures `auth-xroad-ambig-a` and `auth-xroad-ambig-b` both declare `70000091`.

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/echo \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000091/x' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" -d '{}'
```

```http
HTTP/1.1 403 Forbidden
content-type: application/problem+json

{
  "code": "FORBIDDEN",
  "detail": "The X-Road client registry code resolves to more than one active competent authority. This is a registry misconfiguration; contact the gate operator.",
  "status": 403,
  "title": "Forbidden",
  "type": "https://api.efti.ee/errors/forbidden"
}
```

### No `X-Road-Id` → 400

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/echo \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' -d '{}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/problem+json

{
  "code": "MISSING_REQUIRED_HEADER",
  "detail": "X-Road-Id header is required on every X-Road request.",
  "status": 400,
  "title": "Missing Required Header",
  "type": "https://api.efti.ee/errors/missing-required-header"
}
```

### `X-Road-Id` not a UUID → 400

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/echo \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H 'X-Road-Id: not-a-uuid' -d '{}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/problem+json

{
  "code": "INVALID_REQUEST_ID",
  "detail": "X-Road-Id must be a UUID. The gate maps it to its internal request id, which is consumed as a typed UUID on the cross-gate path.",
  "status": 400,
  "title": "Invalid Request ID",
  "type": "https://api.efti.ee/errors/invalid-request-id"
}
```

An upper-case UUID is accepted.

### Invalid JSON → 400 from the engine

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/echo \
  -H 'Content-Type: application/json' \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" -d '{oops'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json

{"error": "invalid JSON body: key must be a string at line 1 column 2"}
```

### Wrong method → 405

```bash
curl -i http://127.0.0.1:27087/xroad/provide/v1/echo \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 405 Method Not Allowed
content-type: application/json

{"allow": ["POST"], "error": "Method Not Allowed"}
```

## Notes

- `authority` is the registry row as the registry serves it, so new registry fields appear here
  without a DSL change.
- `X-Road-Issue` and `X-Road-Represented-Party` are not echoed. A request with both headers
  answers exactly like one without them.
- The DSL has a `400 BAD_REQUEST_GENERAL` "A JSON request body is required." branch for a missing
  body. The engine always supplies a body (`{}` when none is sent, `{"value": …}` for a
  non-object), so the branch did not trigger in any run.
- The guard denials (`401`, `400`, `403`) each add an `xroad.auth_denied` row to the audit log.
  A successful echo is not audited.
- The developer mock's echo answers the same shape with a canned authority, see
  [Developer mock](./developer-mock.md).
- Postman: **1. CA -> Gate / Identity guard / echo** and the four error requests next to it.
