# Database connectivity loss

## Symptoms
- `GET /efti/api/v1/test/baasikontoroll` (public health check) returns a DB-down status, or
  `DSL/Ruuter/efti/GET/health/ready.yml` / `xroad/GET/health/ready.yml` return `503`.
- ReSql requests return non-2xx with `X-Resql-Error-Code` headers, or `[]` bodies where rows were expected.
- `ruuter`/`edelivery` fail to start (both `depends_on: database: service_healthy` at compose level).
- `resql` container's own healthcheck (`/app/resql health --url http://127.0.0.1:8090/health`) fails.

## Diagnosis
1. `docker compose ps database resql` — is the DB container `Up (healthy)`? Is `resql` `Up`?
2. `docker compose logs --tail=200 database` — look for `FATAL`, `out of memory`, `too many connections`,
   or a crash/restart.
3. `docker compose exec database pg_isready -U <user>` (or the Kubernetes equivalent `kubectl exec`).
4. Check connection count / pool exhaustion: `SELECT count(*) FROM pg_stat_activity;` — ReSql's pool
   config lives in `resql.yaml`.
5. Confirm it's the primary `database` (DB `efti`) and not `archive-database` (DB `efti_archive`,
   only touched by the archival/purge ops routes) or `tim-database` — they're independent instances
   (`docs/specs/deploy/resource-and-secrets-requirements.md` §4) and a failure in one does not imply the
   others are down.

## Resolution
1. If the container is simply down: `docker compose restart database` (or let Kubernetes reschedule the
   pod — there's no in-app failover, PostgreSQL 18 is a single instance per AGENTS.md).
2. If it's connection-pool exhaustion: identify and kill long-running/idle-in-transaction sessions
   (`SELECT pid, state, query, now() - query_start FROM pg_stat_activity ORDER BY 3 DESC;`), then
   `SELECT pg_terminate_backend(pid)` on the worst offenders.
3. If it's disk space on the DB volume, see [disk-full.md](disk-full.md).
4. After the DB recovers, restart `resql` if it didn't reconnect on its own
   (`docker compose restart resql`) — ReSql 0.1.0-alpha is known to desync a pooled connection after a
   failed query; a stuck "invalid byte sequence 0x00" error on queries that
   should succeed is that symptom, not a DB outage — restart `resql`, not the database, in that case.
5. Re-check `GET /efti/api/v1/test/baasikontoroll` and the `ready` probes before declaring resolved.

## Escalation
- Ops engineer handles restarts and connection triage.
- Escalate to the platform/infra owner if the volume is corrupted, out of disk, or the managed Postgres
  instance itself is impaired (cloud provider incident).
- Escalate to gate maintainers if `resql` keeps desyncing after restart (points at a query/data issue,
  not infra).

## Dashboards / queries
- Not yet available for the database itself — there's no `postgres_exporter`, so Prometheus/Grafana have
  no DB-level metrics (see `docs/architecture/observability/monitoring_and_alerting.md`). The "Service up"
  / error-rate panels on the Grafana dashboard will show `edelivery`/`xml-mapper` degrading as a
  *symptom* of this, not the cause. Today: `docker compose logs database resql`, `pg_stat_activity`, and
  the health endpoints above are the direct signal.
