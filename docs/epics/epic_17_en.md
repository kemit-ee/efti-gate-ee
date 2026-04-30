# EPIC 17 — Monitoring and Alerting

> Part of [Theme 7](theme_7_en.md)

**AS AN** operations engineer  
**I WANT** real-time metrics, dashboards, and automated alerts  
**SO THAT** I can detect and resolve incidents before users are affected

**Reference:** [RA §7.1 Logical Component Layers](../architecture/eFTI-Gate-Reference-Architecture.md#71-logical-component-layers) — Monitoring and metrics in infrastructure layer

#### Acceptance Criteria

**Happy path:**
- [ ] Metrics endpoint exposes: HTTP request count/duration/errors, eDelivery message count, total identifier count, gate ONLINE/OFFLINE status
- [ ] Real-time dashboard: req/min, latency (p50/p95/p99), error rate, gate status
- [ ] Centralised log aggregation — logs from all pods collected in central system (Loki/ELK)
- [ ] Alerts configured:
  - Gate error rate > 5% in last 5 minutes
  - Application node restarting repeatedly (> 3 restarts in 10 minutes)
  - Database connection failure
  - Disk usage > 90%
  - eDelivery message processing stalled > 15 minutes

**Edge cases:**
- [ ] Metrics endpoint unavailable (app crash) → Prometheus marks target as DOWN; alert fires after 2 missed scrapes

**Technical constraints:**
- [ ] Prometheus scrape interval: 15 seconds (configurable)
- [ ] Grafana dashboard exported as JSON and committed to repository

##### Performance and SLA

**Happy path:**
- [ ] System handles > 1 million queries per year without performance degradation
- [ ] Single node capacity: ≥ 100 requests/sec without exceeding p95 latency threshold
- [ ] End-to-end response time for roadside inspections < 60 seconds (EU Reg 2024/1942)
- [ ] Service availability ≥ 99.9% during business hours (10:00–16:00 CET minimum — Art 8(3) Reg 2024/1942)
- [ ] Performance tests run in CI/CD — regressions cause build failure
- [ ] Incident resolution SLA: 95% of incidents resolvable within 4 hours

**Technical artifacts:**
- [ ] Grafana dashboard JSON in `monitoring/` directory
- [ ] Prometheus alert rules in `monitoring/alerts.yaml`

---
