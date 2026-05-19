# THEME 5 — Infrastructure


**Objective:** Ensure the gate operates to production standards: horizontally scalable across multiple nodes, tolerant of a single node failure without data loss, and smoothly integrated with Kubernetes lifecycle management.

**Theme done when:**
- [ ] EPIC 12 (Scalability): 2+ nodes run without shared memory; registries sync via LISTEN/NOTIFY
- [ ] EPIC 13 (Health): liveness/readiness probes pass; graceful shutdown ≤30s; `/health` public
- [ ] EPIC 26 (Archival): CronManager deployed alongside the gate; nightly job moves non-latest rows of every operational table to archival storage; live DB stays bounded

**Problem:** The current architecture uses in-memory registries — running multiple nodes results in desynchronised state. Request ID duplicate detection only works within a single node. Background jobs (ping, expiry) run on every node simultaneously. Certificates and secrets are baked into container images — reuse across environments is not possible.

**Business value:**
- N+1 redundancy (required for production SLA)
- No session affinity needed at the load balancer — simpler infrastructure
- Zero data loss during node failures
- Zero-downtime rolling updates in Kubernetes
- Kubernetes auto-healing: unhealthy pods are restarted automatically


## Epics

- [EPIC 12 — Scalability and Statelessness](epic_12_en.md)
- [EPIC 13 — Health Checks and Graceful Shutdown](epic_13_en.md)
- [EPIC 26 — Append-Only Archival via CronManager](epic_26_en.md)
