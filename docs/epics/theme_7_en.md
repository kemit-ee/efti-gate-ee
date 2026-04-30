# THEME 7 — Observability


**Objective:** Ensure every request is traceable end-to-end across all components, the operations team is notified of incidents before users are affected, and 95% of incidents are resolved within 4 hours.

**Theme done when:**
- [ ] EPIC 16 (Logging): all logs in ECS JSON, X-Request-ID propagated end-to-end
- [ ] EPIC 17 (Monitoring): Prometheus + Grafana active, alert rules configured

**Problem:** Current logging is inconsistent:
- `GateClient`, `EDeliveryClient`, `PlatformClient` outgoing requests are not logged
- Correlation IDs are not propagated across all log lines (MDC missing)
- Business logic routing decisions (broadcast vs local) are not visible in logs
- Authorisation denials are logged without user identity or reason
- Structured JSON logging (ECS) is missing — centralised collection is not feasible
- Prometheus metrics, Grafana dashboards, and alerting are entirely absent

**Business value:**
- Every failed request can be traced end-to-end using a correlation ID
- All gate-to-gate communication is visible in logs (which gate, response time, success/failure)
- Proactive incident detection reduces downtime
- SLA compliance: 95% of incidents resolved within 4 hours


## Epics

- [EPIC 16 — Logging and Observability](epic_16_en.md)
- [EPIC 17 — Monitoring and Alerting](epic_17_en.md)
