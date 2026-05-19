# Architecture: Gate Registry Management (Admin API)

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Gate Registry Management (Admin API) surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/registry-management/gate_registry.md`](../../cfr/registry-management/gate_registry.md).

## Gate lifecycle at a glance

```mermaid
stateDiagram-v2
    [*] --> ONLINE: POST /api/v1/gates
    ONLINE --> OFFLINE: ping fails (10 s timeout)
    OFFLINE --> ONLINE: ping succeeds (5 min cycle)
    ONLINE --> DISABLED: Admin sets status=DISABLED
    OFFLINE --> DISABLED: Admin disables unreachable gate
    DISABLED --> ONLINE: Admin re-enables + ping OK
    ONLINE --> [*]: DELETE /api/v1/gates/{gateId}
    OFFLINE --> [*]: DELETE /api/v1/gates/{gateId}
    DISABLED --> [*]: DELETE /api/v1/gates/{gateId}
    note right of ONLINE
        Included in broadcasts;
        gateRegistry.online() returns
    end note
    note right of DISABLED
        Excluded from broadcasts AND ping job;
        will not auto-recover
    end note
```

## Rationale

The gate registry is **shared state across the cluster**: every node caches the latest row per gate and refreshes on `NOTIFY`. Pings are append-only state transitions, so the history is auditable without an UPDATE-trigger pattern. Recurring jobs live in CronManager (not the gate) so a scheduled job runs exactly once regardless of how many gate replicas are deployed — combined with the advisory-lock mutex, multi-node deployments are safe.

