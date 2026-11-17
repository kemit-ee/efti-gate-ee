# Permissions matrix

Who may call which route of the gate. Derived from the guard files (`DSL/**/.guard.yml`), the
route files themselves where they check a credential, and the Kotlin services (`edelivery`,
`xml-mapper`). Where `docs/specs/permissions-matrix.md` says otherwise, the code is described here
and the difference is listed under [Differences from the spec](#differences-from-the-spec).

## The callers

| Caller | Credential | Checked by | Resolves to |
|---|---|---|---|
| **Anonymous** | none | – | – |
| **Platform** | `X-Api-Key: <key>` | `DSL/Ruuter/platforms/.guard.yml` | the one `ONLINE` platform in the registry whose `apiKey` equals the key |
| **X-Road authority** | `X-Road-Client: <instance>/<class>/<memberCode>[/<subsystem>]` + `X-Road-Id: <uuid>`, set by the Security Server | `DSL/Ruuter-xroad/xroad/.guard.yml` | the one authority whose `registryCode` equals `memberCode`; its `subsets` are its rights |
| **Admin** | `Authorization: Bearer <gate JWT>` | `DSL/Ruuter/admin/.guard.yml`, `auth/GET/.guard.yml` → `efti/internal/check-*-authority` | the active `users` row for the token's `personalCode` (no roles) |
| **Gate-internal** | `X-Internal-Service-Token: <INTERNAL_SERVICE_TOKEN>` | `efti/{GET,POST}/api/v1/**/.guard.yml`, `InternalServiceTokenAuth` in edelivery and xml-mapper | – (the calling component authorised its own caller) |
| **Ops** | `Authorization: Bearer <ARCHIVE_OPS_TOKEN>` | `DSL/Ruuter/ops/.guard.yml` | – (CronManager) |
| **Peer gate** | AS4 message signed with a certificate from the gate registry | edelivery (`EDeliveryRoutes.kt`) | the gate in `registry/gates/*.yml` |
| **Data layer** | `Authorization: Bearer <RESQL_TOKEN>` | ReSql | – (only Ruuter and edelivery hold it) |

Local dev values (public in the repository): internal token `dev-internal-service-token-change-me`,
ops token `dev-archive-ops-token-change-me`, platform key `mock-secret-key`, ReSql token
`dev-resql-internal-token-change-me`, X-Road clients `DEV/GOV/70000099/testSubsystem` and
`DEV/GOV/70000093/ants`, admin via dev-login for `60001019906`.

## The matrix

✅ allowed · ❌ refused (status in brackets) · – not applicable.
"Ports" are the local G1 host ports; G2 is +1000.

### ruuter (core, :27086)

| Route | Anon | Platform | X-Road | Admin JWT | Internal | Ops | Peer gate |
|---|---|---|---|---|---|---|---|
| `GET /efti/health/live`, `/efti/health/ready` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GET /efti/api/v1/test/{baasikontoroll,lubatud,piiratud}` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GET /efti/api/v1/status`, `GET /efti/api/v1/follow-up` | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 | ✅ | ❌ 401 | ❌ 401 |
| `GET /efti/api/v1/authority/dataset/{gate}/{platform}/{id}` | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 | ✅ | ❌ 401 | ❌ 401 |
| `POST /efti/api/v1/authority/{search,dataset,follow-up}` | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 | ✅ | ❌ 401 | ❌ 401 |
| `POST /efti/api/v1/{consignments/search-xml,dataset-local,dataset-xml,follow-up-local,follow-up-xml,ping}` | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 | ✅ | ❌ 401 | ❌ 401 |
| `POST /efti/internal/check-{admin,user}-authority` | network only ¹ | | | | | | |
| `POST /platforms/v1/consignments[/{datasetId}]` | ❌ 401 | ✅ | ❌ 401 | ❌ 401 | ✅ with `X-Platform-Id` ² | ❌ 401 | ❌ 401 |
| `POST /platforms/v1/consignments-xml` | ❌ 401 | ✅ | ❌ 401 | ❌ 401 | ✅ with `X-Platform-Id` | ❌ 401 | ❌ 401 |
| `GET/POST /mock-platform/v1/**` (dev images) | ❌ 401 | ✅ only `mock-secret-key` ³ | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 |
| `POST /mock-platform/health` (dev images) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `POST /services/msh` | ⚠ ⁴ | | | | | | ✅ |
| `POST /auth/callback`, `/auth/dev-login`, `/auth/logout` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GET /auth/user` | ❌ 401 | ❌ 401 | ❌ 401 | ✅ own row | ❌ 401 | ❌ 401 | ❌ 401 |
| `GET/POST/PUT/DELETE /admin/v1/**` (all 20 operations) | ❌ 401 | ❌ 401 | ❌ 401 | ✅ | ❌ 401 | ❌ 401 | ❌ 401 |
| `POST /ops/v1/*` (7 jobs) | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 | ✅ | ❌ 401 |

### ruuter-xroad (:27087)

| Route | Anon | Platform | X-Road | Admin JWT | Internal | Ops | Peer gate |
|---|---|---|---|---|---|---|---|
| `GET /xroad/health/ready` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GET /xroad/provide/openapi` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GET /xroad/provide/v1/subsets` | ❌ 401 | ❌ 401 | ✅ | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 |
| `POST /xroad/provide/v1/{echo,search,follow-up}` | ❌ 401 | ❌ 401 | ✅ | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 |
| `POST /xroad/provide/v1/dataset` | ❌ 401 | ❌ 401 | ✅ requested subsets ⊆ own, else 403 | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 |
| `POST /xroad/provide/v1/transport-means` | ❌ 401 | ❌ 401 | ✅ needs `EU02`, else 403 | ❌ 401 | ❌ 401 | ❌ 401 | ❌ 401 |

X-Road guard refusals: no or malformed `X-Road-Client` → 401 `UNAUTHORIZED`; no `X-Road-Id` → 400
`MISSING_REQUIRED_HEADER`; `X-Road-Id` not a UUID → 400 `INVALID_REQUEST_ID`; member code matches no
authority, or more than one → 403 `FORBIDDEN`. `X-Road-UserId` is never a credential.

### ruuter-xroad-mock (:27088, public developer mock)

| Route | Anon | With well-formed X-Road headers |
|---|---|---|
| `GET /developer/health/ready` | ✅ | ✅ |
| `GET /developer/v1/subsets`, `POST /developer/v1/{echo,search,transport-means,dataset,follow-up}` | ❌ 401 | ✅ any member code; `00000000` → 403; `70000000` → no subsets |

No registry lookup; see [Developer mock](../api/authorities/developer-mock.md).

### edelivery (:27081)

| Route | Anon | Internal | Peer gate |
|---|---|---|---|
| `GET /health` | ✅ | ✅ | ✅ |
| `POST /api/v1/send/{partyId}` | ❌ 401 | ✅ | ❌ 401 |
| `GET /services/msh` (info text) | ✅ | ✅ | ✅ |
| `POST /services/msh` | ⚠ ⁴ | ⚠ | ✅ signed AS4 from a registered gate or platform |

### xml-mapper (:27082), ReSql (:27090), registry (:27080), Turvis

| Service / route | Who | Credential |
|---|---|---|
| xml-mapper `GET /health` | anyone | none |
| xml-mapper `/api/v1/**` | Ruuter, edelivery | `X-Internal-Service-Token` (else 401) |
| ReSql `POST /efti/<query>`, `/archive/<query>` | Ruuter, edelivery | `Authorization: Bearer <RESQL_TOKEN>` (else 401) |
| registry `/gates.json`, `/platforms.json`, `/authorities.json`, `/<type>/<id>.json` | any container on the network | none – network isolation only. Serves platform `apiKey`s in plain text. |
| Turvis `/turvis/health/ready` | anyone | none |
| Turvis `/turvis/v1/**` | edelivery | `TURVIS_INTERNAL_TOKEN` in `X-Internal-Service-Token` or mTLS SAN, per `AUTH_MODE` (else 401). Not part of the local two-gate stack. |

### Footnotes

1. `efti/POST/internal/.guard.yml` checks nothing. The routes are meant to be reached only by
   ruuter's own guards over loopback. The UI nginx answers 404 for `/efti/internal/`. In the local
   stack ruuter's port is published, so they answer on `127.0.0.1:27086` – for any caller. They
   only report whether a token is valid; they grant nothing.
2. The platforms guard accepts the internal token plus `X-Platform-Id` on **every** `/platforms/**`
   route, not only on `consignments-xml` as its description says. The platform is then whatever
   `X-Platform-Id` names; it is not checked against the registry.
3. The mock platform has no guard file. Each route compares `X-Api-Key` with
   `[#MOCK_PLATFORM_API_KEY]` from the mounted `constants.ini` (dev value `mock-secret-key`; empty denies).
4. Ruuter's `/services/msh` checks only the media type (`multipart/related` with
   `type="application/soap+xml"` and a boundary; else 415 or 400) and passes the bytes to
   edelivery. The AS4 signature inside the message is the credential; edelivery rejects anything a
   registered party did not sign. Anyone can reach the endpoint, nobody unregistered gets through.

## Examples

Each credential is refused where it does not belong. All run against G1.

Admin JWT on the gate-internal authority API:

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/api/v1/authority/search \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"identifier":"TST-001"}'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 176

{"code":"UNAUTHORIZED","detail":"This endpoint requires a valid X-Internal-Service-Token.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

Internal token on the admin API:

```bash
curl -s -i http://127.0.0.1:27086/admin/v1/users \
  -H 'X-Internal-Service-Token: dev-internal-service-token-change-me'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 173

{"detail":"Token is missing, invalid, expired, or the user has no active gate account.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

`X-Api-Key: mock-secret-key` on the same route also answers 401.

Admin JWT on an ops job:

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/expire-identifiers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"dryRun":true}'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 173

{"code":"UNAUTHORIZED","detail":"This endpoint requires a valid CronManager ops token.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

Admin JWT on a platform upload:

```bash
curl -s -i -X POST http://127.0.0.1:27086/platforms/v1/consignments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/xml' -d '<x/>'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 159

{"detail":"X-Api-Key is missing or does not match a registered platform.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

edelivery's send API without the internal token:

```bash
curl -s -i -X POST http://127.0.0.1:27081/api/v1/send/G2 -d '<x/>'
```

```http
HTTP/1.1 401 Unauthorized
Transfer-encoding: chunked
Content-type: application/json; charset=UTF-8

{"detail":"Missing or invalid X-Internal-Service-Token","instance":"/api/v1/send/G2","message":"Missing or invalid X-Internal-Service-Token","status":401,"title":"Unauthorized"}
```

Status codes checked the same way (`curl -o /dev/null -w '%{http_code}'`): `GET /efti/api/v1/test/lubatud`
200, `GET /efti/api/v1/status` without token 401, `GET /xroad/provide/v1/subsets` without headers
401, with `DEV/GOV/70000099/testSubsystem` 200, with unknown member `12345678` 403,
`GET /xroad/provide/openapi` 200, `POST /services/msh` on ruuter with `application/json` 415,
`GET /mock-platform/v1/dataset/…` without key 401, `POST /mock-platform/health` 200, xml-mapper
`/api/v1/` without token 401, ReSql `/efti/get_users` without token 401, registry `/platforms.json`
200.

## Every refusal is audited

Each guard writes an audit row when it refuses: `admin.auth_denied`, `auth.auth_denied`,
`efti-internal.auth_denied`, `platform.auth_denied`, `xroad.auth_denied`, `ops.auth_denied`. See
[Audit log](../api/admin/audit.md#what-is-audited).

## Differences from the spec

`docs/specs/permissions-matrix.md` (v1.7) differs from the code in these points:

| Spec | Code |
|---|---|
| Platform API authenticated by mTLS client certificate (`X-Client-Cert-Subject`), errors `FORBIDDEN_NO_PLATFORM` / `FORBIDDEN_MULTI_PLATFORM` | `X-Api-Key` matched against the registry's `apiKey` (ADR-004/ADR-011). Unknown key → 401; a key on several platforms → 403. |
| Platform routes `/v1/identifiers/{id}`, `/v1/status/…`, `/v1/dataset/…` | `/platforms/v1/consignments[/{id}]`, `/platforms/v1/consignments-xml` |
| Admin API under `/api/v1/…` with registry `POST`/`PUT`/`DELETE`, `…/ping`, break-glass `/api/v1/auth/local-token` | `/admin/v1/…` and `/auth/…`; registries are read-only (405), no ping, no break-glass |
| Wrong ops token → 403 `FORBIDDEN` | 401 `UNAUTHORIZED` |
| Ops routes `/api/v1/admin/archive`, `…/ping-gates` | `/ops/v1/{archive-consignments,expire-identifiers,purge-archive,purge-async-responses,purge-inbound-messages,purge-search-results,refresh-read-models}` |
| Logout appends a `sessions` row and answers 204 | TIM blacklist only, answers 200 |
| `/v1/identifiers/{identifier}` etc. as the authority API paths | `/efti/api/v1/authority/{search,dataset,follow-up}` (internal), `/xroad/provide/v1/*` (X-Road) |
| Every authority data access audited as `identifier.search` / `dataset.deliver` / `followup.send` | not written by any route |

## Notes

- The UI nginx (`docker/ui/nginx.conf`) also proxies `/efti/api/v1/authority/` to ruuter. That
  surface is meant to be gate-internal (`permissions-matrix.md` §3.2: "never directly by a human
  browser session"). It is still protected by the internal token, but the nginx makes it reachable
  from wherever the UI is reachable.
- Health and diagnostic routes are public by design (Kubernetes probes, the X-Road Security
  Server's own checks).
- An admin JWT is the same token for every admin; there is no read-only admin.
