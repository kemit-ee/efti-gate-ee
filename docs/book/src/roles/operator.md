# Operator

> **Goal:** you can tell that a gate is healthy, find why a request failed, keep the scheduled jobs
> running, and know which runbook to follow when something breaks.

## Your role

You run a deployed gate. You watch its probes, metrics and logs, make sure CronManager's housekeeping
jobs run, handle certificate expiry and peer problems, and escalate what you cannot fix.
[Deployer](./deployer.md) covers installation; this page is for the days after.

## Path

1. **Know what to probe:** [Health and diagnostics](../api/operations/health.md).
2. **See it done:** [Operations: health and scheduled jobs](../use-cases/operations.md) checks a gate,
   traces a failed request through the logs, and runs a job by hand.
3. **Know the jobs:** [Scheduled jobs](../api/operations/jobs.md) and the schedules on
   [Operations](../api/operations/index.md#scheduled-jobs-cronmanager).
4. **Read the monitoring design:** [Logging and observability](../architecture/observability/logging_and_observability.md)
   and [Monitoring and alerting](../architecture/observability/monitoring_and_alerting.md).
5. **Keep the runbooks at hand** (below).
6. **Check [Known issues](../reference/known-issues.md)** before you chase a behaviour that is
   already recorded.

## Daily checks

| Check | How | Healthy |
|---|---|---|
| Ruuter live and ready | `GET /efti/health/live`, `/efti/health/ready` | `200`. Ready also tests the database |
| X-Road Ruuter | `GET /xroad/health/ready` | `200` |
| edelivery, xml-mapper | their health endpoints, and `/metrics` | `200` |
| ReSql | `/app/resql health --url http://127.0.0.1:8090/health` (distroless, no `curl`) | exits 0 |
| Registry | `GET /health.json` on the registry service | `status` ok, the counts you expect |
| Jobs ran | the `jobs_execution_log` table, or CronManager's log | `COMPLETED` rows, recent |
| Certificates | AS4 certificate `notAfter` | Not within your renewal window |

None of these endpoints is public. Reach them from inside the network or by port-forward. The UI
nginx does not proxy `/ops/` or the probes.

## Find why a request failed

1. Take the `x-trace-id` from the failing response (Ruuter) or the `audit_ref` (edelivery, on
   inbound AS4 errors).
2. Find that id in the Ruuter log. Each DSL step logs its name, duration and status, so the step
   that failed is visible. The error text names the chain of steps.
3. Follow the call to the next service with the same request id.
4. Match the status to the [Error catalogue](../data/errors.md).

The edelivery log never holds a message body or an exception message. It records a body fingerprint
and a fixed reason code. [Operations: health and scheduled jobs](../use-cases/operations.md) has three
worked examples, including a `404` on a dataset and a search across two gates.

## Scheduled jobs

CronManager calls Ruuter's `/ops/v1/*` routes with `Authorization: Bearer <ARCHIVE_OPS_TOKEN>`. The
jobs archive superseded consignments, purge short-lived state, refresh read models and expire road
identifiers after the cabotage period.

- **If a job group is missing from CronManager's boot log, nothing runs.** The usual cause is an unset
  `OPS_AUTHORIZATION` or a typo in the job file: CronManager skips the whole file with an error.
- **Every job is idempotent.** Archiving verifies in the archive database before it deletes anything,
  and deletes nothing on a mismatch.
- **Run one by hand** with the token, or through CronManager's own execute route
  ([Operations: run a scheduled job by hand](../use-cases/operations.md#run-a-scheduled-job-by-hand)).
- The `jobs_execution_log` is the only history. CronManager keeps none.

## Runbooks

The repository holds one runbook per failure type, all in the same format (symptoms, diagnosis,
resolution, escalation):
[`docs/runbooks/`](https://github.com/kemit-ee/efti-gate-ee/tree/dev/docs/runbooks).

| Scenario | Runbook |
|---|---|
| Database connectivity loss | [database-connectivity-loss](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/database-connectivity-loss.md) |
| Container crash loop | [pod-crash-loop](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/pod-crash-loop.md) |
| High error rate | [high-error-rate](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/high-error-rate.md) |
| Disk full | [disk-full](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/disk-full.md) |
| Certificate expiry (AS4, TIM, TARA) | [certificate-expiry](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/certificate-expiry.md) |
| AS4 communication failure | [edelivery-communication-failure](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/edelivery-communication-failure.md) |
| TARA login outage | [tara-authentication-outage](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/tara-authentication-outage.md) |
| X-Road service unavailable | [xtee-service-unavailable](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/xtee-service-unavailable.md) |
| Manual rollback | [manual-rollback](https://github.com/kemit-ee/efti-gate-ee/blob/dev/docs/runbooks/manual-rollback.md) |

Default escalation, until an on-call rotation exists: the gate operator first, then the
platform/infra owner (Kubernetes, database, secrets, certificates), then the gate maintainers
(DSL, AS4, X-Road adapter), and peer-gate operators only when a cross-gate problem needs their side.

## What monitoring does not cover yet

State this to whoever depends on alerts. As recorded in the runbooks index:

- Prometheus scrapes `edelivery` and `xml-mapper` only. Ruuter, ReSql, the registry and the
  PostgreSQL instances expose no Prometheus metrics.
- There is no node, container or database exporter, so no alert on restart loops, full disks or an
  unavailable database. The runbooks mark such steps "not yet available".
- Centralised log collection does not exist yet.
- Alertmanager has no notification receiver; alerts appear only in its UI.

Plan external probes and disk and database monitoring in your platform until these gaps close.

## Peer and partner problems

| Problem | First look |
|---|---|
| A peer gate rejects our messages | A certificate was rotated on one side only. [Peer gate](./peer-gate-operator.md#what-fails-when) |
| An authority gets `403` | Its registry entry: `registryCode`, `subsets`. [Change the registry](./registry-changes.md) |
| A platform gets `401` | Its key, `status`, and the registry build. [Freight platform](./platform-developer.md) |
| Searches for peer data return nothing | The peer is `DISABLED` or has no certificate, or an AS4 failure. [Peer gate](./peer-gate-operator.md) |
