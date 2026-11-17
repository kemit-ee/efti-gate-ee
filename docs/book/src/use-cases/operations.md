# Operations: health and scheduled jobs

Three things an operator does: check that a gate is healthy, find out why a request failed, and run
a scheduled job by hand.

Actors: the operator (shell on the host or in the cluster), the gate's containers, and — in a
deployment — CronManager and Prometheus. Reference pages: [Operations](../api/operations/index.md),
[Health and diagnostics](../api/operations/health.md), [Scheduled jobs](../api/operations/jobs.md).

## Check that a gate is healthy

```mermaid
flowchart LR
  op[operator]
  op -- "GET /efti/health/ready" --> ruuter
  ruuter -- "POST /efti/get_db_status" --> resql
  resql --> postgres
  op -- "GET /xroad/health/ready" --> rx["ruuter-xroad"]
  rx --> resql
  op -- "GET /health" --> svc["edelivery, xml-mapper, resql"]
  op -- "GET /health.json" --> registry
  op -- "GET /metrics" --> metrics["edelivery, xml-mapper"]
```

1. Probe every service of both gates:

   ```bash
   for g in "G1 27" "G2 28"; do
     set -- $g
     for u in "$2086/efti/health/ready" "$2087/xroad/health/ready" "$2081/health" \
              "$2082/health" "$2090/health" "$2080/health.json"; do
       printf '%-6s %-28s %s\n' "$1" "$u" \
         "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:$u)"
     done
   done
   ```

   ```text
   G1  27086/efti/health/ready      200
   G1  27087/xroad/health/ready     200
   G1  27081/health                 200
   G1  27082/health                 200
   G1  27090/health                 200
   G1  27080/health.json            200
   G2  28086/efti/health/ready      200
   G2  28087/xroad/health/ready     200
   G2  28081/health                 200
   G2  28082/health                 200
   G2  28090/health                 200
   G2  28080/health.json            200
   ```

   `/efti/health/ready` and `/xroad/health/ready` answer `503 "Database unavailable"` when the
   database cannot be reached. The other probes only show that the process runs.

2. Check the registry content and the AS4 peers:

   ```bash
   curl -s http://127.0.0.1:27080/health.json
   curl -s http://127.0.0.1:27081/metrics | grep parties_online
   ```

   ```json
   {
     "status": "ok",
     "gates": 2,
     "platforms": 2,
     "authorities": 10,
     "trustViewGeneration": "sha256:234763487465da2967dbd0518722fe11d969b1142052cd9edd2c42eb77087b84"
   }
   ```

   ```text
   edelivery_parties_online_total 3
   ```

   `0` here fires the `NoOnlineEdeliveryPeers` alert in a deployment: edelivery has no one to talk to.

3. Prove the full path end to end with one cheap call per gate, e.g. an authority search for a
   local identifier ([Core authority API](../api/internal/core-authority.md#local-hit-tst-001-on-g1)),
   or run the Postman folder **0. Health** ([Verify with Postman](../getting-started/postman.md)).

## Find why a request failed

Every answer from Ruuter has an `x-trace-id` header. Keep it, and the `X-Request-Id` you sent.

| Where | What to grep | Example |
|---|---|---|
| ruuter / ruuter-xroad log | `t=` + the first 8 hex of `x-trace-id` | `t=ca3fcecc` |
| resql log | `trace_id=` + the full `x-trace-id` (Ruuter forwards `traceparent`) | `trace_id=58afb798cd810fe94cff8b996669e36a` |
| edelivery and xml-mapper logs, **both gates** | the `X-Request-Id` (`"trace.id"`) | `1a72acca-31ca-436b-ab4c-73241d456b78` |

You can also choose the trace id yourself: Ruuter takes an incoming `traceparent`
(`-H 'traceparent: 00-0123456789abcdef0123456789abcdef-0123456789abcdef-01'` gave
`x-trace-id: 0123456789abcdef0123456789abcdef`).

### Example 1: a 404 on a dataset request

The request asked G1 for a dataset on a platform `nope`:

```http
HTTP/1.1 404 Not Found
content-type: application/json
x-trace-id: ca3fcecc5b19e416f017de3f88c502e2
content-length: 59

{"error":"Platform lookup failed (data layer unavailable)"}
```

The Ruuter log shows each DSL step:

```bash
docker logs --since 1h efti-g1-ruuter 2>&1 | grep -F 't=ca3fcecc'
```

```text
16:03:27.730 INFO  [t=ca3fcecc efti] ▸ setup (assign) 706µs → check_gate  keys="requestId"
16:03:27.731 INFO  [t=ca3fcecc efti] ▸ check_gate (switch) 729µs → -  condition=no_match
…
16:03:27.735 INFO  [t=ca3fcecc efti] ▸ get_platform (http) 1.2ms → -  method="GET" url="http://registry:8080/platforms/nope.json" status=404
16:03:27.736 INFO  [t=ca3fcecc efti] ▸ check_platform (switch) 914µs → platform_lookup_failed  condition=0 expr="${platform_result.response.status == 0 || platform_result.response.status >= 300}"
16:03:27.736 INFO  [t=ca3fcecc efti] ▸ platform_lookup_failed (return) 13µs → -  status=502 wrapper=false body={"error":"Platform lookup failed (data layer unavailable)"}
16:03:27.736 INFO  [t=ca3fcecc efti] ▸ call_local_platform (template) 5.2ms → check_local_result  dsl="POST/api/v1/dataset-local" status=502 body={"error":"Platform lookup failed (data layer unavailable)"}
16:03:27.738 INFO  [t=ca3fcecc efti] ▸ local_platform_error (return) 1.0ms → -  status=404 wrapper=false body={"error":"Platform lookup failed (data layer unavailable)"}
16:03:27.738 INFO  [t=ca3fcecc efti] ⏹ POST /efti/api/v1/authority/dataset 404 11.9ms  from 172.24.0.1
```

The cause is the registry's `404` for `platforms/nope.json`: the platform id is unknown, not the
data layer.

### Example 2: a 502 from a job

`POST /ops/v1/purge-archive` with `keepDays: 1.5e9` answered `502 ARCHIVE_PURGE_FAILED` with
`x-trace-id: 58afb798cd810fe94cff8b996669e36a`. ReSql logs the SQL error under the same id:

```bash
docker logs --since 1h efti-g1-resql 2>&1 | grep -F 58afb798cd810fe94cff8b996669e36a
```

```text
2026-11-03T15:59:50.248882Z  WARN http_request{http.request.method=POST http.route=/archive/purge_archived_consignments resql.project=archive trace_id=58afb798cd810fe94cff8b996669e36a}: resql::error: SQL execution failed: error returned from database: timestamp out of range error.kind=BadSqlGrammarException http.response.status_code=400
2026-11-03T15:59:50.251872Z  INFO http_request{http.request.method=POST http.route=/efti/insert_job_execution_log resql.project=efti trace_id=58afb798cd810fe94cff8b996669e36a}: resql::server: http request completed http.request.method=POST http.route=/efti/insert_job_execution_log http.response.status_code=200 duration_ms=1.42634 resql.project=efti trace_id=58afb798cd810fe94cff8b996669e36a
```

The second line is the `FAILED` row in `jobs_execution_log`.

### Example 3: a search across both gates

```mermaid
flowchart TD
  auth[authority] --> eer["G1 ruuter authority/search (X-Request-Id R)"]
  eer -- "trace.id = R" --> eexm["G1 xml-mapper search/request-to-xml"]
  eer -- "detached" --> eeed["G1 edelivery send/G2"]
  eeed -- "AS4" --> lved["G2 edelivery /services/msh (trace.id = R)"]
  lved --> lvxm1["G2 xml-mapper request-to-json"]
  lved --> lvru["G2 ruuter consignments/search-xml"]
  lved --> lvxm2["G2 xml-mapper response-to-xml"]
  lved -- "AS4 FTI021" --> eeed
  eer -- "trace.id = R" --> eexm2["G1 xml-mapper search/response-to-json"]
```

One `X-Request-Id` follows the request through both gates:

```bash
R=1a72acca-31ca-436b-ab4c-73241d456b78
for c in efti-g1-edelivery efti-g1-xml-mapper efti-g2-edelivery efti-g2-xml-mapper; do
  echo "== $c"; docker logs --since 1h $c 2>&1 | grep -F "$R"
done
```

```text
== efti-g1-edelivery
{"@timestamp":"2026-11-03T16:02:44.414Z","trace.id":"1a72acca-31ca-436b-ab4c-73241d456b78","log.level":"info","log.logger":"edelivery.EDeliveryClient","message":"Sending message to http://g2-edelivery:8081/services/msh: conversationId=G2:1a72acca-31ca-436b-ab4c-73241d456b78:G1 payload_size_bytes=1134",…}
{"@timestamp":"2026-11-03T16:02:44.427Z",…,"message":"eDelivery accepted message to http://g2-edelivery:8081/services/msh: status=200, conversationId=G2:1a72acca-31ca-436b-ab4c-73241d456b78:G1",…}
{"@timestamp":"2026-11-03T16:02:44.460Z",…,"log.logger":"edelivery.EDeliveryRoutes","message":"Handling 'FTI021SearchIdentifierResponse' from G2:1a72acca-31ca-436b-ab4c-73241d456b78:G1",…}
== efti-g1-xml-mapper
{"@timestamp":"2026-11-03T16:02:44.394Z","trace.id":"1a72acca-31ca-436b-ab4c-73241d456b78",…,"message":"172.24.0.7 POST /api/v1/search/request-to-xml: 200 in 1 ms - null",…}
{"@timestamp":"2026-11-03T16:02:44.475Z","trace.id":"1a72acca-31ca-436b-ab4c-73241d456b78",…,"message":"172.24.0.7 POST /api/v1/search/response-to-json: 200 in 1 ms - null",…}
== efti-g2-edelivery
{"@timestamp":"2026-11-03T16:02:44.424Z","trace.id":"1a72acca-31ca-436b-ab4c-73241d456b78",…,"message":"Handling 'FTI019SearchIdentifierRequest' from G1:1a72acca-31ca-436b-ab4c-73241d456b78:G2",…}
{"@timestamp":"2026-11-03T16:02:44.447Z","trace.id":"1a72acca-31ca-436b-ab4c-73241d456b78handler",…,"message":"POST http://ruuter:8086/efti/api/v1/consignments/search-xml?gateId=G2 in 20ms - 200",…}
== efti-g2-xml-mapper
{"@timestamp":"2026-11-03T16:02:44.433Z","trace.id":"1a72acca-31ca-436b-ab4c-73241d456b78",…,"message":"172.25.0.7 POST /api/v1/search/request-to-json: 200 in 1 ms - null",…}
{"@timestamp":"2026-11-03T16:02:44.444Z","trace.id":"1a72acca-31ca-436b-ab4c-73241d456b78",…,"message":"172.25.0.7 POST /api/v1/search/response-to-xml: 200 in 1 ms - null",…}
```

The Ruuter and ReSql lines of the first call carry the trace id instead
(`t=65244ed5`, `trace_id=65244ed562ea4e2a9dbe23ab1fc27606`):

```text
16:02:44.387 INFO  [t=65244ed5 efti] ▸ local_search (http) 3.4ms → -  method="POST" url="http://resql:8090/efti/get_consignments" status=200
16:02:44.394 INFO  [t=65244ed5 efti] ▸ get_peer_gates (http) 447µs → check_gates  method="GET" url="http://registry:8080/gates.json" status=200
16:02:44.401 INFO  [t=65244ed5 efti] ▸ register_pending (http) 2.7ms → check_pending  method="POST" url="http://resql:8090/efti/insert_search_pending" status=200
16:02:44.403 INFO  [t=65244ed5 efti] ▸ start_broadcast (detach) 66µs → respond_pending  steps=5 detached=true
16:02:44.405 INFO  [t=65244ed5 efti] ⏹ POST /efti/api/v1/authority/search 200 29.4ms  from 172.24.0.1
```

For AS4 failures, the reason codes are in the edelivery logs; see
[Troubleshooting](../reference/troubleshooting.md#as4-between-the-gates-fails).

> **Do not paste raw Ruuter logs into tickets.** The step lines print guard expressions after
> constant substitution, so they contain the interpolated `INTERNAL_SERVICE_TOKEN` and, on a
> successful ops call, the `ARCHIVE_OPS_TOKEN`. Locally these are the public dev values; in a
> deployment they are secrets.

## Run a scheduled job by hand

CronManager is not part of the local stack, so the jobs never run there by themselves. In a
deployment you can fire one through CronManager (`POST /execute/efti-ops/<job>` with
`CRONMANAGER_ADMIN_TOKEN`) or call the route directly as below.

```mermaid
flowchart LR
  op[operator]
  op -- "POST /ops/v1/&lt;job&gt; (Bearer ARCHIVE_OPS_TOKEN)" --> ruuter
  ruuter --> resql["resql (efti / archive)"]
  ruuter --> log["resql insert_job_execution_log"]
```

1. Count first. The expiry job has a dry run:

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

2. Run the job. With no body it uses the same defaults as CronManager. Here a read-model refresh:

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

3. For a batch job, repeat until it reports nothing left: `archive-consignments` until
   `candidates` is `0`, `expire-identifiers` until `more` is `false`.

   ```bash
   curl -s -X POST http://127.0.0.1:27086/ops/v1/archive-consignments \
     -H 'Authorization: Bearer dev-archive-ops-token-change-me' \
     -H 'Content-Type: application/json' -d '{"olderThanDays":3650}'
   ```

   ```json
   {"archived":0,"candidates":0,"deleted":0}
   ```

4. Check the job log:

   ```bash
   docker exec efti-g1-database psql -U efti efti -c \
     "select job_name,status,details,finished_at from jobs_execution_log order by finished_at desc limit 3"
   ```

   ```text
         job_name       |  status   |                       details                        |          finished_at
   ---------------------+-----------+------------------------------------------------------+-------------------------------
    refresh-read-models | COMPLETED | {"rows": 1, "generation": 5, "purgedRows": 31}       | 2026-11-03 16:02:37.06082+00
    refresh-read-models | COMPLETED | {"rows": 1, "generation": 3, "purgedRows": 0}        | 2026-11-03 16:00:02.834719+00
    purge-archive       | FAILED    | {"reason": "the purge ReSql step returned an error"} | 2026-11-03 15:59:50.250792+00
   (3 rows)
   ```

   The newest row is a later refresh run by someone else on the same stack; generations count up
   across all callers.

   A `FAILED` row: take the `x-trace-id` of the call and look it up in the ReSql log (Example 2).

> **Careful with the purge values.** A keep window of `0` or a negative number deletes every row of
> the table. The local examples use about ten years (`keepMinutes: 5256000`, `keepHours: 87600`,
> `keepDays: 36500`). See [Scheduled jobs](../api/operations/jobs.md#safe-values-used-on-this-page).
