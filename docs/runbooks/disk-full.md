# Disk full

## Symptoms
- `database`/`archive-database` writes start failing, or PostgreSQL logs `could not write to file...no
  space left on device`.
- Docker volumes (`data`, `archive-data`, `tim-data`, `tim-jwt-key`, `tara-mock-vault`, `tim-ca-bundle`)
  approaching host/PV capacity.
- Container restarts with disk-pressure evictions (Kubernetes: `kubectl describe pod` shows
  `Evicted`/`DiskPressure`).

## Diagnosis
1. `docker system df -v` (or `df -h` on the host / PV) to see which volume is growing.
2. For the primary database, the biggest and fastest-growing table is almost always `consignments`
   (append-only, every "update" inserts a new row — see AGENTS.md database rule 1) and `search_results`
   (K4 cross-gate search state).
3. Check whether the scheduled maintenance jobs are actually running — they are **not** self-triggered;
   an external CronManager/Quartz scheduler must call them:
   - `POST /ops/v1/archive-consignments` — moves superseded `consignments` rows older than
     `olderThanDays` (default 2) to the separate `archive-database`.
   - `POST /ops/v1/purge-archive` — purges archived rows past `keepDays` (default 2555).
   - `POST /ops/v1/purge-search-results` — purges `search_results` rows past `keepMinutes` (default 10).
   - `POST /ops/v1/purge-async-responses` — purges drained `async_responses` rows.
   - `POST /ops/v1/refresh-read-models` → `purge_read_model_generations` — keeps only the latest
     `keepGenerations` (default 2) read-model snapshots.
   If CronManager has been down/misconfigured, these all stop running and every one of those tables
   grows unbounded.
4. Confirm it isn't `search_results`/`async_responses` specifically backing up because a consumer stopped
   polling (an orphaned `pending` row never reaches `complete` and never gets purged on the fast path).

## Resolution
1. Free immediate space: manually invoke the relevant `POST /ops/v1/*` route (with
   `Authorization: Bearer ARCHIVE_OPS_TOKEN`) if CronManager hasn't run recently — these are idempotent
   and safe to call ad hoc, one batch per call (re-invoke until `candidates` is 0 for the archive route).
2. Fix the scheduler if it's the root cause (CronManager/Quartz job down or misconfigured target URL).
3. If a specific volume (not the DB) is full — e.g. `tim-data` from session volume growth — check TIM's
   own retention config; it is a separate PostgreSQL instance/volume from the gate's own data
   (`docs/specs/deploy/resource-and-secrets-requirements.md` §4) and isn't touched by the `ops/v1` routes
   above.
4. As a last resort, expand the volume/PV — this buys time but does not fix a stuck scheduler.

## Escalation
- Ops engineer runs the purge/archive routes and checks CronManager status.
- Escalate to the platform/infra owner for volume/PV expansion or host-disk issues.
- Escalate to gate maintainers if a purge route itself is failing (logs `FAILED` on a mismatch between
  the live and archived row counts — see AGENTS.md database rule 3) rather than simply not being called.

## Dashboards / queries
- Not yet available — there's no `node_exporter` (or Kubernetes PV metrics) wired into Prometheus, so
  disk usage isn't graphed anywhere yet. Today: `docker system df -v`, `pg_database_size()`, and
  `SELECT count(*) FROM consignments` / `search_results` / `async_responses` are the only signal.
