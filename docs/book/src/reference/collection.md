# The Postman collection

Every request of `local/postman/eFTI-Gate.postman_collection.json`, by folder, and of the known-issues
collection. The collection is gate-agnostic: it runs against the gate whose environment is selected
(`G1.postman_environment.json`, `G2.postman_environment.json`), and cross-gate requests go to
that environment's `peer_gate`.

`{{…}}` are environment variables ([Ports and credentials](./ports-and-credentials.md));
`{{rid}}` is a fresh UUID per request. Each request checks the status shown in its name or, if
none is shown, a 2xx answer and the key parts of the body.

When you change the collection in Postman, export it over the file and update this page.

## 0. Health

The gate answers: Ruuter (with its database), Ruuter-xroad, edelivery.

| Request | Method | URL | Note |
|---|---|---|---|
| ruuter health/ready | `GET` | `{{ruuter}}/efti/health/ready` |  |
| ruuter-xroad health/ready | `GET` | `{{xroad}}/health/ready` |  |
| edelivery MSH up | `GET` | `{{edelivery}}/services/msh` |  |

## 1. CA -> Gate (X-Road provider)

A competent authority calls the gate through its X-Road provider API (`/xroad/provide/**` on ruuter-xroad). Remote requests travel to the peer gate over AS4. Locally there is no Security Server: the request sets `X-Road-Client` and `X-Road-Id` itself.

| Request | Method | URL | Note |
|---|---|---|---|
| Identity guard / openapi (public) | `GET` | `{{xroad}}/provide/openapi` |  |
| Identity guard / echo | `POST` | `{{xroad}}/provide/v1/echo` |  |
| Identity guard / echo - no X-Road-Client -> 401 | `POST` | `{{xroad}}/provide/v1/echo` |  |
| Identity guard / echo - unknown client -> 403 | `POST` | `{{xroad}}/provide/v1/echo` |  |
| Identity guard / echo - no X-Road-Id -> 400 | `POST` | `{{xroad}}/provide/v1/echo` |  |
| Identity guard / echo - X-Road-Id not a UUID -> 400 | `POST` | `{{xroad}}/provide/v1/echo` |  |
| Identity guard / subsets | `GET` | `{{xroad}}/provide/v1/subsets` |  |
| Search / search - local hit TST-001 | `POST` | `{{xroad}}/provide/v1/search` | A local hit answers at once from this gate and does not fan out (by design). |
| Search / search - peer-only plate starts fan-out | `POST` | `{{xroad}}/provide/v1/search` | `peer_only_plate` is seeded only at the peer gate. No local hit, so the gate asks the other gates over AS4 (FTI019) and answers `[]` with `x-poll-more: true` and `x-search-id`. Poll with the next request. |
| Search / search - poll peer-only plate | `POST` | `{{xroad}}/provide/v1/search` | Same searchId, new X-Road-Id. Rows from the peer gate carry only `uil` and `mainTransportId` (what xml-mapper's FTI021 -> JSON gives). |
| Search / search - by transport mode (road) | `POST` | `{{xroad}}/provide/v1/search` |  |
| Search / search - poll without searchId -> 400 | `POST` | `{{xroad}}/provide/v1/search` |  |
| Transport means (EU02) / transport-means - existence TST-001 | `POST` | `{{xroad}}/provide/v1/transport-means` |  |
| Transport means (EU02) / transport-means - local TST-001 | `POST` | `{{xroad}}/provide/v1/transport-means` |  |
| Transport means (EU02) / transport-means - allgates peer-only vessel starts fan-out | `POST` | `{{xroad}}/provide/v1/transport-means` |  |
| Transport means (EU02) / transport-means - poll peer-only vessel | `POST` | `{{xroad}}/provide/v1/transport-means` | Known issue: the polled answer has `identifier: ""` (transport-means.yml poll path). |
| Transport means (EU02) / transport-means - client without EU02 -> 403 | `POST` | `{{xroad}}/provide/v1/transport-means` |  |
| Transport means (EU02) / transport-means - missing identifier -> 400 | `POST` | `{{xroad}}/provide/v1/transport-means` |  |
| Transport means (EU02) / transport-means - invalid scope -> 400 | `POST` | `{{xroad}}/provide/v1/transport-means` |  |
| Dataset and follow-up / dataset - local | `POST` | `{{xroad}}/provide/v1/dataset` |  |
| Dataset and follow-up / dataset - remote (peer gate, AS4 FTI009) | `POST` | `{{xroad}}/provide/v1/dataset` | The content comes from the mock platform (MOCK-PLATE-1), not from the stored identifiers. |
| Dataset and follow-up / dataset - subset not held -> 403 | `POST` | `{{xroad}}/provide/v1/dataset` |  |
| Dataset and follow-up / dataset - missing uil/subsets -> 400 | `POST` | `{{xroad}}/provide/v1/dataset` |  |
| Dataset and follow-up / follow-up - remote (peer gate, AS4 FTI025) | `POST` | `{{xroad}}/provide/v1/follow-up` | referenceIds = the X-Road-Id of the remote dataset request above. |
| Dataset and follow-up / follow-up - local | `POST` | `{{xroad}}/provide/v1/follow-up` |  |
| Dataset and follow-up / follow-up - empty body -> 400 | `POST` | `{{xroad}}/provide/v1/follow-up` |  |

## 2. G2G -> peer gate (AS4 via edelivery)

The gate-internal edelivery API (`/api/v1/send/{party}`) wraps the FTI XML in an AS4 message, sends it to the peer gate's `/services/msh` and waits for the answer. This is what Ruuter calls for every remote request; here you call it directly.

| Request | Method | URL | Note |
|---|---|---|---|
| FTI019 search TST-001 | `POST` | `{{edelivery}}/api/v1/send/{{peer_gate}}` |  |
| FTI009 dataset | `POST` | `{{edelivery}}/api/v1/send/{{peer_gate}}` |  |
| FTI025 follow-up | `POST` | `{{edelivery}}/api/v1/send/{{peer_gate}}` |  |
| send without token -> 401 | `POST` | `{{edelivery}}/api/v1/send/{{peer_gate}}` |  |
| send to unknown party -> 400 | `POST` | `{{edelivery}}/api/v1/send/EU-XX` |  |

## 3. G2G inbound handlers and the AS4 edge

Called by edelivery, with the internal token, once an inbound AS4 message is unpacked. `/services/msh` is the public AS4 endpoint peers post to.

| Request | Method | URL | Note |
|---|---|---|---|
| search-xml (FTI019 -> FTI021) | `POST` | `{{ruuter}}/efti/api/v1/consignments/search-xml?gateId={{own_gate}}` | edelivery passes `gateId` = the receiving gate's own id, so the search runs on this gate's rows. |
| dataset-xml (FTI009 -> FTI010) | `POST` | `{{ruuter}}/efti/api/v1/dataset-xml` |  |
| follow-up-xml (FTI025 -> FTI030) | `POST` | `{{ruuter}}/efti/api/v1/follow-up-xml` |  |
| AS4 edge /services/msh - JSON -> 415 | `POST` | `{{ruuter}}/services/msh` |  |
| AS4 edge /services/msh - malformed multipart -> 400 | `POST` | `{{ruuter}}/services/msh` | A SOAP fault from edelivery: proves Ruuter forwards /services/msh to edelivery. |

## 4. Core authority API (gate-internal)

The gate-internal API behind the X-Road adapter and the UI (needs `X-Internal-Service-Token`). Useful to tell an X-Road adapter problem from a core problem.

| Request | Method | URL | Note |
|---|---|---|---|
| authority/search TST-001 | `POST` | `{{ruuter}}/efti/api/v1/authority/search` |  |
| authority/dataset - local | `POST` | `{{ruuter}}/efti/api/v1/authority/dataset` |  |
| authority/dataset - remote | `POST` | `{{ruuter}}/efti/api/v1/authority/dataset` |  |
| GET authority/dataset/{gate}/{platform}/{id} - remote | `GET` | `{{ruuter}}/efti/api/v1/authority/dataset/{{peer_gate}}/{{platform_id}}/{{peer_ds}}?subsets=EU01` | Path form only; the same values as query parameters give 404. |
| authority/follow-up - remote | `POST` | `{{ruuter}}/efti/api/v1/authority/follow-up` |  |
| GET follow-up/{datasetId} | `GET` | `{{ruuter}}/efti/api/v1/follow-up/{{own_ds}}` |  |
| GET status/{datasetId} | `GET` | `{{ruuter}}/efti/api/v1/status/{{own_ds}}` | Without the datasetId path parameter the route answers 502 'Data layer unavailable'. |
| authority/search - no token -> 401 | `POST` | `{{ruuter}}/efti/api/v1/authority/search` |  |

## 5. Platform -> Gate

A freight platform registers consignment identifiers at its gate. The `mock` platform key is `X-Api-Key: mock-secret-key`.

| Request | Method | URL | Note |
|---|---|---|---|
| upload - short ParameterIDSetCriteria | `POST` | `{{ruuter}}/platforms/v1/consignments/{{$guid}}` | REST upload: the datasetId is the path parameter, the body only the criteria. |
| upload - full FTI004 | `POST` | `{{ruuter}}/platforms/v1/consignments` |  |
| upload - FTI004 for another gate -> 403 | `POST` | `{{ruuter}}/platforms/v1/consignments` | The UIL must belong to the authenticated platform and to this gate. |
| upload - no X-Api-Key -> 401 | `POST` | `{{ruuter}}/platforms/v1/consignments` |  |
| upload - wrong X-Api-Key -> 401 | `POST` | `{{ruuter}}/platforms/v1/consignments` |  |
| upload over eDelivery path - consignments-xml (FTI004 -> FTI029) | `POST` | `{{ruuter}}/platforms/v1/consignments-xml` | What edelivery calls after a platform's AS4 FTI004 upload (gate-internal). |
| mock platform - GET dataset | `GET` | `{{ruuter}}/mock-platform/v1/dataset/{{own_ds}}` | The mock platform the gate fetches datasets from (dev images only). |

## 6. Administrators

The admin API with a dev-login JWT. The pre-request scripts log in automatically when no token is cached. Every gate has its own TIM and users.

| Request | Method | URL | Note |
|---|---|---|---|
| Login and session / dev-login (admin token) | `POST` | `{{ruuter}}/auth/dev-login` | Dev/CI only (DEV_LOGIN_ENABLED=true). Mints a TIM JWT for a personal code; the admin API also needs an active users row for it (the dev admin 60001019906 is seeded on every gate). |
| Login and session / dev-login - invalid personal code | `POST` | `{{ruuter}}/auth/dev-login` |  |
| Login and session / auth/user | `GET` | `{{ruuter}}/auth/user` |  |
| Login and session / auth/user - no token -> 401 | `GET` | `{{ruuter}}/auth/user` |  |
| Login and session / logout (a second token) | `POST` | `{{ruuter}}/auth/logout` | Logs in a second time and logs that token out, so the shared admin token keeps working. |
| Login and session / auth/user - logged-out token -> 401 | `GET` | `{{ruuter}}/auth/user` |  |
| Users / users - list | `GET` | `{{ruuter}}/admin/v1/users?limit=20&offset=0` |  |
| Users / users - the dev admin | `GET` | `{{ruuter}}/admin/v1/users/11111111-1111-1111-1111-111111111111` |  |
| Users / users - unknown id -> 404 | `GET` | `{{ruuter}}/admin/v1/users/{{$guid}}` |  |
| Users / users - duplicate personal code -> 409 | `POST` | `{{ruuter}}/admin/v1/users` | The route checks before it inserts, so nothing is written. |
| Users / users - missing name -> 400 | `POST` | `{{ruuter}}/admin/v1/users` |  |
| Users / users - no token -> 401 | `GET` | `{{ruuter}}/admin/v1/users` |  |
| Registries (read-only) / gates | `GET` | `{{ruuter}}/admin/v1/gates` |  |
| Registries (read-only) / gates/own | `GET` | `{{ruuter}}/admin/v1/gates/own` |  |
| Registries (read-only) / gates/{id} (the peer gate) | `GET` | `{{ruuter}}/admin/v1/gates/{{peer_gate}}` |  |
| Registries (read-only) / gates/{id} unknown -> 404 | `GET` | `{{ruuter}}/admin/v1/gates/EU-XX` |  |
| Registries (read-only) / platforms | `GET` | `{{ruuter}}/admin/v1/platforms` |  |
| Registries (read-only) / authorities | `GET` | `{{ruuter}}/admin/v1/authorities` |  |
| Registries (read-only) / authorities/{id} | `GET` | `{{ruuter}}/admin/v1/authorities/auth-xroad-tm` |  |
| Registries (read-only) / gates - POST -> 405 (registries are read-only) | `POST` | `{{ruuter}}/admin/v1/gates` |  |
| Consignments and audit / consignments | `GET` | `{{ruuter}}/admin/v1/consignments?limit=10` |  |
| Consignments and audit / consignment-history (seed dataset) | `GET` | `{{ruuter}}/admin/v1/consignment-history?datasetId={{own_ds}}` |  |
| Consignments and audit / consignment-history - no datasetId -> 400 | `GET` | `{{ruuter}}/admin/v1/consignment-history` |  |
| Consignments and audit / consignment-counts | `GET` | `{{ruuter}}/admin/v1/consignment-counts` | Read model (ADR-012): empty until POST /ops/v1/refresh-read-models has run (folder 7). |
| Consignments and audit / consignment-summary | `GET` | `{{ruuter}}/admin/v1/consignment-summary?authorityId=auth-xroad-tm` |  |
| Consignments and audit / audit (last 20) | `GET` | `{{ruuter}}/admin/v1/audit?limit=20` |  |
| Consignments and audit / audit - filter by resource | `GET` | `{{ruuter}}/admin/v1/audit?resource=user&limit=5` |  |

## 7. Operations

Health probes of every service, and the /ops/v1 jobs CronManager runs (not part of the local network, so they are called by hand). Ops token: `dev-archive-ops-token-change-me`.

| Request | Method | URL | Note |
|---|---|---|---|
| Probes / ruuter health/live | `GET` | `{{ruuter}}/efti/health/live` |  |
| Probes / ruuter /health (engine) | `GET` | `{{ruuter}}/health` |  |
| Probes / edelivery /health | `GET` | `{{edelivery}}/health` |  |
| Probes / xml-mapper /health | `GET` | `{{xml_mapper}}/health` |  |
| Probes / resql /health | `GET` | `{{resql}}/health` |  |
| Probes / registry /health.json | `GET` | `{{registry}}/health.json` |  |
| Probes / developer mock health/ready | `GET` | `{{devmock}}/developer/health/ready` |  |
| Probes / test/baasikontoroll (DB status) | `GET` | `{{ruuter}}/efti/api/v1/test/baasikontoroll` |  |
| Probes / test/lubatud (public) | `GET` | `{{ruuter}}/efti/api/v1/test/lubatud` |  |
| Probes / test/piiratud - no cookie -> 401 | `GET` | `{{ruuter}}/efti/api/v1/test/piiratud` |  |
| Probes / edelivery /metrics | `GET` | `{{edelivery}}/metrics` |  |
| Scheduled jobs / ops expire-identifiers (dry run) | `POST` | `{{ruuter}}/ops/v1/expire-identifiers` | Safe parameters: they cannot touch the seed data. CronManager normally calls this with an empty body (the route defaults). |
| Scheduled jobs / ops archive-consignments (older than 10 years) | `POST` | `{{ruuter}}/ops/v1/archive-consignments` | Safe parameters: they cannot touch the seed data. CronManager normally calls this with an empty body (the route defaults). |
| Scheduled jobs / ops purge-archive (keep 100 years) | `POST` | `{{ruuter}}/ops/v1/purge-archive` | Safe parameters: they cannot touch the seed data. CronManager normally calls this with an empty body (the route defaults). |
| Scheduled jobs / ops purge-async-responses (keep 10 years) | `POST` | `{{ruuter}}/ops/v1/purge-async-responses` | Safe parameters: they cannot touch the seed data. CronManager normally calls this with an empty body (the route defaults). |
| Scheduled jobs / ops purge-inbound-messages (keep 10 years) | `POST` | `{{ruuter}}/ops/v1/purge-inbound-messages` | Safe parameters: they cannot touch the seed data. CronManager normally calls this with an empty body (the route defaults). |
| Scheduled jobs / ops purge-search-results (keep 10 years) | `POST` | `{{ruuter}}/ops/v1/purge-search-results` | Safe parameters: they cannot touch the seed data. CronManager normally calls this with an empty body (the route defaults). |
| Scheduled jobs / ops refresh-read-models | `POST` | `{{ruuter}}/ops/v1/refresh-read-models` | Safe parameters: they cannot touch the seed data. CronManager normally calls this with an empty body (the route defaults). |
| Scheduled jobs / ops - no token -> 401 | `POST` | `{{ruuter}}/ops/v1/purge-search-results` |  |
| Scheduled jobs / ops - wrong type -> 400 | `POST` | `{{ruuter}}/ops/v1/expire-identifiers` |  |

## 8. Developer mock (ruuter-xroad-mock)

The public X-Road developer mock: same headers as the real API, canned answers, no database.

| Request | Method | URL | Note |
|---|---|---|---|
| mock echo | `POST` | `{{devmock}}/developer/v1/echo` |  |
| mock subsets | `GET` | `{{devmock}}/developer/v1/subsets` |  |
| mock search MOCK-PLATE-3 | `POST` | `{{devmock}}/developer/v1/search` |  |
| mock transport-means MOCK-PLATE-1 | `POST` | `{{devmock}}/developer/v1/transport-means` |  |
| mock transport-means allgates -> 501 | `POST` | `{{devmock}}/developer/v1/transport-means` |  |
| mock dataset | `POST` | `{{devmock}}/developer/v1/dataset` |  |
| mock follow-up | `POST` | `{{devmock}}/developer/v1/follow-up` |  |
| mock - no X-Road-Client -> 401 | `POST` | `{{devmock}}/developer/v1/echo` |  |

## 9. Gate internals

Direct calls to the services behind ruuter, for debugging. Internal credentials: the internal service token and the ReSql token (dev values).

| Request | Method | URL | Note |
|---|---|---|---|
| registry gates.json holds the peer gate | `GET` | `{{registry}}/gates.json` |  |
| registry gates/<id>.json in upper case -> 404 | `GET` | `{{registry}}/gates/{{peer_gate}}.json` | The registry serves ids in lower case only. |
| resql get_db_status | `POST` | `{{resql}}/efti/get_db_status` |  |
| resql - no token -> 401 | `POST` | `{{resql}}/efti/get_db_status` |  |
| xml-mapper search/request-to-xml (criteria -> FTI019) | `POST` | `{{xml_mapper}}/api/v1/search/request-to-xml` |  |
| xml-mapper search/request-to-json (FTI019 -> criteria) | `POST` | `{{xml_mapper}}/api/v1/search/request-to-json` |  |
| xml-mapper dataset/request-to-xml (-> FTI009) | `POST` | `{{xml_mapper}}/api/v1/dataset/request-to-xml` |  |
| xml-mapper upload/request-to-json (FTI004 -> row) | `POST` | `{{xml_mapper}}/api/v1/upload/request-to-json` |  |
| xml-mapper - no token -> 401 | `POST` | `{{xml_mapper}}/api/v1/search/request-to-xml` |  |

## Known issues (separate collection)

`local/postman/eFTI-Gate-known-issues.postman_collection.json`. Requests that show a known defect (the book: Reference > Known issues, H1). They fail on purpose and are not part of the newman service. Use the same environment as the main collection.

| Request | Method | URL | Note |
|---|---|---|---|
| ping - 204 with content-length: 2 | `POST` | `{{ruuter}}/efti/api/v1/ping` | KNOWN ISSUE, fails in Postman/Newman. Ruuter answers `204 No Content` with `content-length: 2` and no body (the route returns `""`). curl shows 204; Postman's client waits for the 2 bytes and times out (keep-alive) or aborts (`Connection: close`, set here so it fails at once). |
| admin - create a test user | `POST` | `{{ruuter}}/admin/v1/users` | Creates a user with a random valid Estonian personal code. Works (201); it is here because the two clean-up requests after it fail in Postman. |
| admin - revoke the test user's tokens (204 issue) | `POST` | `{{ruuter}}/admin/v1/users/revoke-token/{{test_user_id}}` | KNOWN ISSUE: 204 with content-length: 2. |
| admin - delete the test user (204 issue) | `DELETE` | `{{ruuter}}/admin/v1/users/{{test_user_id}}` | KNOWN ISSUE: 204 with content-length: 2. The user is soft-deleted even though Postman reports an error. |
