# Error catalogue

Every error code the gate can answer with: its HTTP status, title, problem `type`, what it
means, whether a retry can help, and which endpoints actually send it. The tables are generated
from `docs/specs/errors.json` (version 2.0.0, 39 codes) and checked against the DSL with
`grep -rho 'code: "[A-Z_]*"' DSL/`. Codes the DSL sends that the catalogue lacks, and the other
way round, are under [Gaps](#gaps).

The tokens and X-Road clients in the examples are development values, public in the repo
([Ports and credentials](../reference/ports-and-credentials.md)).

## How to read an error

A problem body has `type`, `code`, `title`, `status` and `detail`
([Error bodies](../api/conventions.md#error-bodies)). Branch on `code`. If there is no `code`, the
answer came from a layer that does not use the catalogue (the Ruuter engine, a core handler,
xml-mapper, edelivery); the shapes are listed in [Conventions](../api/conventions.md#other-shapes).

- `type` is `https://api.efti.ee/errors/<slug>`. The tables show `…/<slug>`.
- "Retry" is the `retryable` hint from `errors.json`. The gate never sends it on the wire.
- "Emitted by" lists the routes whose DSL returns that `code`. "–" means no route in this
  repository sends the code; the parenthesis says what you get instead.
- `errors.json` also gives a log level per code (`INFO` for `TOKEN_EXPIRED`,
  `CONSIGNMENT_NOT_FOUND`, `NOT_IMPLEMENTED`; `ERROR` for `ARCHIVE_STORAGE_UNAVAILABLE`,
  `DATABASE_ERROR`, `INTERNAL_ERROR`, `TRANSFORMATION_ERROR`; `WARN` for the rest).

## Codes from errors.json

### 400 Bad Request

| Code | Status | Title | `type` | Retry | Meaning | Emitted by |
|---|---|---|---|---|---|---|
| `BAD_REQUEST_GENERAL` | 400 | Bad Request | `…/bad-request` | no | The request contains invalid parameters or malformed data that cannot be parsed. | X-Road `dataset`, `search`, `transport-means`, `follow-up`, `echo`; core `POST /efti/api/v1/authority/{dataset,search,follow-up}`, `GET /efti/api/v1/authority/dataset/…`, `follow-up-local`; admin `POST`/`PUT /admin/v1/users`, `POST /admin/v1/js-error`, `GET /admin/v1/consignment-{counts,summary,history}`; `/services/msh` guard; developer mock `transport-means` |
| `FOLLOW_UP_GATE_MISMATCH` | 400 | Follow-Up Gate Mismatch | `…/follow-up-gate-mismatch` | no | The gateId in the follow-up request does not match this gate's own ID. Follow-up messages can only be sent to the gate that owns the referenced dataset. | – |
| `INVALID_DATASET_ID` | 400 | Invalid Dataset ID | `…/invalid-dataset-id` | no | The datasetId path parameter is not a valid UUID v4. | – (platform upload answers `{"error":"Invalid consignment XML","status":400}`) |
| `INVALID_REQUEST_ID` | 400 | Invalid Request ID | `…/invalid-request-id` | no | The X-Request-ID header value is present but does not conform to UUID v4 format. | X-Road guard (`/xroad/**`); developer mock guard (`/developer/**`) |
| `INVALID_SUBSET` | 400 | Invalid Subset | `…/invalid-subset` | no | One or more requested subset codes are not one of EU01..EU07. The gate does not emit this code: the authority dataset routes answer BAD_REQUEST_GENERAL (see the example). | – (core answers `BAD_REQUEST_GENERAL`) |
| `INVALID_XML` | 400 | Invalid XML | `…/invalid-xml` | no | The submitted XML document is malformed (unclosed tags, invalid characters, encoding errors) or does not conform to the eFTI consignment identifier XSD schema. | – (platform upload answers `{"error":"Invalid consignment XML","status":400}`) |
| `MISSING_REQUIRED_HEADER` | 400 | Missing Required Header | `…/missing-required-header` | no | A required HTTP header is absent from the request. | X-Road guard (`/xroad/**`) (`X-Road-Id`); developer mock guard |
| `MISSING_SUBSET` | 400 | Missing Subset Parameter | `…/missing-subset` | no | At least one subset is required for dataset retrieval but none was requested. Returned by the X-Road dataset route (`subsets` body field); the internal authority dataset route answers BAD_REQUEST_GENERAL instead. | X-Road `dataset`; developer mock `dataset` |

### 401 Unauthorized

| Code | Status | Title | `type` | Retry | Meaning | Emitted by |
|---|---|---|---|---|---|---|
| `TOKEN_EXPIRED` | 401 | Token Expired | `…/token-expired` | no | The bearer token was valid but has passed its expiry time. The client must re-authenticate. | – (admin guard answers 401 `type …/unauthorized` without `code`) |
| `TOKEN_INVALID` | 401 | Invalid Token | `…/token-invalid` | no | The supplied Bearer token cannot be decoded, is structurally incorrect, or the credentials do not match any known user. | – (same) |
| `UNAUTHORIZED` | 401 | Authentication Required | `…/unauthorized` | no | The request does not contain any authentication credentials or the Authorization header is absent. | X-Road guard (`/xroad/**`) (`X-Road-Client`); developer mock guard; core `/efti/api/v1/**` service-token guards; ops guard (`/ops/v1/**`). The platform guard and the admin guard send the same `type` without `code`. |

### 403 Forbidden

| Code | Status | Title | `type` | Retry | Meaning | Emitted by |
|---|---|---|---|---|---|---|
| `FORBIDDEN` | 403 | Forbidden | `…/forbidden` | no | The authenticated caller is identified but not permitted on this surface — the static opsToken does not match, or the authenticated user is calling an endpoint reserved for a different identity type. | X-Road guard (`/xroad/**`) (unknown or ambiguous client); X-Road `subsets`; X-Road `dataset`, `transport-means` (entitlement not evaluable); developer mock guard |
| `FORBIDDEN_MULTI_PLATFORM` | 403 | Certificate matches multiple active platforms | `…/forbidden-multi-platform` | no | The presented client certificate's subject DN + serial resolve to more than one active row in the `platforms` registry. This is a configuration error — exactly one active platform row per certificate. The platform cannot authenticate until the operator soft-deletes the obsolete row(s). | platform guard sends `type …/forbidden-multi-platform` without `code`, for an `X-Api-Key` shared by two platforms (the registry build rejects that, so not reachable) |
| `FORBIDDEN_NO_PLATFORM` | 403 | No matching platform | `…/forbidden-no-platform` | no | The presented client certificate's subject DN + serial resolve to zero active rows in the `platforms` registry. Either the platform is not registered, or its latest row has status='DELETED'. | – (no mTLS platform path) |
| `FORBIDDEN_SUBSET` | 403 | Subset Access Denied | `…/forbidden-subset` | no | The X-Road authority requested a dataset or transport-means subset that its registry entry does not permit. Emitted only by the X-Road routes; the answer lists the denied and the permitted subsets as arrays. | X-Road `dataset`, `transport-means`; developer mock `dataset`, `transport-means` |
| `FORBIDDEN_WRITE_ACCESS` | 403 | Write Access Denied | `…/forbidden-write-access` | no | The admin caller does not have write access to the target entity. | – |

### 404 Not Found

| Code | Status | Title | `type` | Retry | Meaning | Emitted by |
|---|---|---|---|---|---|---|
| `AUTHORITY_NOT_FOUND` | 404 | Authority Not Found | `…/authority-not-found` | no | The specified authorityId does not exist in the authorities table. | – (admin `GET /admin/v1/authorities/{id}` answers `{"error":"Not Found"}`) |
| `CONSIGNMENT_NOT_FOUND` | 404 | Consignment Not Found | `…/consignment-not-found` | no | No consignment record found for the given datasetId in the local consignments table. | – (admin `DELETE /admin/v1/consignments/{id}` answers `{"error":"Not Found"}`) |
| `GATE_NOT_FOUND` | 404 | Gate Not Found | `…/gate-not-found` | no | The specified gateId does not exist in the gate registry. | – (admin `GET /admin/v1/gates/{id}` answers `{"error":"Not Found"}`; an unknown `uil.gateId` is a `502 GATEWAY_UNAVAILABLE`) |
| `NOT_FOUND` | 404 | Not Found | `…/not-found` | no | The requested resource does not exist. | – (the engine answers `{"error":"Not Found"}`) |
| `PLATFORM_NOT_FOUND` | 404 | Platform Not Found | `…/platform-not-found` | no | The specified platformId does not exist in the platforms table. | – (core answers `{"error":"Platform Not Found"}` or `{"error":"Platform lookup failed (data layer unavailable)"}`, wrapped by X-Road in `502`) |
| `USER_NOT_FOUND` | 404 | User Not Found | `…/user-not-found` | no | The specified userId does not exist in the users table. | – (admin user routes answer `{"error":"Not Found"}`) |

### 409 Conflict

| Code | Status | Title | `type` | Retry | Meaning | Emitted by |
|---|---|---|---|---|---|---|
| `ARCHIVE_IN_PROGRESS` | 409 | Archive run already in progress | `…/archive-in-progress` | yes | Another invocation of POST /api/v1/admin/archive started earlier is still running. The gate enforces serialised archival runs to avoid concurrent live-DB DELETEs by the db_archiver role. | – |
| `CONFLICT` | 409 | Conflict | `…/conflict` | no | The request conflicts with the current state of the resource. Typically a duplicate key violation. | – (admin `POST /admin/v1/users` answers `{"error":"Conflict","detail":…}`) |
| `DUPLICATE_REQUEST_ID` | 409 | Duplicate Request ID | `…/duplicate-request-id` | no | The X-Request-ID value has already been used for a previous request. Each request must use a unique UUID v4. | X-Road `dataset`, `follow-up` (repeated `X-Road-Id`) |
| `FOLLOW_UP_IN_PROGRESS` | 409 | Follow-up In Progress | `…/follow-up-in-progress` | yes | The same follow-up (same request id, platform, dataset request and content) is being delivered, or its delivery is not confirmed yet. Retry with the same request id; after a failed delivery or after 120 seconds the follow-up is delivered again. | core `POST /efti/api/v1/authority/follow-up`, `follow-up-xml`, `follow-up-local` |

### 415, 429

| Code | Status | Title | `type` | Retry | Meaning | Emitted by |
|---|---|---|---|---|---|---|
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Unsupported Media Type | `…/unsupported-media-type` | no | The inbound Content-Type is not on the route's media-type allow-list. Returned by the services/ AS4 edge guard when a request to POST /services/msh does not carry multipart/related. | `POST /services/msh` guard |
| `RATE_LIMIT_EXCEEDED` | 429 | Rate Limit Exceeded | `…/rate-limit-exceeded` | yes | The caller has sent too many requests within the allowed time window. Applies to gate-to-gate (100 req/min per source gate) and authority search endpoints. | – (reverse proxy, not in the stack) |

### 5xx

| Code | Status | Title | `type` | Retry | Meaning | Emitted by |
|---|---|---|---|---|---|---|
| `DATABASE_ERROR` | 500 | Database Error | `…/database-error` | yes | An unexpected error occurred when accessing the PostgreSQL database (e.g., connection lost, query timeout, constraint violation not caught as business error). | – |
| `INTERNAL_ERROR` | 500 | Internal Server Error | `…/internal-error` | no | An unexpected error occurred inside the Gate. This is a server-side fault; the client should not retry immediately. | – |
| `TRANSFORMATION_ERROR` | 500 | Data Transformation Error | `…/transformation-error` | no | An unexpected error occurred during XML marshalling/unmarshalling that is not caused by invalid client input. | – |
| `NOT_IMPLEMENTED` | 501 | Not Implemented | `…/not-implemented` | no | The request names a capability that is part of the published API contract but is not yet implemented. Distinct from 400: the value is recognised and valid, the capability is absent. | developer mock `transport-means` with `scope: allgates` |
| `ARCHIVE_STORAGE_UNAVAILABLE` | 502 | Archive storage unavailable | `…/archive-storage-unavailable` | yes | The configured archival destination (S3-compatible object store, secondary Postgres, or append-only file store) is unreachable or rejected the write. The current batch is rolled back; no rows have been deleted from the live database. | – |
| `EDELIVERY_ERROR` | 502 | eDelivery Network Error | `…/edelivery-error` | yes | Communication with the eDelivery AS4 access point failed (SOAP fault, TLS error, network unreachable). | – |
| `GATEWAY_UNAVAILABLE` | 502 | Bad Gateway | `…/bad-gateway` | yes | The Gate attempted to contact a remote gate or platform and received an invalid or no response. | X-Road `dataset`, `search`, `transport-means`, `follow-up`, `subsets` (registry lookup failed); core `POST /efti/api/v1/authority/search` |
| `PLATFORM_UNAVAILABLE` | 502 | Platform Unavailable | `…/platform-unavailable` | yes | The Gate attempted to forward a dataset or follow-up request to the platform and received no valid response (connection refused, timeout, or HTTP error from platform). | – (core answers `{"error":"The platform is unreachable","transportError":…}`) |
| `SERVICE_UNAVAILABLE` | 503 | Service Unavailable | `…/service-unavailable` | yes | The Gate itself is temporarily unavailable (starting up, overloaded, or in maintenance mode). Clients should retry with exponential backoff. | admin guard, `auth` guard and `check-*-authority` send `type …/service-unavailable` without `code` when TIM is unreachable |
| `GATE_TIMEOUT` | 504 | Gate Timeout | `…/gate-timeout` | no | A remote gate did not respond to a gate-to-gate identifier search broadcast within the configured timeout (default: 8 seconds per gate). | – (a silent peer shows up as `coreStatus` 504 inside `502 GATEWAY_UNAVAILABLE`) |
| `PLATFORM_TIMEOUT` | 504 | Platform Timeout | `…/platform-timeout` | yes | The platform did not respond to a dataset or follow-up request within the configured timeout (default: 30 seconds). | – |

## Codes by API

What a caller of each surface can actually receive.

| Surface | Codes with a `code` field | Other answers |
|---|---|---|
| X-Road `/xroad/provide/**` | `UNAUTHORIZED`, `MISSING_REQUIRED_HEADER`, `INVALID_REQUEST_ID`, `FORBIDDEN`, `FORBIDDEN_SUBSET`, `MISSING_SUBSET`, `BAD_REQUEST_GENERAL`, `DUPLICATE_REQUEST_ID`, `GATEWAY_UNAVAILABLE` | engine `400`/`404`/`405` `{"error": …}` |
| Developer mock `/developer/**` | the X-Road guard codes, `MISSING_SUBSET`, `FORBIDDEN_SUBSET`, `BAD_REQUEST_GENERAL`, `NOT_IMPLEMENTED` | engine errors |
| Core `/efti/api/v1/**` | `UNAUTHORIZED`, `BAD_REQUEST_GENERAL`, `FOLLOW_UP_IN_PROGRESS`, `GATEWAY_UNAVAILABLE` (search) | `{"error": …}` from the handlers, with the upstream status |
| Platforms `/platforms/v1/**` | none | `401` problem without `code`; `{"error": …, "status": …}` for 400, 403, 500, 502 |
| Admin `/admin/v1/**` | `BAD_REQUEST_GENERAL` | `401`/`503` problem without `code`; `{"error":"Not Found"}`, `{"error":"Conflict",…}`, `{"error":"Data layer unavailable"}` |
| Ops `/ops/v1/**` | `UNAUTHORIZED` and the eight job codes under [Gaps](#gaps) | – |
| AS4 edge `/services/msh` | `UNSUPPORTED_MEDIA_TYPE`, `BAD_REQUEST_GENERAL` | AS4 / SOAP faults from edelivery ([AS4 endpoint](../api/gates/as4-endpoint.md)) |
| edelivery, xml-mapper | none | `{"detail","instance","message","status","title"}` |

## Gaps

### Sent by the DSL, missing from errors.json

The ops jobs answer with their own codes. All are `502`, all use `type …/bad-gateway`
(the `GATEWAY_UNAVAILABLE` type) with their own `title`. Source: `DSL/Ruuter/ops/POST/v1/`.

| Code | Route | Title | Detail |
|---|---|---|---|
| `IDENTIFIER_EXPIRY_FAILED` | `expire-identifiers` | Cabotage-expiry sweep failed | The consignments expiry step returned an error. See jobs_execution_log. |
| `ARCHIVE_SWEEP_FAILED` | `archive-consignments` | Archive sweep failed | A ReSql step in the archive sweep returned an error. See jobs_execution_log. |
| `ARCHIVE_VERIFICATION_FAILED` | `archive-consignments` | Archive verification failed | The archive DB did not confirm every copied row; nothing was deleted from the live DB. |
| `ARCHIVE_PURGE_FAILED` | `purge-archive` | Archive purge failed | The archive-DB purge step returned an error. See jobs_execution_log. |
| `ASYNC_RESPONSES_PURGE_FAILED` | `purge-async-responses` | AS4 hand-off purge failed | The async_responses purge step returned an error. See jobs_execution_log. |
| `INBOUND_MESSAGES_PURGE_FAILED` | `purge-inbound-messages` | Inbound message purge failed | The inbound_messages purge step returned an error. See jobs_execution_log. |
| `SEARCH_PURGE_FAILED` | `purge-search-results` | Search-state purge failed | The search_results purge step returned an error. See jobs_execution_log. |
| `READ_MODEL_REFRESH_FAILED` | `refresh-read-models` | Read-model refresh failed | A ReSql step in the read-model refresh returned an error. See jobs_execution_log. |

Not run locally: each needs a failing ReSql or archive database.

The grep also finds 23 values in the Turvis mock (`DSL/Ruuter-turvis-mock/turvis/POST/v1/`). They
are `reason_code` members, not `code`, and belong to the Turvis signing/encryption API, which
edelivery calls internally ([Turvis](../api/internal/turvis.md)):

| HTTP | `reason_code` |
|---|---|
| 413 | `PAYLOAD_TOO_LARGE` |
| 422 | `AES_UNWRAP_FAIL`, `ALGO_UNSUPPORTED`, `KEY_HANDLE_UNKNOWN`, `PAYLOAD_DECRYPT_FAIL`, `RECEIVER_CERT_EXPIRED`, `RECEIVER_CERT_UNTRUSTED`, `RECEIVER_UNKNOWN`, `SIGNER_IDENTITY_MISMATCH`, `SIGNER_UNTRUSTED`, `SIG_ALGO_UNSUPPORTED`, `SIG_BUILD_FAIL`, `SIG_CRYPTO_FAIL`, `SIG_MISSING`, `SIG_POLICY_FAIL`, `SKI_MISMATCH`, `UNKNOWN_RETURN_FIELD`, `WRAPPED_KEY_MALFORMED`, `XML_ATTACK_DETECTED`, `XML_PARSE_FAIL` |
| 500 | `TURVIS_INTERNAL` |
| 503 | `KEY_UNAVAILABLE`, `TRUST_STORE_UNAVAILABLE` |

### In errors.json, sent by no route

27 of the 39 codes: `FOLLOW_UP_GATE_MISMATCH`, `INVALID_DATASET_ID`, `INVALID_SUBSET`,
`INVALID_XML`, `TOKEN_EXPIRED`, `TOKEN_INVALID`, `FORBIDDEN_MULTI_PLATFORM`,
`FORBIDDEN_NO_PLATFORM`, `FORBIDDEN_WRITE_ACCESS`, `AUTHORITY_NOT_FOUND`,
`CONSIGNMENT_NOT_FOUND`, `GATE_NOT_FOUND`, `NOT_FOUND`, `PLATFORM_NOT_FOUND`, `USER_NOT_FOUND`,
`ARCHIVE_IN_PROGRESS`, `CONFLICT`, `RATE_LIMIT_EXCEEDED`, `DATABASE_ERROR`, `INTERNAL_ERROR`,
`TRANSFORMATION_ERROR`, `ARCHIVE_STORAGE_UNAVAILABLE`, `EDELIVERY_ERROR`,
`PLATFORM_UNAVAILABLE`, `SERVICE_UNAVAILABLE`, `GATE_TIMEOUT`, `PLATFORM_TIMEOUT`.

Of these, `FORBIDDEN_MULTI_PLATFORM` and `SERVICE_UNAVAILABLE` are sent as a `type` without a
`code`. The examples in `errors.json` for most of them use paths of the earlier design
(`GET /identifiers/{id}`, `GET /dataset/{gate}/{platform}/{id}`, `POST /api/v1/admin/archive`)
that this gate does not have.

### Other differences between errors.json and the stack

| errors.json | Stack |
|---|---|
| "All error responses include Content-Type: application/problem+json" | only guard answers and `DUPLICATE_REQUEST_ID` do; route-level problems are `application/json` |
| "The 'instance' field always reflects the exact request path" | ruuter never sets `instance` |
| `UNAUTHORIZED` title "Authentication Required" | the X-Road and developer guards use it; core and ops guards say "Unauthorized" |
| `FORBIDDEN` for a wrong ops token | `/ops/v1/**` answers `401 UNAUTHORIZED` for a missing, wrong or JWT token |
| `PLATFORM_TIMEOUT` default 30 s, `GATE_TIMEOUT` default 8 s | no such timeouts; see [Timeouts](../api/conventions.md#timeouts) |
| – | ADR-006 proposes `FORBIDDEN_MULTI_AUTHORITY` for an ambiguous `registryCode`; the guard sends `FORBIDDEN` with its own `detail` |

## Real error responses

Captured on the local stack.

### 400 MISSING_REQUIRED_HEADER

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/problem+json
traceparent: 00-ae859bb935397d4b2ed7a13140561b8d-a2fcfaebb9d081f9-01
x-trace-id: ae859bb935397d4b2ed7a13140561b8d

{"code":"MISSING_REQUIRED_HEADER","detail":"X-Road-Id header is required on every X-Road request.","status":400,"title":"Missing Required Header","type":"https://api.efti.ee/errors/missing-required-header"}
```

### 400 INVALID_REQUEST_ID

Same request with `-H 'X-Road-Id: msg-123'`:

```http
HTTP/1.1 400 Bad Request
content-type: application/problem+json
traceparent: 00-7278dc93f849895327ae1acd9435e794-6be090f144aac025-01
x-trace-id: 7278dc93f849895327ae1acd9435e794

{"code":"INVALID_REQUEST_ID","detail":"X-Road-Id must be a UUID. The gate maps it to its internal request id, which is consumed as a typed UUID on the cross-gate path.","status":400,"title":"Invalid Request ID","type":"https://api.efti.ee/errors/invalid-request-id"}
```

### 401 UNAUTHORIZED

X-Road, no `X-Road-Client` (also for a malformed one such as `DEV/GOV/`):

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
traceparent: 00-3f4df3f01e6978170cbc3f30f151bda4-ad1d2c5571f57f83-01
x-trace-id: 3f4df3f01e6978170cbc3f30f151bda4

{"code":"UNAUTHORIZED","detail":"X-Road-Client header is missing or malformed. Expected instance/memberClass/memberCode[/subsystemCode].","status":401,"title":"Authentication Required","type":"https://api.efti.ee/errors/unauthorized"}
```

Core, no `X-Internal-Service-Token`:

```bash
curl -i "http://127.0.0.1:27086/efti/api/v1/authority/dataset/G1/mock/11111111-0000-4000-8000-000000000001?subsets=EU01"
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
traceparent: 00-bbef6f32469f8fc080a9cea6260b486a-24c1853763fedd64-01
x-trace-id: bbef6f32469f8fc080a9cea6260b486a

{"code":"UNAUTHORIZED","detail":"This endpoint requires a valid X-Internal-Service-Token.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

Ops, wrong token (`Authorization: Bearer wrong`; an admin JWT gets the same):

```bash
curl -i -X POST http://127.0.0.1:27086/ops/v1/expire-identifiers \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer wrong' -d '{"dryRun":true}'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
traceparent: 00-954ff846a7255747bc08955edd827863-971a396f4c8a1334-01
x-trace-id: 954ff846a7255747bc08955edd827863

{"code":"UNAUTHORIZED","detail":"This endpoint requires a valid CronManager ops token.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

Platform, wrong `X-Api-Key` (no `code`):

```bash
curl -i -X POST "http://127.0.0.1:27086/platforms/v1/consignments/$(cat /proc/sys/kernel/random/uuid)" \
  -H 'Content-Type: application/json' -H 'X-Api-Key: wrong' -d '{}'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
traceparent: 00-104c326c41030f38520ff614664e1c13-6f7c28f801037cb1-01
x-trace-id: 104c326c41030f38520ff614664e1c13

{"detail":"X-Api-Key is missing or does not match a registered platform.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

Admin, invalid JWT (`Authorization: Bearer abc.def.ghi`, no `code`):

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
traceparent: 00-0f23641cc13319baebd924c33cb3616b-d804eb11b4a35c8a-01
x-trace-id: 0f23641cc13319baebd924c33cb3616b

{"detail":"Token is missing, invalid, expired, or the user has no active gate account.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

### 403 FORBIDDEN

Client that is no registered authority (`X-Road-Client: DEV/GOV/12345678/x`):

```http
HTTP/1.1 403 Forbidden
content-type: application/problem+json
traceparent: 00-fd3060a2cd2033c04823c326e7b52d40-8285cf653ea3ae17-01
x-trace-id: fd3060a2cd2033c04823c326e7b52d40

{"code":"FORBIDDEN","detail":"The X-Road client does not resolve to an active competent authority.","status":403,"title":"Forbidden","type":"https://api.efti.ee/errors/forbidden"}
```

Registry code held by two authorities (`DEV/GOV/70000091/x`):

```http
HTTP/1.1 403 Forbidden
content-type: application/problem+json
traceparent: 00-3e09c6970a4777ff45d1910ee3f3c215-e4c5beb46f238fc0-01
x-trace-id: 3e09c6970a4777ff45d1910ee3f3c215

{"code":"FORBIDDEN","detail":"The X-Road client registry code resolves to more than one active competent authority. This is a registry misconfiguration; contact the gate operator.","status":403,"title":"Forbidden","type":"https://api.efti.ee/errors/forbidden"}
```

### 403 FORBIDDEN_SUBSET

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"G1","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01","EU06","EU06"]}'
```

```http
HTTP/1.1 403 Forbidden
content-type: application/json
traceparent: 00-00a94843f3f0fef4b0c5c11ffd735c6d-f51255a0f32fe487-01
x-trace-id: 00a94843f3f0fef4b0c5c11ffd735c6d

{"authorityId":"auth-xroad-tm","code":"FORBIDDEN_SUBSET","deniedSubsets":["EU06"],"detail":"The requesting authority is not permitted to access one or more of the requested eFTI subsets.","permittedSubsets":["EU01","EU02"],"status":403,"title":"Subset Access Denied","type":"https://api.efti.ee/errors/forbidden-subset"}
```

More cases in [eFTI subsets](./subsets.md#examples).

### 404

No route (engine):

```bash
curl -i http://127.0.0.1:27086/efti/api/v1/nope
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
traceparent: 00-bc4c4ce70acd18efc227b7bd20993454-044105433ba9e02f-01
x-trace-id: bc4c4ce70acd18efc227b7bd20993454

{"error":"Not Found"}
```

Unknown user (`GET /admin/v1/users/<random uuid>` with the admin JWT) answers the same body:

```http
HTTP/1.1 404 Not Found
content-type: application/json
traceparent: 00-5c4ca3dc9b4309aa738c315a082ba324-431c42dd52074657-01
x-trace-id: 5c4ca3dc9b4309aa738c315a082ba324

{"error":"Not Found"}
```

### 409 DUPLICATE_REQUEST_ID

The same `X-Road-Id` sent twice to `dataset`. The first answer is `200`, the second:

```http
HTTP/1.1 409 Conflict
content-type: application/problem+json
traceparent: 00-142ad53565dd26e0ce16df2ee30b691e-60fc64ed37cef539-01
x-trace-id: 142ad53565dd26e0ce16df2ee30b691e

{"code":"DUPLICATE_REQUEST_ID","detail":"This X-Road-Id has already been processed. Retry with a new request id if this is a new request.","status":409,"title":"Duplicate Request ID","type":"https://api.efti.ee/errors/duplicate-request-id"}
```

### 409 Conflict (no code)

`POST /admin/v1/users` with the `taraSub` of an existing active user (the dev admin's); the
route checks before it inserts, so nothing is written:

```bash
curl -i -X POST http://127.0.0.1:27086/admin/v1/users \
  -H "Authorization: Bearer eyJ…" -H 'Content-Type: application/json' \
  -d '{"taraSub":"60001019906","name":"Duplicate"}'
```

```http
HTTP/1.1 409 Conflict
content-type: application/json
traceparent: 00-5aca3e61e7d0b6bf3baa76ae0f1a810d-82672d77066dc7f9-01
x-trace-id: 5aca3e61e7d0b6bf3baa76ae0f1a810d

{"detail":"tara_sub already exists on an active user","error":"Conflict"}
```

### 415 UNSUPPORTED_MEDIA_TYPE

```bash
curl -i -X POST http://127.0.0.1:27086/services/msh -H 'Content-Type: application/json' -d '{}'
```

```http
HTTP/1.1 415 Unsupported Media Type
content-type: application/problem+json

{"code":"UNSUPPORTED_MEDIA_TYPE","detail":"Content-Type must be multipart/related. The AS4 MSH endpoint does not accept other media types.","status":415,"title":"Unsupported Media Type","type":"https://api.efti.ee/errors/unsupported-media-type"}
```

### 501 NOT_IMPLEMENTED

```bash
curl -i -X POST http://127.0.0.1:27088/developer/v1/transport-means \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000099/testSubsystem' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" -d '{"identifier":"123ABC","scope":"allgates"}'
```

```http
HTTP/1.1 501 Not Implemented
content-type: application/json
traceparent: 00-50fc096261320d80c8481848077de36b-fb9abdeb333a3e79-01
x-trace-id: 50fc096261320d80c8481848077de36b

{"code":"NOT_IMPLEMENTED","detail":"scope 'allgates' is part of the published contract but not yet implemented. Use scope 'local'.","status":501,"title":"Not Implemented","type":"https://api.efti.ee/errors/not-implemented"}
```

### 502 GATEWAY_UNAVAILABLE

A UIL naming a gate that is not in the registry:

```bash
curl -i -X POST http://127.0.0.1:27087/xroad/provide/v1/dataset \
  -H 'Content-Type: application/json' -H 'X-Road-Client: DEV/GOV/70000093/ants' \
  -H "X-Road-Id: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"uil":{"gateId":"EU-XX","platformId":"mock","datasetId":"11111111-0000-4000-8000-000000000001"},"subsets":["EU01"]}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
traceparent: 00-0bca818ba53dbabfc91c671b047890db-037fe656ac7267d3-01
x-trace-id: 0bca818ba53dbabfc91c671b047890db

{"code":"GATEWAY_UNAVAILABLE","coreResponse":{"detail":{"detail":"Unknown party: EU-XX","instance":"/api/v1/send/EU-XX","message":"Unknown party: EU-XX","status":400,"title":"Bad Request"},"error":"Remote gate dataset request failed"},"coreStatus":400,"detail":"The gate's dataset request failed. The gate's own status and response are carried in coreStatus and coreResponse.","status":502,"title":"Bad Gateway","type":"https://api.efti.ee/errors/bad-gateway"}
```

A platform unknown at the other gate: after 60 s, `coreStatus` 504 and
`"detail":"No data received after 1m"` inside `coreResponse` ([UIL](./uil.md#validation)).

### 502 without code

`GET /admin/v1/users?limit=abc` with the admin JWT:

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
traceparent: 00-400cc9708c097b170e624c899f969ec8-4d19d1176374aac3-01
x-trace-id: 400cc9708c097b170e624c899f969ec8
content-length: 34

{"error":"Data layer unavailable"}
```

## Notes

- Not run locally: `429` (no reverse proxy), `503` from the admin/auth guards (needs TIM down),
  the ops job `502`s (need a failing database), `GATEWAY_UNAVAILABLE` with `transportError`
  (needs core down). Restarting containers is outside what these pages may do.
- The X-Road adapter turns every core error into `502 GATEWAY_UNAVAILABLE`. Look at
  `coreStatus` and `coreResponse` for the cause; [dataset](../api/authorities/dataset.md) lists
  the combinations.
- Related: [Conventions](../api/conventions.md), [Troubleshooting](../reference/troubleshooting.md),
  [Known issues](../reference/known-issues.md).
