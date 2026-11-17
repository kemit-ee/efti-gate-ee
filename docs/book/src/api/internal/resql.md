# ReSql endpoints

ReSql runs named SQL files over HTTP. Every gate database access goes through it: Ruuter has no
database driver, and edelivery uses it for AS4 bookkeeping. Each file under `DSL/Resql/<project>/POST/`
is one endpoint.

`POST /<project>/<name>` · on resql (G1 :27090, G2 :28090, inside `resql:8090`) · `Authorization: Bearer <RESQL_TOKEN>` · source `DSL/Resql/<project>/POST/<name>.sql`, config `resql.yaml`

## How it works

- Image `turnerrainer/resql:0.5.1-alpha` (`docker/resql/Dockerfile`); the SQL is copied to `/app/sql`.
- Two projects. `efti/*` runs on datasource `efti` (`database:5432/efti`, 75 connections);
  `archive/*` runs on datasource `archive` (`archive-database:5432/efti_archive`, 10 connections),
  mapped by `project_datasource_map`. A caller cannot pick the datasource
  (`allow_datasource_header: false`).
- Each file starts with a `/* description: … params: … */` header. `params` declares the named
  parameters (`:name` in the SQL), their type, `required` and `default`.
- Request: `POST`, JSON body with the parameters. Response: a JSON array of rows, column names in
  camelCase (`row_id` → `rowId`).
- Credential: `security.inter_service_token_env: RESQL_INTERNAL_TOKEN`. Local value
  `dev-resql-internal-token-change-me` (public in the repository). Ruuter sends it as
  `[#RESQL_TOKEN]`.
- Errors come as `400`/`401`/`405` with body `[]` and the cause in the headers
  `x-resql-error-code` and `x-resql-error-message`.
- Request limit 1 MiB, timeout 30 s. `GET /health` is public ([Health](../operations/health.md#resql)).

## The append-only rule

The live database is append-only for the application:

- The app role `app` (RDS: `efti_rw`) has only `SELECT, INSERT` on the tables
  (`DSL/Liquibase/init.sql`: `ALTER DEFAULT PRIVILEGES … GRANT SELECT, INSERT ON TABLES TO app`).
  `db_archiver` (RDS: `efti_ro`) has `SELECT, DELETE`.
- No SQL file contains an `UPDATE`. A change is a new row: `update_user`, `revoke_user_token`,
  `soft_delete_user`, `soft_delete_consignment`, `expire_road_consignments` all `INSERT` a copy
  with the changed columns.
- Readers take the **latest row** per logical key: `DISTINCT ON (id) … ORDER BY id, created_at
  DESC, revision DESC` (users), or a `NOT EXISTS` for a newer `(platform_id, dataset_id)` row
  (consignments, ADR-009).
- `DELETE` exists only for retention and archiving, called from the [ops jobs](../operations/jobs.md):
  `delete_consignments`, `delete_expired_async_responses`, `delete_expired_inbound_messages`,
  `delete_expired_search_results`, `purge_read_model_generations`, `archive/purge_archived_consignments`.

Locally ReSql connects as the table owner `efti` (`resql.yaml`, `username: "efti"`), so the role
limits do not apply in the two-gate stack.

## Catalogue

44 files in `efti/`, 4 in `archive/`. `*` = required. Callers are the DSL files that call the
endpoint (paths under `DSL/Ruuter/`, or `xroad:` for `DSL/Ruuter-xroad/`), found with
`grep -r "/efti/<name>"`; edelivery calls through `code/core/src/resql/ResqlClient.kt`.

| Endpoint | Kind | Purpose | Parameters | Called by |
|---|---|---|---|---|
| `efti/check_and_record_request_id` | INSERT | record a request id in `request_id_cache`; returns the row only when new, so `[]` means "seen before" (replay check) | `requestId`* | xroad: `provide/v1/dataset`, `provide/v1/follow-up` |
| `efti/check_tara_sub_exists` | SELECT | does an active user with this personal code exist (duplicate check) | `taraSub`* | `admin/POST/v1/users` |
| `efti/check_transport_means_registered` | SELECT | one boolean: does this gate hold an ACTIVE latest consignment carrying the identifier (main transport, used or carried equipment) | `transport_means_id`*, `country_code` | xroad: `provide/v1/transport-means` (scope `existence`) |
| `efti/check_user_auth` | SELECT | the user row if active and the token is not older than `token_revoked_at`; else `[]` | `tara_sub`*, `token_issued_at`* | `efti/POST/internal/check-admin-authority`, `check-user-authority` |
| `efti/claim_async_response` | INSERT | claim the stored AS4 response for a request key; exactly one caller wins | `requestKey`* | edelivery |
| `efti/delete_consignments` | DELETE | delete the given live rows, only if each still has a newer sibling | `rowIds` uuid[] | `ops/POST/v1/archive-consignments` |
| `efti/delete_expired_async_responses` | DELETE | delete `async_responses` older than `keepMinutes` | `keepMinutes` = 10 | `ops/POST/v1/purge-async-responses` |
| `efti/delete_expired_inbound_messages` | DELETE | delete `inbound_messages` older than `keepHours` | `keepHours` = 48 | `ops/POST/v1/purge-inbound-messages` |
| `efti/delete_expired_search_results` | DELETE | delete `search_results` older than `keepMinutes` | `keepMinutes` = 10 | `ops/POST/v1/purge-search-results` |
| `efti/expire_road_consignments` | INSERT | cabotage sweep: append `INACTIVE` for ≤ 1000 road consignments accepted > 14 days ago; `dryRun 'true'` only counts | `dryRun` string = `"false"` | `ops/POST/v1/expire-identifiers` |
| `efti/find_inbound_message` | SELECT | stored receipt and payload hash of an already accepted inbound AS4 message | `senderId`*, `messageId`* | edelivery |
| `efti/get_audit_log` | SELECT | one page of `audit_log`, filtered | `resource`, `resourceId`, `userId`, `from`, `to`, `limit` = 20 (≤ 1000), `offset` = 0 | `admin/GET/v1/audit` |
| `efti/get_consignment_by_id` | SELECT | current versions of a dataset, optionally narrowed by platform and gate (no `xml`) | `datasetId`*, `platformId`, `gateId` | `efti/GET/api/v1/status`, `platforms/POST/v1/consignments` |
| `efti/get_consignment_counts` | SELECT | ADR-012 read model: counts per gate, platform, status (latest generation) | `gateId`, `platformId`, `status`, `limit` = 100, `offset` = 0 | `admin/GET/v1/consignment-counts` |
| `efti/get_consignment_history` | SELECT | one page of the live versions of a dataset, newest first, no `xml` | `datasetId`*, `limit` = 100, `offset` = 0 | `admin/GET/v1/consignment-history` |
| `efti/get_consignment_summary` | SELECT | ADR-012 read model: counts per day and dimension, only for the given subsets | `subsets` string[], `from`, `to`, `dimension`, `limit` = 100, `offset` = 0 | `admin/GET/v1/consignment-summary` |
| `efti/get_consignment_xml` | SELECT | current identifier XML of one UIL | `datasetId`*, `gateId`*, `platformId`* | **none** |
| `efti/get_consignments` | SELECT | current consignments of a gate matching a criteria object (with `xml`) | `gateId`, `criteria` object, `limit` = 20, `offset` = 0 | `efti/POST/api/v1/authority/search`, `efti/POST/api/v1/consignments/search-xml`, `admin/GET/v1/consignments` |
| `efti/get_consignments_by_transport_means` | SELECT | curated identifier-level projection of the consignments carrying an identifier (no `xml`), case-sensitive | `transport_means_id`*, `country_code` | xroad: `provide/v1/transport-means` (scope `local`) |
| `efti/get_db_status` | SELECT | PostgreSQL version, start time, uptime | — | `efti/GET/health/ready`, `efti/GET/api/v1/test/baasikontoroll`, xroad: `GET/health/ready` |
| `efti/get_follow_up_messages` | SELECT | follow-up log rows of a dataset, optionally one follow-up id | `datasetId`*, `followUpId`, `limit` = 20, `offset` = 0 | `efti/GET/api/v1/follow-up` |
| `efti/get_follow_up_state` | SELECT | status of the latest log row of one follow-up (same query id, platform, dataset, content) | `queryId`*, `destinationPlatformId`*, `datasetRequestId`*, `contentJson`* | `efti/POST/api/v1/follow-up-local` |
| `efti/get_search_result` | SELECT | latest state (`pending`/`complete`) and body of a cross-gate search | `searchId`* | `efti/POST/api/v1/authority/search`, xroad: `provide/v1/search`, `provide/v1/transport-means` |
| `efti/get_user_by_id` | SELECT | latest row of a user | `userId`* | `admin/GET/v1/users`, `admin/PUT/v1/users`, `admin/POST/v1/users/revoke-token` |
| `efti/get_user_by_tara_sub` | SELECT | latest row of a user by personal code | `tara_sub`* | **none** |
| `efti/get_users` | SELECT | one page of users (latest rows) | `limit` = 20, `offset` = 0 | `admin/GET/v1/users` |
| `efti/insert_async_response` | INSERT | store an incoming AS4 response for the node waiting for it | `requestKey`*, `body`* | edelivery |
| `efti/insert_audit_log` | INSERT | one `audit_log` entry (best effort, after the action) | `action`*, `resource`*, `resourceId`, `userId`, `ipAddress`, `details` | every guard (`admin`, `efti/*/api/v1`, `authority`, `platforms`, `ops`, `auth/GET`, xroad), the admin routes, `auth/POST/*`, `authority/search` — 27 DSL files |
| `efti/insert_consignment` | INSERT | one consignment row from an upload (all identifier columns, `xml`, `status` = `ACTIVE`) | `datasetId`* uuid, `platformId`, `gateId`, `xml`, `status`, 21 criteria columns | `platforms/POST/v1/consignments` |
| `efti/insert_follow_up_log` | INSERT | one `follow_up_log` row; a `PENDING` repeat is skipped by the `follow_up_log_once` trigger | `requestingGateId`*, `datasetRequestId`*, `destinationGateId`*, `destinationPlatformId`*, `payload`*, `status` = `DELIVERED`, `followUpId`, `failureReason`, `queryId`, `contentHash`, `contentJson` | `efti/POST/api/v1/follow-up-local` |
| `efti/insert_inbound_message` | INSERT | record an accepted inbound AS4 message and its receipt; `[]` for a repeat | `senderId`*, `messageId`*, `payloadSha256`*, `receiptB64`* | edelivery |
| `efti/insert_job_execution_log` | INSERT | outcome of a scheduled job run | `jobName`*, `status` = `COMPLETED`, `startedAt`, `details` object | all seven `ops/POST/v1/*` |
| `efti/insert_search_complete` | INSERT | append the `complete` row with the aggregated result | `searchId`*, `body`* (JSON string) | `efti/POST/api/v1/authority/search`, xroad: `provide/v1/search`, `provide/v1/transport-means` |
| `efti/insert_search_pending` | INSERT | register a cross-gate search as `pending` | `searchId`* | `efti/POST/api/v1/authority/search` |
| `efti/insert_session_revocation` | INSERT | JWT denylist entry (`sessions`), `jti`/`exp` decoded from the payload segment | `user_id`*, `jwt_payload`*, `reason`* | **none** |
| `efti/insert_user` | INSERT | create a user | `taraSub`*, `name`* | `admin/POST/v1/users` |
| `efti/purge_read_model_generations` | DELETE | delete read-model generations older than the newest `keepGenerations` (min 1) | `keepGenerations` = 2 | `ops/POST/v1/refresh-read-models` |
| `efti/refresh_consignment_counts` | INSERT | new published generation of the counts read model (advisory lock) | — | `ops/POST/v1/refresh-read-models` |
| `efti/refresh_consignment_summary` | INSERT | new published generation of the summary read model (advisory lock) | — | `ops/POST/v1/refresh-read-models` |
| `efti/revoke_user_token` | INSERT | append a user row with `token_revoked_at = now()` | `userId`* | `admin/POST/v1/users/revoke-token` |
| `efti/select_archivable_consignments` | SELECT | one batch of superseded rows whose newer sibling is older than `olderThanDays` | `olderThanDays` = 2, `batchLimit` = 100 | `ops/POST/v1/archive-consignments` |
| `efti/soft_delete_consignment` | INSERT | append a `DELETED` tombstone for a dataset | `datasetId`*, `platformId`*, `gateId`* | `admin/DELETE/v1/consignments` |
| `efti/soft_delete_user` | INSERT | append an inactive user row | `userId`* | `admin/DELETE/v1/users` |
| `efti/update_user` | INSERT | append a user row with a new name/personal code; a changed code also revokes tokens | `id`*, `name`*, `taraSub`* | `admin/PUT/v1/users` |
| `archive/get_archived_consignments` | SELECT | one page of the archived versions of a dataset, no `xml` | `datasetId`*, `limit` = 100, `offset` = 0 | `admin/GET/v1/consignment-history` |
| `archive/insert_archived_consignments` | INSERT | bulk-copy rows into the archive (`ON CONFLICT (row_id) DO NOTHING`) | `rows` array | `ops/POST/v1/archive-consignments` |
| `archive/purge_archived_consignments` | DELETE | delete archive rows older than `keepDays` | `keepDays` = 2555 | `ops/POST/v1/purge-archive` |
| `archive/verify_archived_consignments` | SELECT | confirm the given row ids are in the archive and readable | `rowIds` uuid[] | `ops/POST/v1/archive-consignments` |

## Examples

All read-only. `A='Authorization: Bearer dev-resql-internal-token-change-me'`.

### Database status

```bash
curl -s -i -X POST http://127.0.0.1:27090/efti/get_db_status -H "$A" -H 'Content-Type: application/json' -d '{}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: c68d30390b16492b9f8bc6ac9ebd67cb
content-length: 201

[{"pgVersion":"PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit","startedAt":"2026-11-03T12:28:54.736200Z","uptime":"03:40:57.664012"}]
```

Without the token:

```http
HTTP/1.1 401 Unauthorized
content-type: application/json
x-resql-error-code: UnauthorizedException
x-resql-error-message: Authentication required
content-length: 2

[]
```

### A cross-gate search result

The search from [Core authority API](./core-authority.md#cross-gate-g2-ab123-exists-only-at-g2):

```bash
curl -s -i -X POST http://127.0.0.1:27090/efti/get_search_result -H "$A" -H 'Content-Type: application/json' \
  -d '{"searchId":"1a72acca-31ca-436b-ab4c-73241d456b78"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 826e33c250f04a5f8a9f26f46afb2bd5
content-length: 563
[{"status":"complete","body":[{"xml":"<ParameterIDSetCriteria xmlns:udt=\"urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34\"><MainCarriageTransportMeansIDParameterScope><ID>G2-AB123</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>","gateId":"G2","datasetId":"22222222-0000-4000-8000-000000000001","platformId":"mock","mainTransportId":"G2-AB123","usedEquipmentIds":[],"usedEquipmentSeq":[],"carriedEquipmentIds":[],"carriedEquipmentSeq":[],"usedEquipmentCountries":[],"usedEquipmentCategories":[],"carriedEquipmentCategories":[]}]}]
```

### Latest row wins

The test user of [Auth helpers](./auth-helpers.md) was created and then had its tokens revoked.
The table has two rows for the id:

```bash
docker exec efti-g1-database psql -U efti efti -c \
  "select row_id, tara_sub, token_revoked_at, is_active, created_at from users where id='2a70be0b-c95c-4ccf-af3e-45a0f48219e5' order by created_at"
```

```text
                row_id                |  tara_sub   |       token_revoked_at        | is_active |          created_at
--------------------------------------+-------------+-------------------------------+-----------+-------------------------------
 6ae95a5b-8265-4eab-92c8-b5efa50a0ab7 | 38505047904 |                               | t         | 2026-11-03 16:05:25.517537+00
 544e9d05-1065-450a-95ef-bb302aa78e67 | 38505047904 | 2026-11-03 16:05:35.816724+00 | t         | 2026-11-03 16:05:35.816724+00
(2 rows)
```

`get_user_by_id` returns only the latest:

```bash
curl -s -i -X POST http://127.0.0.1:27090/efti/get_user_by_id -H "$A" -H 'Content-Type: application/json' \
  -d '{"userId":"2a70be0b-c95c-4ccf-af3e-45a0f48219e5"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: b6a9573c0d9845c2b7ccc0466d76386d
content-length: 249

[{"rowId":"544e9d05-1065-450a-95ef-bb302aa78e67","id":"2a70be0b-c95c-4ccf-af3e-45a0f48219e5","taraSub":"38505047904","name":"Doc Writer E","tokenRevokedAt":"2026-11-03T16:05:35.816724Z","isUserActive":true,"createdAt":"2026-11-03T16:05:35.816724Z"}]
```

### Errors

| Request | Status | `x-resql-error-code` | `x-resql-error-message` |
|---|---|---|---|
| `get_consignment_by_id` with `{}` | 400 | `InvalidDataAccessApiUsageException` | `No value supplied for the SQL parameter 'datasetId': No value registered for key 'datasetId'` |
| `get_consignment_by_id` with `{"datasetId":"x"}` | 400 | `BadSqlGrammarException` | `SQL execution failed: error returned from database: invalid input syntax for type uuid: "x"` |
| `POST /efti/nope` | 400 | `ResqlRuntimeException` | `Saved query '/efti/nope' does not exist` |
| `GET /efti/get_db_status` | 405 | `MethodNotAllowedException` | `Method not allowed for '/efti/get_db_status' ? try POST` (plus `allow: POST`) |

Every error body is `[]`.

## Notes

- Three files have no caller in the DSL or the Kotlin code: `get_consignment_xml`,
  `get_user_by_tara_sub`, `insert_session_revocation`. The `sessions` denylist is therefore never
  written; logout relies on TIM's own denylist.
- `find_inbound_message`, `insert_inbound_message`, `insert_async_response` and
  `claim_async_response` are called only by Kotlin (`ResqlClient.kt`), not by the DSL.
- The DELETE statements run on the same `efti` datasource as everything else. Under the
  production role split (`app` = SELECT+INSERT) they need a role with DELETE. Not verified which
  role a deployment gives ReSql.
- ReSql puts database error texts in response headers. That is fine inside the gate; it is one more
  reason the port must stay internal.
- `docs/specs/db/schema.sql` and `DSL/Liquibase/` are the schema source.
