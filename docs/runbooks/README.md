# Operations runbooks

Runbooks for diagnosing and resolving the gate's most common failure scenarios. Each one follows the
same template so an on-call engineer can jump straight to the relevant section under time pressure.

**Current state note:** Prometheus + Grafana + Alertmanager are wired up (`docker/prometheus/`,
`docker/grafana/`, `docker/alertmanager/`, services added in `compose.yml`/`compose.override.yml`) and
scrape `edelivery`/`xml-mapper`'s klite-provided `/metrics` endpoint
(`code/core/src/metrics/RequestMetrics.kt`) — see
[`docs/architecture/observability/monitoring_and_alerting.md`](../architecture/observability/monitoring_and_alerting.md)
for what's covered. Locally: Grafana at `http://localhost:3000` (admin / `$GRAFANA_ADMIN_PASSWORD`,
default `admin`), Prometheus at `http://localhost:9090`, Alertmanager at `http://localhost:9093`
(loopback-only, like resql/registry). **Gaps that still apply to every runbook below:** Ruuter, ReSql,
the registry and both PostgreSQL instances don't expose Prometheus metrics yet, there's no
node_exporter/cAdvisor/postgres_exporter (so no restart-loop/disk/DB-down alerts), no centralized log
collection (MON-1), and Alertmanager has no real notification receiver configured (alerts are visible in
its UI only, not paged anywhere). Where a runbook below says "not yet available", that's what's meant.

## Template

```markdown
# <Scenario name>

## Symptoms
- What an operator actually observes (alert text, user report, HTTP status, log line).

## Diagnosis
- Where to look first, in order. Concrete commands, not "check the logs".

## Resolution
- Numbered recovery steps, safest/most-likely-to-work first.

## Escalation
- Who/what to page if the above doesn't resolve it within SLA, and what to hand them.

## Dashboards / queries
- Links to Grafana panels and log queries (once the observability stack exists).
```

## Index

| Scenario | File |
|---|---|
| Database connectivity loss | [database-connectivity-loss.md](database-connectivity-loss.md) |
| Container/pod crash loop | [pod-crash-loop.md](pod-crash-loop.md) |
| High error rate | [high-error-rate.md](high-error-rate.md) |
| Disk full | [disk-full.md](disk-full.md) |
| Certificate expiry (AS4 / TIM / TARA) | [certificate-expiry.md](certificate-expiry.md) |
| eDelivery (AS4) communication failure | [edelivery-communication-failure.md](edelivery-communication-failure.md) |
| TARA authentication outage | [tara-authentication-outage.md](tara-authentication-outage.md) |
| X-Road (xroad) service unavailable | [xtee-service-unavailable.md](xtee-service-unavailable.md) |

## Escalation paths (default, until an on-call rotation exists)

1. **Gate operator / ops engineer** — first responder, runs the diagnosis steps below.
2. **Platform/infra owner** — Kubernetes, database, secrets, certificate rotation.
3. **eFTI Gate maintainers** (this repository) — DSL/application logic, AS4, X-Road adapter.
4. **Peer gate operators** — only when a cross-gate AS4 exchange or X-Road authority dispute needs the
   other side's cooperation (certificate mismatch, registry drift).

Each runbook below names which of these tiers is the right escalation target for that scenario.
