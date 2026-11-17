# Health and diagnostics

Every probe and diagnostic route of a gate. Load balancers, container health checks and operators
call them. None of them needs a credential.

| Service | Route | Checks a dependency | Used by the container healthcheck |
|---|---|---|---|
| ruuter | `GET /efti/health/live` | no | no |
| ruuter | `GET /efti/health/ready` | database (via ReSql) | yes (`curl -sf …/efti/health/ready`) |
| ruuter, ruuter-xroad, ruuter-xroad-mock | `GET /health` (Ruuter built-in) | no | no |
| ruuter-xroad | `GET /xroad/health/ready` | database (via ReSql) | yes |
| edelivery | `GET /health`, `GET /services/msh`, `GET /metrics` | no | none set |
| xml-mapper | `GET /health`, `GET /metrics` | no | none set |
| resql | `GET /health` | no | yes (`resql health --url …/health`) |
| registry | `GET /health.json` | no (written by `registry-build`) | yes (`wget --spider …/health.json`) |
| ruuter | `GET /efti/api/v1/test/baasikontoroll` | database | no |
| ruuter | `GET /efti/api/v1/test/lubatud`, `/test/piiratud` | TIM | no |

edelivery and xml-mapper have only `/health`. There is no `/health/live` or `/health/ready` on
them (both answer `404`).

## GET /efti/health/live

Liveness. Answers as soon as Ruuter runs; it calls nothing.

`GET /efti/health/live` · on ruuter (G1 :27086) · no auth · source `DSL/Ruuter/efti/GET/health/live.yml`

### Request

No headers, parameters or body.

### Responses

| Status | When | Body |
|---|---|---|
| 200 | always, once Ruuter serves the route | `"OK"` (a JSON string, `content-type: application/json`) |

### Example

```bash
curl -s -i http://127.0.0.1:27086/efti/health/live
```

```http
HTTP/1.1 200 OK
content-type: application/json
traceparent: 00-c816ca174c56dc7e4866b48cf82bb12c-57750fedddc4a3fc-01
x-trace-id: c816ca174c56dc7e4866b48cf82bb12c
content-length: 4

"OK"
```

## GET /efti/health/ready

Readiness. Ruuter calls ReSql `get_db_status`; the database must answer.

`GET /efti/health/ready` · on ruuter (G1 :27086) · no auth · source `DSL/Ruuter/efti/GET/health/ready.yml`

### Request

No headers, parameters or body.

### Responses

| Status | When | Body |
|---|---|---|
| 200 | ReSql answered `get_db_status` with a status below 300 | `"OK"` |
| 503 | ReSql unreachable (transport status 0) or answered ≥ 300 | `"Database unavailable"` (a JSON string) |

### Example

```bash
curl -s -i http://127.0.0.1:27086/efti/health/ready
```

```http
HTTP/1.1 200 OK
content-type: application/json
traceparent: 00-43475fc65a254ebb7927f56f6753c273-c9fe84835c1e58b2-01
x-trace-id: 43475fc65a254ebb7927f56f6753c273
content-length: 4

"OK"
```

The same call on G2 (`http://127.0.0.1:28086/efti/health/ready`) answers the same.

Not run locally: the `503` case. It needs the database or ReSql down, and this book does not stop
containers.

## Ruuter built-in: GET /health

The Ruuter framework answers `GET /health` itself, without a DSL file. It runs no step, so it has
no `x-trace-id`.

```bash
curl -s -i http://127.0.0.1:27086/health
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 15

{"status":"ok"}
```

ruuter-xroad (`:27087/health`) and ruuter-xroad-mock (`:27088/health`) answer the same.

## GET /xroad/health/ready

Readiness of the X-Road provider. Same logic as `/efti/health/ready`. It is public: the
`xroad/GET/health/.guard.yml` overrides the X-Road guard, so no `X-Road-Client` is needed. The
Security Server can use it as its own probe.

`GET /xroad/health/ready` · on ruuter-xroad (G1 :27087) · no auth · source `DSL/Ruuter-xroad/xroad/GET/health/ready.yml`

### Responses

| Status | When | Body |
|---|---|---|
| 200 | ReSql answered `get_db_status` with a status below 300 | `"OK"` |
| 503 | ReSql unreachable or answered ≥ 300 | `"Database unavailable"` |

### Example

```bash
curl -s -i http://127.0.0.1:27087/xroad/health/ready
```

```http
HTTP/1.1 200 OK
content-type: application/json
traceparent: 00-1d026c8a1b2ea9d294c44b558409fe91-9562fcf81ed336bd-01
x-trace-id: 1d026c8a1b2ea9d294c44b558409fe91
content-length: 4

"OK"
```

## edelivery and xml-mapper

Both are klite servers (`code/edelivery/src/Launcher.kt`, `code/xml-mapper/src/Launcher.kt`). Each
registers `context("/health") { get { "OK" } }` and klite's `metrics()` endpoint. Neither probe
checks a dependency. `/health` sits outside `/api/v1`, so it does not need the internal token.

| Route | Service | Status | Body |
|---|---|---|---|
| `GET /health` | edelivery | 200 | `OK` with `Content-type: text/xml` (edelivery renders every string as XML) |
| `GET /health` | xml-mapper | 200 | `OK`, `text/plain` |
| `GET /services/msh` | edelivery | 200 | `eDelivery MSH endpoint is up, please send POST requests` |
| `GET /metrics` | both | 200 | OpenMetrics text |

```bash
curl -s -i http://127.0.0.1:27081/health
curl -s -i http://127.0.0.1:27082/health
curl -s -i http://127.0.0.1:27081/services/msh
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 2

OK
```

```http
HTTP/1.1 200 OK
Transfer-encoding: chunked
Content-type: text/plain; charset=UTF-8

OK
```

```http
HTTP/1.1 200 OK
Content-type: text/xml; charset=UTF-8
Content-length: 55

eDelivery MSH endpoint is up, please send POST requests
```

### Metrics

```bash
curl -s http://127.0.0.1:27081/metrics
```

```text
edelivery_async_response_timeouts_total 0
edelivery_parties_online_total 3
edelivery_messages_inbound_total_fti010getcmdsresponse 8
edelivery_messages_inbound_total_fti025lodgefollowupcommrequest 6
edelivery_messages_inbound_total_fti009getcmdsrequest 8
edelivery_messages_inbound_total_fti021searchidentifierresponse 6
edelivery_messages_inbound_total_fti030lodgefollowupcommresponse 6
edelivery_messages_inbound_total_fti019searchidentifierrequest 6
edelivery_inbound_dedup_unavailable_total 0
started_at{value="2026-11-03T13:54:02.307856683Z"} 1
requests_active 1
edelivery_turvis_shadow_dropped_total 0
instance_id{value="7ef2"} 1
http_requests_total_get_2xx 7
http_requests_total_post_2xx 60
http_requests_total_get_4xx 2
http_requests_total_post_4xx 7
heap_mb_used 21.563065
heap_mb_size 26.652344
heap_mb_max 595.5
heap_mb_rss 94.50391
edelivery_turvis_shadow_security_discrepancies_total 0
requests_total 0
http_request_errors_total 9
http_request_duration_ms_avg 68.10526315789474
edelivery_messages_outbound_total_success 40
# EOF
```

```bash
curl -s http://127.0.0.1:27082/metrics
```

```text
http_request_errors_total 0
instance_id{value="a0ae"} 1
http_requests_total_post_2xx 361
http_request_duration_ms_avg 6.50415512465374
heap_mb_used 20.949951
heap_mb_size 21.4375
heap_mb_max 297.75
heap_mb_rss 59.058594
started_at{value="2026-11-03T12:28:55.214400396Z"} 1
requests_total 0
requests_active 1
# EOF
```

The `http_requests_total_<method>_<class>`, `http_request_errors_total` and
`http_request_duration_ms_avg` counters come from `RequestMetrics`. They carry no labels, so the
alert rules match on the metric name. `edelivery_parties_online_total` counts the ONLINE gates and
platforms edelivery took from the registry (here 3: G1, G2, `mock-edelivery`).

## ResQL

`GET /health` is built into ReSql. It needs no token and does not touch the database.

```bash
curl -s -i http://127.0.0.1:27090/health
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 7bf8ff33a4b746f28853b1120275db85
traceparent: 00-7bf8ff33a4b746f28853b1120275db85-83042aab32cb446f-01
content-length: 126

{"appName":"resql","version":"v0.5.1","packagingTime":0,"appStartTime":1793708949295,"serverTime":1793721550853,"status":"UP"}
```

To check the database through ReSql, call `get_db_status` with the ReSql token (see
[ReSql endpoints](../internal/resql.md#examples)), or use `baasikontoroll` below.

## Registry

`GET /health.json` is a static file written by the `registry-build` run (`scripts/registry-to-json.py`) into the `registry-data` volume.
It proves nginx serves the documents; the counts show what was published.

```bash
curl -s -i http://127.0.0.1:27080/health.json
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 174
Cache-Control: no-store

{
  "status": "ok",
  "gates": 2,
  "platforms": 2,
  "authorities": 10,
  "trustViewGeneration": "sha256:234763487465da2967dbd0518722fe11d969b1142052cd9edd2c42eb77087b84"
}
```

More on the registry documents: [Registry documents](../internal/registry.md).

## GET /efti/api/v1/test/baasikontoroll

Database diagnostic. Returns the ReSql `get_db_status` row: PostgreSQL version, start time, uptime.

`GET /efti/api/v1/test/baasikontoroll` · on ruuter (G1 :27086) · no auth · source `DSL/Ruuter/efti/GET/api/v1/test/baasikontoroll.yml`

The parent guard `efti/GET/api/v1/.guard.yml` asks for `X-Internal-Service-Token`. The
`efti/GET/api/v1/test/.guard.yml` overrides it (`override_ancestors: true`), so all three `test/`
routes are public.

### Request

No headers, parameters or body. A token or cookie is ignored.

### Responses

| Status | When | Body |
|---|---|---|
| 200 | ReSql answered below 300 | array with one object: `pgVersion`, `startedAt`, `uptime` |
| 503 | ReSql unreachable or answered ≥ 300 | `{"error": "Database unavailable"}` |

### Example

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/test/baasikontoroll
```

```http
HTTP/1.1 200 OK
content-type: application/json
traceparent: 00-d1634520a2aec8cd0ce6cf888e2bab81-c67c6a46cde4b3cc-01
x-trace-id: d1634520a2aec8cd0ce6cf888e2bab81
content-length: 201

[{"pgVersion":"PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit","startedAt":"2026-11-03T12:28:54.736200Z","uptime":"03:30:37.234398"}]
```

## GET /efti/api/v1/test/lubatud

Public session probe ("lubatud" = allowed). With a valid session cookie it returns what TIM knows
about the token; without one it answers anonymously with `200`.

`GET /efti/api/v1/test/lubatud` · on ruuter (G1 :27086) · no auth, optional cookie · source `DSL/Ruuter/efti/GET/api/v1/test/lubatud.yml`

### Request

| Header | Required | Value |
|---|---|---|
| `Cookie` | no | `eftiJwtCookie=<JWT>`. The route forwards the whole `Cookie` header to TIM `GET /jwt/userinfo`; TIM reads the `eftiJwtCookie` cookie. |

An `Authorization: Bearer` header is **not** read.

Get a JWT with the dev login (dev only, `DEV_LOGIN_ENABLED=true`):

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:27086/auth/dev-login \
  -H 'Content-Type: application/json' \
  -d '{"personalCode":"60001019906","firstName":"Super","lastName":"Admin"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
```

### Responses

| Status | When | Body |
|---|---|---|
| 200 | cookie present and TIM answered 2xx | TIM's `userinfo` object |
| 200 | no `Cookie` header, or TIM rejected it | `{"authenticated": false, "message": "Sessioon puudub"}` |

### Examples

#### Without a cookie

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/test/lubatud
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: e4a4d30e3eaae50c2cfaf080144c920f
content-length: 51

{"authenticated":false,"message":"Sessioon puudub"}
```

#### With the JWT as a cookie

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/test/lubatud -H "Cookie: eftiJwtCookie=$TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: a5580761d243621564aa66cc534f5a66
content-length: 306

{"userinfo":{"audience":["tim-service"],"claims":{"firstName":"Super","lastName":"Admin","personalCode":"60001019906","token_type":"custom_jwt"},"expires_at":"2026-11-03T16:59:31Z","issued_at":"2026-11-03T15:59:31Z","issuer":"TIM","jwt_id":"9034620b-51dc-46b0-91b7-495e6ecf5142","subject":"EE60001019906"}}
```

#### With the JWT as a Bearer header (not read)

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/test/lubatud -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 28d51f0d4d801426808c61da89568f81
content-length: 51

{"authenticated":false,"message":"Sessioon puudub"}
```

## GET /efti/api/v1/test/piiratud

Restricted session probe ("piiratud" = restricted). Same as `lubatud`, but answers `401` without a
valid cookie.

`GET /efti/api/v1/test/piiratud` · on ruuter (G1 :27086) · `eftiJwtCookie` cookie · source `DSL/Ruuter/efti/GET/api/v1/test/piiratud.yml`

### Request

| Header | Required | Value |
|---|---|---|
| `Cookie` | yes | `eftiJwtCookie=<JWT>` |

### Responses

| Status | When | Body |
|---|---|---|
| 200 | TIM answered 2xx | TIM's `userinfo` object |
| 401 | no `Cookie` header, or TIM rejected the token | `{"status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}` |

### Examples

#### Without a cookie

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/test/piiratud
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/json
x-trace-id: a25cbff81d00b85b97c6743192d6234f
content-length: 86

{"status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

#### With the JWT as a cookie

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/test/piiratud -H "Cookie: eftiJwtCookie=$TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 3746765aa8e3e2c4fb019c5631ef6844
content-length: 306

{"userinfo":{"audience":["tim-service"],"claims":{"firstName":"Super","lastName":"Admin","personalCode":"60001019906","token_type":"custom_jwt"},"expires_at":"2026-11-03T16:59:31Z","issued_at":"2026-11-03T15:59:31Z","issuer":"TIM","jwt_id":"9034620b-51dc-46b0-91b7-495e6ecf5142","subject":"EE60001019906"}}
```

#### With an invalid cookie or a Bearer header

```bash
curl -s -i http://127.0.0.1:27086/efti/api/v1/test/piiratud -H "Cookie: eftiJwtCookie=garbage"
curl -s -i http://127.0.0.1:27086/efti/api/v1/test/piiratud -H "Authorization: Bearer $TOKEN"
```

Both answer:

```http
HTTP/1.1 401 Unauthorized
content-type: application/json
content-length: 86

{"status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

## Notes

- The session probes check only TIM. They do not check the gate's `users` table or
  `token_revoked_at`: a token the admin has revoked still gets `200` here. The admin surface uses
  [check-admin-authority](../internal/auth-helpers.md), which does check both.
- The `piiratud` 401 body is a problem object, but its `content-type` is `application/json`, not
  `application/problem+json`, and it has no `code` or `detail`.
- `baasikontoroll` is public and shows the exact PostgreSQL build. Keep it behind the internal
  network in a deployment.
- `docs/specs/api_endpoints.md` §3 says the live/ready probes answer `text/plain` and a 503 is
  RFC 7807. The DSL answers a JSON string (`"OK"`, `"Database unavailable"`); `live` has no 503.
- Postman: folder **0. Health** ("ruuter health/ready", "ruuter-xroad health/ready",
  "edelivery MSH up") and **7. Operations / Probes** (every other probe).
