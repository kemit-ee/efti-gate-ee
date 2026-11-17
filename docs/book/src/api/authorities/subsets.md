# subsets

Returns the eFTI subsets the calling organisation may request. The registry code comes only from
`X-Road-Client`, so a caller cannot ask about another organisation. One call covers all seven
subsets; cache the answer and decide locally which `dataset` requests you can make.

`GET /xroad/provide/v1/subsets` · on ruuter-xroad (G1 :27087, G2 :28087) · X-Road guard ·
source `DSL/Ruuter-xroad/xroad/GET/provide/v1/subsets.yml`

The X-Road clients below are development fixtures and public in the repo.

## Request

| Header | Required | Value |
|---|---|---|
| `X-Road-Client` | yes | `instance/memberClass/memberCode[/subsystemCode]` |
| `X-Road-Id` | yes | a UUID |

No query parameters, no body.

## Responses

| Status | When | Body |
|---|---|---|
| 200 | the client resolves to exactly one authority | `{authorityId, registryCode, subsets}`. `subsets: []` is a valid answer: registered, no entitlement. |
| 400 `MISSING_REQUIRED_HEADER` | no `X-Road-Id` | RFC 7807 (guard) |
| 400 `INVALID_REQUEST_ID` | `X-Road-Id` is not a UUID | RFC 7807 (guard) |
| 401 `UNAUTHORIZED` | `X-Road-Client` missing or malformed | RFC 7807 (guard) |
| 403 `FORBIDDEN` | no authority, or more than one, has this registry code | RFC 7807 (guard; the route checks again) |
| 502 `GATEWAY_UNAVAILABLE` | the route's own registry lookup failed (transport error or status ≥ 300) | RFC 7807, detail "The subset-entitlement lookup failed. …". Not run locally: needs a stopped registry, and the guard would answer `403` first. |

## Examples

### EU01 only, at G1

```bash
curl -i http://127.0.0.1:27087/xroad/provide/v1/subsets \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "authorityId": "auth-xroad-test",
  "registryCode": "70000099",
  "subsets": ["EU01"]
}
```

### EU01 and EU02, at G2

```bash
curl -i http://127.0.0.1:28087/xroad/provide/v1/subsets \
  -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "authorityId": "auth-xroad-tm",
  "registryCode": "70000093",
  "subsets": ["EU01", "EU02"]
}
```

### Registered, no entitlement

```bash
curl -i http://127.0.0.1:27087/xroad/provide/v1/subsets \
  -H 'X-Road-Client: DEV/GOV/70000096/x' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 200 OK
content-type: application/json

{
  "authorityId": "auth-xroad-empty",
  "registryCode": "70000096",
  "subsets": []
}
```

### Not registered → 403

```bash
curl -i http://127.0.0.1:27087/xroad/provide/v1/subsets \
  -H 'X-Road-Client: DEV/GOV/99999999/x' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)"
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

`DEV/GOV/70000091/x` (two authorities with that code) gives `403 FORBIDDEN` with the
"registry misconfiguration" detail, as on [echo](./echo.md).

### Bad headers

```bash
curl -i http://127.0.0.1:27087/xroad/provide/v1/subsets \
  -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' -H 'X-Road-Id: 123'
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

Without `X-Road-Id`: `400 MISSING_REQUIRED_HEADER`. Without `X-Road-Client`: `401 UNAUTHORIZED`.
The bodies are the ones shown on [echo](./echo.md).

## Notes

- The route reads the registry again instead of trusting the guard's result. It discloses
  entitlements, so it makes its own authorisation decision, and the registry may have changed
  between the two lookups.
- A registry outage is reported differently here than in the guard: the guard fails closed with
  `403`, this route's own lookup reports `502`. Because the guard runs first, a caller sees the
  `403`.
- `subsets` is the list from `registry/authorities/<id>.yml`. Changing it means changing that file
  and re-running `registry-build` (`docker compose up -d registry-build`).
- Postman: **1. CA -> Gate / Identity guard / subsets**.
