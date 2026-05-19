# Theme: Infrastructure

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Architecture: [`../../architecture/infrastructure/README.md`](../../architecture/infrastructure/README.md). The overarching rules are defined there; the AC below verify the gate honours those rules end-to-end.

<!-- issue-body:begin -->

**Objective:** Ensure the gate operates to production standards: horizontally scalable across multiple nodes, tolerant of a single node failure without data loss, and smoothly integrated with Kubernetes lifecycle management.

## Business value

- N+1 redundancy (required for production SLA)
- No session affinity needed at the load balancer — simpler infrastructure
- Zero data loss during node failures
- Zero-downtime rolling updates in Kubernetes
- Kubernetes auto-healing: unhealthy pods are restarted automatically

## Acceptance Criteria

**Theme done when:**
- [ ] EPIC 12 (Scalability): 2+ nodes run without shared memory; registries sync via LISTEN/NOTIFY
- [ ] EPIC 13 (Health): liveness/readiness probes pass; graceful shutdown ≤30s; `/health` public
- [ ] EPIC 26 (Archival): CronManager deployed alongside the gate; nightly job moves non-latest rows of every operational table to archival storage; live DB stays bounded

<!-- issue-body:end -->

## Sub-areas

- [Scalability and Statelessness](scalability_and_statelessness.md)
- [Health Checks and Graceful Shutdown](health_checks_and_graceful_shutdown.md)
- [Append-Only Archival via CronManager](append_only_archival.md)
