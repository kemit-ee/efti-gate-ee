# High error rate

## Symptoms
- A noticeable rise in 4xx/5xx responses from `ruuter` (gate API), `edelivery`, or `xml-mapper`.
- Platform or authority integrators reporting failed uploads/searches.
- Cross-gate AS4 messages failing or timing out (see
  [edelivery-communication-failure.md](edelivery-communication-failure.md) if it's specifically AS4).

## Diagnosis
1. Check the **"4xx/5xx error rate" panel** on the "eFTI Gate overview" Grafana dashboard
   (`http://localhost:3000/d/efti-gate-overview`) — it's computed from `edelivery`/`xml-mapper`'s
   `/metrics`. The `HighErrorRate` Alertmanager rule (`docker/prometheus/alert-rules.yml`) fires at >5%
   over 5 minutes, visible at `http://localhost:9093` (no paging receiver configured yet — check its UI,
   don't wait to be paged). This only covers the two klite Kotlin services; Ruuter/ReSql error spikes
   still have to be found by tailing logs: `docker compose logs -f ruuter edelivery xml-mapper`.
2. Narrow by layer:
   - **Ruuter DSL 400s** — almost always a declaration/allowlist mismatch (`declaration.allowlist`,
     `required: true` fields) or a caller sending malformed input; check `ruuter`'s access log for the
     path and the `{"error": "Field missing: X"}` body.
   - **Ruuter DSL 401/403** — guard rejection (missing/invalid credential, `X-Internal-Service-Token`,
     platform `X-Api-Key`, X-Road `X-Road-Client`). Confirm whether this is one caller misconfigured or
     a guard regression affecting everyone.
   - **5xx / `result.response.status == 0`** — an upstream `http.*` call failed (connection refused, DNS,
     TLS handshake, timeout) and Ruuter 0.9.15-rc+ surfaces it as a semantic 502/503 via
     `result.response.error` (`timeout`/`connect`/`request`/`body`/`decode`/`unknown`) — check which
     downstream (ReSql, edelivery, xml-mapper, a peer gate) that error attributes to.
   - **ReSql errors** — non-2xx ReSql responses carry `X-Resql-Error-Code`/`X-Resql-Error-Message`
     headers; check those rather than assuming an empty `[]` body means "no rows".
3. Check `GET /admin/v1/audit` for a spike in failed admin actions, and the E2E/`DSL-tests` suite status
   if the error appeared right after a deploy (regression vs. operational).

## Resolution
1. If it's one DSL route: identify the failing step from the log/trace and compare against its
   `declaration:`/guard; this is almost always a config or input-contract issue, not a database issue.
2. If it's a downstream dependency (ReSql, edelivery, xml-mapper, a peer gate, TIM): see the relevant
   runbook ([database-connectivity-loss.md](database-connectivity-loss.md),
   [edelivery-communication-failure.md](edelivery-communication-failure.md),
   [tara-authentication-outage.md](tara-authentication-outage.md)).
3. If it started right after a deploy: compare against the previous release's commit — this is the
   fastest signal while there's no automated rollback workflow (CICD-3, open). Redeploy the prior image
   tag if the cause isn't immediately obvious and the error rate is customer-impacting.
4. If it's one caller (one platform or one X-Road member) misconfigured: it does not need a gate-wide
   fix — contact that integrator with the exact error code/detail from the DSL response.

## Escalation
- Ops engineer triages which layer is failing.
- Escalate to gate maintainers for anything that looks like a DSL/application logic regression.
- Escalate to the platform/infra owner if every service is erroring simultaneously (points at shared
  infra: DB, network, TIM).

## Dashboards / queries
- Grafana: "eFTI Gate overview" dashboard, "4xx/5xx error rate" and "HTTP request rate by method/status
  class" panels (`docker/grafana/provisioning/dashboards/efti-gate-overview.json`).
- Prometheus query (same expression the alert uses):
  `sum(rate({__name__=~"http_requests_total_.*_(4xx|5xx)"}[5m])) / clamp_min(sum(rate({__name__=~"http_requests_total_.*"}[5m])), 1)`
- Still missing: per-route breakdown (the metric only has method + status-class, no path label) and any
  signal at all from Ruuter/ReSql, which don't expose `/metrics`.
