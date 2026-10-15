# Operations runbooks

These runbooks help operators diagnose and correct the gate's most common failures. Each runbook
uses the same template. An on-call engineer can therefore find the applicable instructions quickly.

**Current state:** Prometheus, Grafana, and Alertmanager are configured in `docker/prometheus/`,
`docker/grafana/`, and `docker/alertmanager/`. The files `compose.yml` and `compose.override.yml`
start these services. Prometheus collects the klite `/metrics` endpoint from `edelivery` and
`xml-mapper`
(`code/core/src/metrics/RequestMetrics.kt`) — see
[`docs/architecture/observability/monitoring_and_alerting.md`](../architecture/observability/monitoring_and_alerting.md)
for the implemented coverage. In a local environment, Grafana is at `http://localhost:3000`.
Its user name is `admin`, and `$GRAFANA_ADMIN_PASSWORD` supplies the password. The default password
is `admin`. Prometheus is at `http://localhost:9090`. Alertmanager is at
`http://localhost:9093` and accepts loopback connections only.

**Known gaps:** Ruuter, ReSql, the registry, and the two PostgreSQL instances do not expose
Prometheus metrics. The stack does not include node_exporter, cAdvisor, or postgres_exporter.
Consequently, it cannot alert on restart loops, full disks, or unavailable databases. Centralized
log collection (MON-1) is also unavailable. Alertmanager has no notification receiver, so it shows
alerts only in its UI. In the runbooks, **not yet available** refers to these gaps.

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
| Manual rollback | [manual-rollback.md](manual-rollback.md) |

## Escalation paths (default, until an on-call rotation exists)

1. **Gate operator / ops engineer** — first responder, runs the diagnosis steps below.
2. **Platform/infra owner** — Kubernetes, database, secrets, certificate rotation.
3. **eFTI Gate maintainers** (this repository) — DSL/application logic, AS4, X-Road adapter.
4. **Peer gate operators** — only when a cross-gate AS4 exchange or X-Road authority dispute needs the
   other side's cooperation (certificate mismatch, registry drift).

Each runbook below names which of these tiers is the right escalation target for that scenario.
