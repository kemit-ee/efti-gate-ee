# Operations

This part is for the people who run a gate: they check that it is up, they run the scheduled
clean-up jobs, and they read logs and metrics when something fails.

## How operators reach the gate

None of these endpoints is public. In a deployment they are reached from inside the container
network, or through a port-forward:

| What | Service | Path | Credential |
|---|---|---|---|
| Probes | ruuter, ruuter-xroad, edelivery, xml-mapper, resql, registry | `/efti/health/*`, `/xroad/health/ready`, `/health`, `/health.json` | none |
| DB diagnostic | ruuter | `/efti/api/v1/test/baasikontoroll` | none |
| Session probes | ruuter | `/efti/api/v1/test/lubatud`, `/test/piiratud` | optional `eftiJwtCookie` cookie |
| Scheduled jobs | ruuter | `/ops/v1/*` | `Authorization: Bearer <ARCHIVE_OPS_TOKEN>` |
| Metrics | edelivery, xml-mapper | `/metrics` | none |

`docker/ui/nginx.conf` does not proxy `/ops/` or the probes, so the public UI host does not
expose them. In the local stack all ports are bound to `127.0.0.1` (see
[Ports and credentials](../../reference/ports-and-credentials.md)).

## The ops guard

`DSL/Ruuter/ops/.guard.yml` protects every `/ops/**` route. It compares the `Authorization` header
literally with `Bearer [#ARCHIVE_OPS_TOKEN]`. There is no JWT path.

- A missing, empty or wrong header gets `401` with `application/problem+json`, and an
  `ops.auth_denied` row is written to `audit_log` in the background.
- If `ARCHIVE_OPS_TOKEN` is unset, the guard still denies: an empty header never matches.
- The local token is `dev-archive-ops-token-change-me`. It is a development value that is public
  in the repository.

```bash
curl -s -i -X POST http://127.0.0.1:27086/ops/v1/purge-search-results \
  -H 'Content-Type: application/json' -d '{}'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
x-trace-id: 5d5e5f3f89e733e335514b0a607058f7
content-length: 173

{"code":"UNAUTHORIZED","detail":"This endpoint requires a valid CronManager ops token.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

## Scheduled jobs (CronManager)

CronManager (`turnerrainer/cronmanager:0.2.3-alpha`, `docker/cronmanager/Dockerfile`) calls the
`/ops/v1/*` routes on a timer. The job file is `DSL/CronManager/efti-ops.yaml`; the group name is
`efti-ops`. Every job:

- is `POST http://ruuter:8086/ops/v1/<job>` with `Content-Type: application/json`;
- sends `Authorization` from the env var `OPS_AUTHORIZATION`, whose value is the whole header
  (`Bearer <ARCHIVE_OPS_TOKEN>`). If the variable is unset or empty, CronManager loads no job;
- sends an **empty body**, so each route uses its defaults. A different value is a change to the
  route default, not to the job file;
- retries after a failure (`retryCount`, `retryDelay` in ms). Every route is idempotent.

The trigger is a Quartz cron expression: seconds, minutes, hours, day of month, month, day of week.

| Job | Trigger | When | Endpoint | Retries |
|---|---|---|---|---|
| `efti_gate_archive_sweep` | `0 */10 2-5 * * ?` | every 10 min, 02:00–05:50 | [`/ops/v1/archive-consignments`](./jobs.md#archive-consignments) | 3 × 30 s |
| `efti_gate_archive_purge` | `0 0 4 * * ?` | daily 04:00 | [`/ops/v1/purge-archive`](./jobs.md#purge-archive) | 3 × 30 s |
| `efti_gate_expire_identifiers` | `0 45 * * * ?` | hourly at :45 | [`/ops/v1/expire-identifiers`](./jobs.md#expire-identifiers) | 3 × 30 s |
| `efti_gate_async_responses_purge` | `0 */5 * * * ?` | every 5 min | [`/ops/v1/purge-async-responses`](./jobs.md#purge-async-responses) | 3 × 10 s |
| `efti_gate_inbound_messages_purge` | `0 15 * * * ?` | hourly at :15 | [`/ops/v1/purge-inbound-messages`](./jobs.md#purge-inbound-messages) | 3 × 10 s |
| `efti_gate_search_results_purge` | `0 */5 * * * ?` | every 5 min | [`/ops/v1/purge-search-results`](./jobs.md#purge-search-results) | 3 × 10 s |
| `efti_gate_read_models_refresh` | `0 */15 * * * ?` | every 15 min | [`/ops/v1/refresh-read-models`](./jobs.md#refresh-read-models) | 2 × 30 s |

The outbound timeout is `limits.request_timeout_secs: 120` in `docker/cronmanager/cronmanager.yaml`.
CronManager's own admin API (`POST /execute/efti-ops/<job>` fires one job by hand) needs
`CRONMANAGER_ADMIN_TOKEN`.

**The ops CronManager is not part of the local two-gate stack.** None of these jobs runs on a timer
there (the optional `test-scheduler` only runs the Postman collection). Call the
endpoints by hand, as in [Run a scheduled job by hand](../../use-cases/operations.md#run-a-scheduled-job-by-hand).

Every job run writes one row to `jobs_execution_log` (job name, `COMPLETED` or `FAILED`, a
`details` JSON). A `dryRun` of `expire-identifiers` writes none.

## Logs

| Service | Format | Correlation field |
|---|---|---|
| ruuter, ruuter-xroad | text, one line per DSL step, `[t=<first 8 hex of the trace id> <project>]` | `x-trace-id` / `traceparent` |
| resql | text (`tracing`), `trace_id=<32 hex>` | the caller's `traceparent` |
| edelivery, xml-mapper | JSON, `"trace.id"` | the `X-Request-Id` header |
| registry | nginx access log | none |

How to follow one request through these logs is shown in
[Find why a request failed](../../use-cases/operations.md#find-why-a-request-failed).
The target log format (fields, levels, redaction, retention) is in `docs/specs/logging-spec.md`.

## Metrics

Only edelivery and xml-mapper expose a Prometheus endpoint (`GET /metrics`, OpenMetrics text, no
auth; `code/core/src/metrics/RequestMetrics.kt`). Ruuter, ReSql and the registry have none.

- Scrape config: `docker/prometheus/prometheus.yml` (jobs `edelivery`, `xml-mapper`, every 15 s).
- Alert rules: `docker/prometheus/alert-rules.yml` — `ServiceUnreachable` (`up == 0` for 2 min),
  `HighErrorRate` (4xx+5xx above 5 % for 5 min), `NoOnlineEdeliveryPeers`
  (`edelivery_parties_online_total == 0` for 5 min).
- Alertmanager: `docker/alertmanager/alertmanager.yml`; Grafana provisioning: `docker/grafana/`.
- Prometheus, Alertmanager and Grafana are in `compose.yml`, not in the local two-gate stack.

Sample output is on the [health page](./health.md#metrics).

## Endpoints

| Endpoint | Service (G1 port) | Auth | Page |
|---|---|---|---|
| `GET /efti/health/live` | ruuter (27086) | none | [Health](./health.md#get-eftihealthlive) |
| `GET /efti/health/ready` | ruuter (27086) | none | [Health](./health.md#get-eftihealthready) |
| `GET /health` | ruuter, ruuter-xroad, ruuter-xroad-mock | none | [Health](./health.md#ruuter-built-in-get-health) |
| `GET /xroad/health/ready` | ruuter-xroad (27087) | none | [Health](./health.md#get-xroadhealthready) |
| `GET /health`, `GET /metrics` | edelivery (27081), xml-mapper (27082) | none | [Health](./health.md#edelivery-and-xml-mapper) |
| `GET /services/msh` | edelivery (27081) | none | [Health](./health.md#edelivery-and-xml-mapper) |
| `GET /health` | resql (27090) | none | [Health](./health.md#resql) |
| `GET /health.json` | registry (27080) | none | [Health](./health.md#registry) |
| `GET /efti/api/v1/test/baasikontoroll` | ruuter (27086) | none | [Health](./health.md#get-eftiapiv1testbaasikontoroll) |
| `GET /efti/api/v1/test/lubatud` | ruuter (27086) | optional cookie | [Health](./health.md#get-eftiapiv1testlubatud) |
| `GET /efti/api/v1/test/piiratud` | ruuter (27086) | cookie | [Health](./health.md#get-eftiapiv1testpiiratud) |
| `POST /ops/v1/archive-consignments` | ruuter (27086) | ops token | [Jobs](./jobs.md#archive-consignments) |
| `POST /ops/v1/expire-identifiers` | ruuter (27086) | ops token | [Jobs](./jobs.md#expire-identifiers) |
| `POST /ops/v1/purge-archive` | ruuter (27086) | ops token | [Jobs](./jobs.md#purge-archive) |
| `POST /ops/v1/purge-async-responses` | ruuter (27086) | ops token | [Jobs](./jobs.md#purge-async-responses) |
| `POST /ops/v1/purge-inbound-messages` | ruuter (27086) | ops token | [Jobs](./jobs.md#purge-inbound-messages) |
| `POST /ops/v1/purge-search-results` | ruuter (27086) | ops token | [Jobs](./jobs.md#purge-search-results) |
| `POST /ops/v1/refresh-read-models` | ruuter (27086) | ops token | [Jobs](./jobs.md#refresh-read-models) |

## Typical sequence

1. Probe every service ([Health](./health.md)); `GET /efti/health/ready` and
   `GET /xroad/health/ready` also prove the database is reachable.
2. When a request failed, take its `x-trace-id` and `X-Request-Id` and follow them through the
   logs ([use case](../../use-cases/operations.md#find-why-a-request-failed)).
3. Run a job by hand when a timer did not fire, or to drain a backlog
   ([Jobs](./jobs.md)), then check `jobs_execution_log`.
