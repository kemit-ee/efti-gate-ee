# Architecture: Infrastructure

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Theme-wide architectural rules. Every sub-area below — and every Acceptance Criterion (AC) it carries — must derive from or at minimum **not conflict with** the rules stated here. AC live in the corresponding sub-area files under [`docs/cfr/infrastructure/`](../../cfr/infrastructure/); this document describes the *contract those AC implement*.

**System-wide reference:** [eFTI Gate Reference Architecture](../eFTI-Gate-Reference-Architecture.md). This document narrows the system-wide rules to the Infrastructure surface.

**Sub-architectures in this theme** (each is the architectural surface for the AC tracked in the linked epic):

- [Scalability and Statelessness](scalability_and_statelessness.md) — AC: [`docs/cfr/infrastructure/scalability_and_statelessness.md`](../../cfr/infrastructure/scalability_and_statelessness.md)
- [Health Checks and Graceful Shutdown](health_checks_and_graceful_shutdown.md) — AC: [`docs/cfr/infrastructure/health_checks_and_graceful_shutdown.md`](../../cfr/infrastructure/health_checks_and_graceful_shutdown.md)
- [Append-Only Archival via CronManager](append_only_archival.md) — AC: [`docs/cfr/infrastructure/append_only_archival.md`](../../cfr/infrastructure/append_only_archival.md)

---

## Overarching rules

_Theme-wide architectural rules to be elaborated. Until then, each sub-area's architecture file documents its own scope._
