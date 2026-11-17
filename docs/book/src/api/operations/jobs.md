# Scheduled jobs

The seven `/ops/v1/*` routes do the gate's housekeeping: archiving, the cabotage expiry, and the
purge of short-lived state. CronManager calls them on a timer (schedules on the
[Operations](./index.md#scheduled-jobs-cronmanager) page). An operator can call them by hand.

All seven share:

- `POST` on ruuter (G1 :27086, G2 :28086), source `DSL/Ruuter/ops/POST/v1/<name>.yml`;
- the ops guard: `Authorization: Bearer <ARCHIVE_OPS_TOKEN>` (local value
  `dev-archive-ops-token-change-me`, public in the repository);
- an optional JSON body. CronManager sends none, so the route defaults apply;
- one `jobs_execution_log` row per run (`COMPLETED` or `FAILED`), except `expire-identifiers`
  with `dryRun: true`.

## Common request

| Header | Required | Value |
|---|---|---|
| `Authorization` | yes | `Bearer dev-archive-ops-token-change-me` (local) |
| `Content-Type` | with a body | `application/json` |

## Common responses

| Status | When | Body |
|---|---|---|
| 200 | the job ran | per job, see below |
| 400 | a body field has the wrong JSON type (Ruuter `allowlist` check) | `{"error":"Field type mismatch in body: <field> expected <type>, got <type>"}` |
| 400 | the body is not JSON | `{"error":"invalid JSON body: …"}` |
| 401 | `Authorization` missing, empty or wrong | problem+json, `code: UNAUTHORIZED` (see [Operations](./index.md#the-ops-guard)) |
| 502 | a ReSql step failed | problem object with a job-specific `code`; a `FAILED` row is written to `jobs_execution_log` |

Unknown body fields are ignored. A number given as a string (`"60"`) is a type mismatch. The
`502` body has `content-type: application/json` (the route sets no problem+json header).

## Safe values used on this page

The examples run against the local stack with values that cannot touch the seed data:
`expire-identifiers` with `dryRun: true`, `archive-consignments` with `olderThanDays: 3650`, and the
purges with keep windows of about ten years or more.

> **Warning.** There is no lower bound on any keep value. `keepMinutes: 0` or a negative value
> makes the `DELETE … WHERE created_at < now() - interval` cover every row of the table. The SQL
> only applies `COALESCE(:value, <default>)`.

## archive-consignments

Moves one batch of **superseded** `consignments` rows from the live database to the archive
database (`append_only_archival.md`). A row is a candidate only when a newer row of the same
`(platform_id, dataset_id)` exists **and** that newer row is older than `olderThanDays`. The current
row of a dataset, including a `DELETED` tombstone, never moves.

The four steps, all through ReSql:

1. `efti/select_archivable_consignments` picks the batch from the live DB.
2. `archive/insert_archived_consignments` copies it to the archive DB (`ON CONFLICT DO NOTHING`).
3. `archive/verify_archived_consignments` reads the rows back.
4. Only if every row is confirmed: `efti/delete_consignments` deletes exactly those rows.

One batch per call. Call again until `candidates` is `0`.

`POST /ops/v1/archive-consignments` · on ruuter (G1 :27086) · ops token · source `DSL/Ruuter/ops/POST/v1/archive-consignments.yml`

| Body field | Required | Type | Meaning |
|---|---|---|---|
| `olderThanDays` | no | number | age of the newer sibling, in days. Default `2` |
| `batchLimit` | no | number | rows per call. Default `100` |

| Status | When | Body |
|---|---|---|
| 200 | batch moved, or nothing to move | `{"candidates": n, "archived": n, "deleted": n}` |
| 502 | `ARCHIVE_VERIFICATION_FAILED` | the archive did not confirm every row; nothing was deleted |
| 502 | `ARCHIVE_SWEEP_FAILED` | the select, insert or delete step returned ≥ 400 |

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/archive-consignments \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d '{"olderThanDays":3650}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 4d7c04793221f7a2ca5f009a88ad706a
content-length: 41

{"archived":0,"candidates":0,"deleted":0}
```

Not run locally: a run that moves rows (it would archive seed rows), and both 502 cases.

## expire-identifiers

Cabotage expiry, Reg 2024/1942 Art 11(4). Every current road consignment (transport mode `3`,
latest row `ACTIVE`) whose carrier acceptance date is more than 14 days ago gets an `INACTIVE` copy
appended as its new latest row. Nothing is updated or deleted. At most 1000 per call; `more: true`
means call again.

`POST /ops/v1/expire-identifiers` · on ruuter (G1 :27086) · ops token · source `DSL/Ruuter/ops/POST/v1/expire-identifiers.yml`

| Body field | Required | Type | Meaning |
|---|---|---|---|
| `dryRun` | no | boolean | `true`: count only, write nothing (no `INACTIVE` rows, no job log row). Default `false` |

| Status | When | Body |
|---|---|---|
| 200 | the sweep ran | `{"candidates": n, "expired": n, "dryRun": bool, "more": bool}` (`more` = `candidates >= 1000`) |
| 400 | `dryRun` is not a boolean | type-mismatch error |
| 502 | `IDENTIFIER_EXPIRY_FAILED` | the ReSql step `expire_road_consignments` failed |

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/expire-identifiers \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d '{"dryRun":true}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: bfa63c2e3c46811299135501a3598aa3
content-length: 55

{"candidates":0,"dryRun":true,"expired":0,"more":false}
```

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/expire-identifiers \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d '{"dryRun":"yes"}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
x-trace-id: c20e0a5c4fa9d4f74cfbe096190be420
content-length: 76

{"error":"Field type mismatch in body: dryRun expected boolean, got string"}
```

Not run locally: `dryRun: false` (it would expire seed consignments once they are old enough).

## purge-archive

Cold-storage retention. Permanently deletes rows of the **archive** database older than
`keepDays` (by `archived_at`). The live database is not touched.

`POST /ops/v1/purge-archive` · on ruuter (G1 :27086) · ops token · source `DSL/Ruuter/ops/POST/v1/purge-archive.yml`

| Body field | Required | Type | Meaning |
|---|---|---|---|
| `keepDays` | no | number | retention in days. Default `2555` (about 7 years) |

| Status | When | Body |
|---|---|---|
| 200 | purge ran | `{"purged": n}` |
| 502 | `ARCHIVE_PURGE_FAILED` | `archive/purge_archived_consignments` failed |

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/purge-archive \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d '{"keepDays":36500}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 2b6f8eae2a68a8ec2a0545fae1a118b1
content-length: 12

{"purged":0}
```

A value PostgreSQL cannot turn into a date gives the 502. Here `keepDays: 1.5e9`:

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/purge-archive \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d '{"keepDays":1.5e9}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
x-trace-id: 58afb798cd810fe94cff8b996669e36a
content-length: 203

{"code":"ARCHIVE_PURGE_FAILED","detail":"The archive-DB purge step returned an error. See jobs_execution_log.","status":502,"title":"Archive purge failed","type":"https://api.efti.ee/errors/bad-gateway"}
```

The ReSql log line with the same trace id gives the cause:

```text
WARN http_request{http.request.method=POST http.route=/archive/purge_archived_consignments resql.project=archive trace_id=58afb798cd810fe94cff8b996669e36a}: resql::error: SQL execution failed: error returned from database: timestamp out of range error.kind=BadSqlGrammarException http.response.status_code=400
```

## purge-async-responses

Deletes AS4 hand-off rows (`async_responses`) older than `keepMinutes`. They are useless once the
authority call that waited for them has ended.

`POST /ops/v1/purge-async-responses` · on ruuter (G1 :27086) · ops token · source `DSL/Ruuter/ops/POST/v1/purge-async-responses.yml`

| Body field | Required | Type | Meaning |
|---|---|---|---|
| `keepMinutes` | no | number | retention in minutes. Default `10` |

| Status | When | Body |
|---|---|---|
| 200 | purge ran | `{"purged": n}` |
| 502 | `ASYNC_RESPONSES_PURGE_FAILED` | `efti/delete_expired_async_responses` failed |

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/purge-async-responses \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d '{"keepMinutes":5256000}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: fc9a89086e6e7dc138f8b7f0132d24db
content-length: 12

{"purged":0}
```

## purge-inbound-messages

Deletes inbound AS4 duplicate-elimination rows (`inbound_messages`, #336) older than
`keepHours`. A peer stops retrying long before that.

`POST /ops/v1/purge-inbound-messages` · on ruuter (G1 :27086) · ops token · source `DSL/Ruuter/ops/POST/v1/purge-inbound-messages.yml`

| Body field | Required | Type | Meaning |
|---|---|---|---|
| `keepHours` | no | number | retention in hours. Default `48` |

| Status | When | Body |
|---|---|---|
| 200 | purge ran | `{"purged": n}` |
| 502 | `INBOUND_MESSAGES_PURGE_FAILED` | ReSql unreachable or `efti/delete_expired_inbound_messages` failed |

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/purge-inbound-messages \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d '{"keepHours":87600}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 17ff9fd9d7c193bdc09272f7cc5e3cc3
content-length: 12

{"purged":0}
```

## purge-search-results

Deletes cross-gate search state (`search_results`, ADR-013) older than `keepMinutes`. A caller's
polling window is long over by then.

`POST /ops/v1/purge-search-results` · on ruuter (G1 :27086) · ops token · source `DSL/Ruuter/ops/POST/v1/purge-search-results.yml`

| Body field | Required | Type | Meaning |
|---|---|---|---|
| `keepMinutes` | no | number | retention in minutes. Default `10` |

| Status | When | Body |
|---|---|---|
| 200 | purge ran | `{"purged": n}` |
| 400 | `keepMinutes` not a number | type-mismatch error |
| 502 | `SEARCH_PURGE_FAILED` | `efti/delete_expired_search_results` failed |

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/purge-search-results \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d '{"keepMinutes":5256000}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: eeee77ce901edd4a6803b47af093013f
content-length: 12

{"purged":0}
```

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/purge-search-results \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d '{"keepMinutes":"60"}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
x-trace-id: 7e751985824ae5a7d17f01acc2284632
content-length: 80

{"error":"Field type mismatch in body: keepMinutes expected number, got string"}
```

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/purge-search-results \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d 'not-json'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 64

{"error":"invalid JSON body: expected ident at line 1 column 2"}
```

## refresh-read-models

ADR-012 read-model refresh. Writes a new full snapshot generation of `rm_consignment_counts`
(`efti/refresh_consignment_counts`) and of the authority consignment summary
(`efti/refresh_consignment_summary`), each published in the same SQL statement as its snapshot. An
advisory lock serialises overlapping runs. Then `efti/purge_read_model_generations` deletes
generations older than `keepGenerations`. The source tables stay canonical. The admin counts
([Consignments](../admin/consignments.md)) are at most one interval (15 min) stale.

`POST /ops/v1/refresh-read-models` · on ruuter (G1 :27086) · ops token · source `DSL/Ruuter/ops/POST/v1/refresh-read-models.yml`

| Body field | Required | Type | Meaning |
|---|---|---|---|
| `keepGenerations` | no | number | newest published generations to keep. Default `2`, minimum `1` (`GREATEST(…, 1)`); the current one is never removed |

| Status | When | Body |
|---|---|---|
| 200 | new generation published | `{"generation": n, "rows": n, "purgedRows": n}` (`rows` and `generation` are from the counts model) |
| 502 | `READ_MODEL_REFRESH_FAILED` | a snapshot step failed (the previous generation stays current), or the purge failed after publishing |

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/refresh-read-models \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
  -H 'Content-Type: application/json' -d '{"keepGenerations":1000}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 8580a81c6b04e2e6262940eb2025f167
content-length: 40

{"generation":1,"purgedRows":0,"rows":1}
```

Without a body, as CronManager calls it:

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/refresh-read-models \
  -H 'Authorization: Bearer dev-archive-ops-token-change-me'
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: ff76d9baded22d31d1cb44054c96a0db
content-length: 40

{"generation":3,"purgedRows":0,"rows":1}
```

## The job log

Each run appends a row. Read it with psql (password `01234`):

```bash
docker exec efti-g1-database psql -U efti efti -c \
  "select job_name,status,details,started_at,finished_at from jobs_execution_log order by finished_at desc limit 6"
```

```text
        job_name        |  status   |                       details                        |          started_at           |          finished_at
------------------------+-----------+------------------------------------------------------+-------------------------------+-------------------------------
 refresh-read-models    | COMPLETED | {"rows": 1, "generation": 3, "purgedRows": 0}        | 2026-11-03 16:00:02.834719+00 | 2026-11-03 16:00:02.834719+00
 purge-archive          | FAILED    | {"reason": "the purge ReSql step returned an error"} | 2026-11-03 15:59:50.250792+00 | 2026-11-03 15:59:50.250792+00
 purge-search-results   | COMPLETED | {"purged": 0}                                        | 2026-11-03 15:59:50.212048+00 | 2026-11-03 15:59:50.212048+00
 refresh-read-models    | COMPLETED | {"rows": 1, "generation": 1, "purgedRows": 0}        | 2026-11-03 15:59:50.161589+00 | 2026-11-03 15:59:50.161589+00
 purge-search-results   | COMPLETED | {"purged": 0}                                        | 2026-11-03 15:59:50.111044+00 | 2026-11-03 15:59:50.111044+00
 purge-inbound-messages | COMPLETED | {"purged": 0}                                        | 2026-11-03 15:59:50.08672+00  | 2026-11-03 15:59:50.08672+00
(6 rows)
```

## Notes

- `started_at` equals `finished_at`: the routes do not pass `startedAt`, so the SQL uses `now()`
  for both. The row records when the job ended, not how long it ran.
- The `declaration.returns` of `refresh-read-models` says `generation` is a string; the route
  returns a number.
- The comment block in `DSL/CronManager/efti-ops.yaml` lists the defaults but leaves out
  `keepHours=48` for `purge-inbound-messages`.
- `docs/specs/api_endpoints.md` §9.4 lists `POST /api/v1/admin/archive`; that route does not
  exist. The archive sweep is `POST /ops/v1/archive-consignments`.
- The purge statements are `DELETE`s run through the single `efti` ReSql datasource. The schema
  grants only `SELECT, INSERT` to the `app` role and `SELECT, DELETE` to `db_archiver`
  (`DSL/Liquibase/init.sql`). Locally ReSql connects as the table owner `efti`, so the deletes
  work. Not verified: which role a deployment gives ReSql, and whether the purges succeed under it.
