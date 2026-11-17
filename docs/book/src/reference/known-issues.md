# Known issues

Defects found while writing this book (November 2026, branch `dev` at PR #380). Each one was seen
on the local two-gate stack unless it says "from the code". None of them is fixed here; they are
recorded so you recognise them, and as input for issues. The reference pages describe the
behaviour as it is and link back here.

## Security

| # | Issue | Where | Details |
|---|---|---|---|
| S1 | **Ruuter logs secrets.** The INFO step log prints guard expressions after constant substitution: one hour of G1's ruuter log held the internal service token 229 times and the ops token 14 times. | Ruuter step logging, every guard that compares `[#…_TOKEN]` | [Operations](../api/operations/index.md) |
| S2 | **The admin API returns a platform credential.** `GET /admin/v1/platforms[/{id}]` removes `apiKey` but returns `headers` unchanged. For `mock` that is `X-Api-Key: mock-secret-key`, the very key the stripping hides. | `DSL/Ruuter/admin/GET/v1/platforms.yml` | [Registries](../api/admin/registries.md) |
| S3 | **`/mock-platform/**` ships in every Ruuter image.** `docker/ruuter/Dockerfile` copies all of `DSL/Ruuter/`, so the mock-platform routes are served by production builds too. Their key is no longer hard-coded: they compare `X-Api-Key` with `MOCK_PLATFORM_API_KEY` from the mounted `constants.ini` (dev value `mock-secret-key`; empty denies), so a deployment without the mock platform must set a long random value. | `docker/ruuter/Dockerfile` | [Mock platform](../api/platforms/mock-platform.md) |
| S4 | **The UI nginx proxies the gate-internal authority API.** `docker/ui/nginx.conf` forwards `/efti/api/v1/authority/` to ruuter, although `constants.ini` says the path must not be publicly reachable. Only the internal token protects it. | `docker/ui/nginx.conf` | [Gate internals](../api/internal/index.md) |
| S5 | **An empty search returns unfiltered rows.** `incoming.body == null` never fires, so `{}`, `null` or no body on `POST /xroad/provide/v1/search` (and core `authority/search`) returns the gate's 20 newest consignments with all fields. | `search.yml` (both Ruuters) | [search](../api/authorities/search.md) |
| S6 | **With the internal token, any `X-Platform-Id` is accepted** on every `/platforms/**` route, also an id not in the registry (`ghost-platform` → 201). | `DSL/Ruuter/platforms/.guard.yml` | [Freight platforms](../api/platforms/index.md) |
| S7 | **Authority data access is not audited.** No route writes `identifier.search`, `dataset.deliver` or `followup.send`, which `permissions-matrix.md` §4 requires (GDPR Art. 30). `GET /admin/v1/gates/own`, `consignment-history` and a successful logout write no audit row either. | X-Road and core routes | [Audit log](../api/admin/audit.md) |
| S8 | **The audit IP is always null.** `X-Forwarded-For` is stored, but `get_audit_log.sql` reads the `inet` column without `::text`, so the API returns `ipAddress: null`. | `DSL/Resql/efti/POST/get_audit_log.sql` | [Audit log](../api/admin/audit.md) |
| S9 | **Purge jobs accept 0 or negative keep values**, which would delete whole tables. | `DSL/Ruuter/ops/POST/v1/purge-*.yml` | [Scheduled jobs](../api/operations/jobs.md) |
| S10 | **`baasikontoroll` is public and shows the exact PostgreSQL build.** | `efti/GET/api/v1/test/baasikontoroll.yml` | [Health](../api/operations/health.md) |
| S11 | **Revoked tokens pass the test probes.** `test/lubatud` and `test/piiratud` check only TIM, not the user row. | `efti/GET/api/v1/test/*.yml` | [Health](../api/operations/health.md) |
| S12 | **The receiving gate does not check the inbound request.** It does not compare `GateID` with its own id, the dataset with its register, or the requested subsets with EU01..EU07; `EE05b` reaches the platform as `?subsets=EE05b`. | `dataset-xml.yml`, `dataset-local.yml` | [Dataset: FTI009 / FTI010](../api/gates/dataset-fti009.md) |
| S13 | **AS4 receipts are not verified.** The sender treats any 2xx as delivered; legacy mode has no `MessageId` duplicate check. From the code. | `EDeliveryClient.kt` | [Peer gates](../api/gates/index.md) |

## Errors reported as something else

| # | Issue | Where | Details |
|---|---|---|---|
| E1 | **Unknown platform reads as a data-layer outage.** The registry answers 404 for an unknown id; `dataset-local` and `follow-up-local` send every status ≥ 300 to "Platform lookup failed (data layer unavailable)" (502, or `coreStatus` 404 through X-Road). The "Platform Not Found" branch is reached only for a `DISABLED` platform. | `dataset-local.yml`, `follow-up-local.yml` | [What the gate calls on a platform](../api/platforms/platform-side-api.md) |
| E2 | **G2G handlers answer 2xx on failure.** `dataset-xml` (200) and `follow-up-xml` (201) return xml-mapper's error JSON whenever the local step fails. edelivery sends that to the peer, the peer refuses it as `EBMS:0002 UNSUPPORTED_MESSAGE`, and the requester waits 60 s for a 504. There is no FTI error answer (`StatusCode` `E`) anywhere. | `dataset-xml.yml`, `follow-up-xml.yml` | [Inbound handlers](../api/gates/inbound-handlers.md) |
| E3 | **`authority/follow-up` answers 201 when the local step failed**, with `{"error":"Platform lookup failed…"}` or `{"error":"Failed to log follow-up message"}` in the body. | `authority/follow-up.yml` | [follow-up](../api/authorities/follow-up.md) |
| E4 | **Malformed XML gives 502 "XML mapper unavailable"**, not 400: xml-mapper reports parse errors and unsupported date formats as 500. Same for a short upload without `datasetId`. | `platforms/POST/v1/consignments.yml`, xml-mapper | [Upload (REST)](../api/platforms/upload-rest.md) |
| E5 | **Unchecked upload values fail in the database** (500 "Failed to insert consignment"): a mode code longer than 1 character, a dangerous-goods code longer than 2. Any single character is accepted as a mode code. No XSD validation: `<Hello>` as root or an empty `<ParameterIDSetCriteria/>` is stored. | xml-mapper `UploadRoutes.kt` | [Upload (REST)](../api/platforms/upload-rest.md) |
| E6 | **xml-mapper 400 is not caught in `authority/dataset`.** A remote UIL with a non-UUID `datasetId` sends xml-mapper's error body as the AS4 payload; the caller gets 502 with `EBMS:0002`. | `authority/dataset.yml` | [UIL](../data/uil.md) |
| E7 | **A failed AS4 upload gets no negative FTI029.** The platform receives the receipt and then nothing; the sender times out after a minute. | edelivery, `consignments-xml.yml` | [Upload (eDelivery)](../api/platforms/upload-edelivery.md) |
| E8 | **Non-UUID or non-numeric admin parameters read as outages**: `?limit=abc` → 502 "Data layer unavailable"; a non-UUID user id → 502 (GET/PUT/revoke) or 500 (consignment DELETE). | admin routes, ReSql | [Users](../api/admin/users.md) |
| E9 | **`DELETE /admin/v1/users/{unknown}` answers 500** "User deletion could not be verified"; the 404 branch is dead code. The self-delete 400 body is a double-encoded string without `code`. | `admin/DELETE/v1/users.yml` | [Users](../api/admin/users.md) |
| E10 | **`consignment-history` hides ReSql errors** as empty arrays. | `admin/GET/v1/consignment-history.yml` | [Consignments](../api/admin/consignments.md) |
| E11 | **A search without `X-Request-Id`** that has to fan out answers 502 "Registering the cross-gate broadcast failed". | core `authority/search.yml` | [Core authority API](../api/internal/core-authority.md) |
| E12 | **An array `identifier` in transport-means gives 502**, not the "200 found: 0" the DSL comment promises. | `transport-means.yml` | [transport-means](../api/authorities/transport-means.md) |

## Wrong results

| # | Issue | Where | Details |
|---|---|---|---|
| W1 | **Date criteria never work across gates.** xml-mapper wants dates as `{formatId, value}`, the SQL wants a string: a string date works locally but cannot be mapped to FTI019; an object date fails locally with 502. Core does not check the `criteria_to_xml` status and broadcasts the error body; the peer answers `EBMS:0002`, the poll returns `[]`. `LT` on text fields, a wrong value key and unknown country codes fail the same way. | core `authority/search.yml`, xml-mapper `ParameterSearchCriteria.kt` | [Search criteria](../data/search-criteria.md) |
| W2 | **Search is capped at 20 rows with no paging**, locally and over FTI019. | `get_consignments.sql`, `search-xml.yml` | [search](../api/authorities/search.md) |
| W3 | **The transport-means poll echoes the poll body**: `identifier` and `countryCode` come back as `""`. | `transport-means.yml` | [transport-means](../api/authorities/transport-means.md) |
| W4 | **`search` and `transport-means` share stored searches.** A transport-means poll can collect a search started by the same authority. | both X-Road routes | [transport-means](../api/authorities/transport-means.md) |
| W5 | **The local/remote decision is case-sensitive.** `uil.gateId` `g1` sent to G1 goes out over AS4 to itself (and works, because edelivery's lookup is case-insensitive). | `authority/dataset.yml`, `authority/follow-up.yml` | [UIL](../data/uil.md) |
| W6 | **`follow_up_log` stores the wrong gate.** `requesting_gate_id` holds `uil.gateId` (the destination), and `dataset_request_id` the dataset id. | `follow-up-local.yml` | [follow-up](../api/authorities/follow-up.md) |
| W7 | **FTI021 has a nested `<IncludedNote><IncludedNote>`** when empty, which does not match the schema. | xml-mapper `ExchangedDocument.render()` | [Identifier search](../api/gates/search-fti019.md) |
| W8 | **edelivery writes `aes128-gcm` for a 256-bit key.** Confirmed by decrypting a captured message. | `EDeliveryMessageGenerator.kt` | [AS4 endpoint](../api/gates/as4-endpoint.md) |
| W9 | **`RequestedSpecifiedQuery/ID` in answers is the AS4 conversation id**, not the query id of the request. FTI029/021/010/030. | xml-mapper | [Peer gates](../api/gates/index.md) |
| W10 | **Date format 207 is written with `Z`** (`20261103160338Z`) instead of the `+0000` offset the format describes. | xml-mapper `Formats.kt` | [Conventions](../api/conventions.md) |
| W11 | **`PUT /admin/v1/users/{id}` allows a duplicate personal code**; while two active users share one, login for it fails with 401. `POST /admin/v1/users` accepts an invalid personal code that `dev-login` rejects. | `admin/PUT/v1/users.yml` | [Users](../api/admin/users.md) |
| W12 | **`check-admin-authority` checks `loginExpireDate`, which TIM never sends** (`expires_at`), so the expiry branch never fires. | `efti/POST/internal/check-admin-authority.yml` | [Auth helpers](../api/internal/auth-helpers.md) |
| W13 | **The X-Road `dataset` route does not check a local UIL**: an unknown or non-UUID `datasetId` goes to the platform (the mock answers every id). | `authority/dataset.yml` | [dataset](../api/authorities/dataset.md) |
| W14 | **`GET /auth/user` returns only `id`, `name`, `taraSub`**, not the fields its declaration lists. | `auth/GET/user.yml` | [Login and session](../api/admin/auth.md) |
| W15 | **A re-upload revives a deleted dataset** (DELETED → ACTIVE). Possibly intended. | `consignments` table rules | [Upload (REST)](../api/platforms/upload-rest.md) |

## HTTP protocol

| # | Issue | Where | Details |
|---|---|---|---|
| H1 | **204 responses carry `content-length: 2` and no body.** The routes return `""` with `status: 204`. curl copes; Postman/Newman wait for the 2 bytes and time out (keep-alive) or abort (`Connection: close`). Affected: `POST /efti/api/v1/ping`, `POST /admin/v1/users/revoke-token/{id}`, `DELETE /admin/v1/users/{id}`, `DELETE /admin/v1/consignments/{id}`, `POST /admin/v1/js-error`. Folder **9. Known issues** of the collection reproduces it. | the five routes | [Local platform handlers](../api/internal/local-handlers.md) |
| H2 | **Route-level problem bodies are `application/json`**, not `application/problem+json`; only guard answers use the problem type. ruuter never sets `instance`. | many routes | [Conventions](../api/conventions.md) |
| H3 | **The G2G inbound routes reject `application/xml`** and `text/plain` (`400 Field missing: xml`); only `text/xml` or a JSON `{"xml"}` works. | `efti/POST/api/v1/*-xml.yml` | [Inbound handlers](../api/gates/inbound-handlers.md) |

## Documentation that disagrees with the code

The book follows the code. These repository documents are out of date in the points listed on
the linked pages:

| Document | Main differences | Page |
|---|---|---|
| `docs/specs/permissions-matrix.md` | `/api/v1/…` paths, registry write routes, ping routes, break-glass login, mTLS platform auth, ops 403, logout 204 | [Permissions matrix](../data/permissions.md) |
| `docs/specs/errors.json` | 27 of 39 codes sent by no route; 8 ops codes missing; content type and `instance` claims | [Error catalogue](../data/errors.md) |
| `docs/specs/api_endpoints.md` | G2G routes "public", "after AS4 mTLS"; probe content types; a non-existent archive route; 409 on duplicate upload request id | [Inbound handlers](../api/gates/inbound-handlers.md), [Health](../api/operations/health.md) |
| `docs/developer/x_road_openapi.yaml`, `x_road_authority_integration_guide.md` | follow-up "not implemented", optional `referenceIds`, 200; operators LT–GE on all fields; missing 409/400; "six operations" | [follow-up](../api/authorities/follow-up.md), [Search criteria](../data/search-criteria.md) |
| `docs/developer/platform_api_integration_guide.md` | platforms registered in the admin UI; `X-Gate-Id` header; 400 on invalid XML | [Freight platforms](../api/platforms/index.md) |
| `docs/developer/x_road_developer_mock.md` | transport-means poll 501 (is 400); "1:1 with live" | [Developer mock](../api/authorities/developer-mock.md) |
| `docs/architecture/identity-and-access/authentication.md` | login flow, token lifetime | [Login and session](../api/admin/auth.md) |
| `docs/specs/non-functional.md` | `GATE_BROADCAST_TIMEOUT_MS`, `PLATFORM_TIMEOUT_MS` (read by nothing) | [Conventions](../api/conventions.md) |
| `docs/specs/openapi.generated.yaml` | `dataset-xml`, `follow-up-xml` bodies listed as JSON | [Inbound handlers](../api/gates/inbound-handlers.md) |

## Not a bug: the `gateId` on `search-xml`

On the receiving gate, edelivery calls `POST /efti/api/v1/consignments/search-xml?gateId=<own gate>`.
The edelivery log line `Handling 'FTI019SearchIdentifierRequest' from G1:<id>:G2` reads as
if `G1` were the gate being searched. It isn't: the key prints as
`receiverId:requestId:senderId` and is built from the reply's point of view. So the search runs on
the receiving gate's own rows, as it should.
