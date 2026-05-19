# Architecture: Monitoring and Alerting

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Monitoring and Alerting surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/observability/monitoring_and_alerting.md`](../../cfr/observability/monitoring_and_alerting.md).

## Monitoring pipeline at a glance

```mermaid
flowchart LR
    Gate[Gate node<br/>/metrics endpoint<br/>HTTP req/duration/errors,<br/>eDelivery msg count,<br/>gate ONLINE/OFFLINE] --> Prom[Prometheus<br/>15 s scrape]
    Prom --> Graf[Grafana dashboard<br/>p50/p95/p99,<br/>error rate, gate status]
    Prom --> Rules[Alert rules<br/>error rate > 5%/5 min,<br/>restarts > 3/10 min,<br/>DB down, disk > 90%]
    Rules --> Alert[Alertmanager → on-call]
```

## Rationale

The gate is regulator-facing; an outage during an inspection window has legal as well as operational consequences. Alerts are deliberately coarse (error rate, restart loop, DB failure, peer-gate stall, disk) — the long tail of issue-specific alerts emerges from operating the system, not from spec-writing. SLOs and the HPA contract live in one place (`non-functional.md`); this epic just wires them up to dashboards + alertmanager.

