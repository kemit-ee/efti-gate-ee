# Architecture: Platform Registry Management (Admin API)

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Platform Registry Management (Admin API) surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/registry-management/platform_registry.md`](../../cfr/registry-management/platform_registry.md).

## Platform lifecycle at a glance

```mermaid
stateDiagram-v2
    [*] --> ONLINE: POST /api/v1/platforms<br/>(id, baseUrl, certSubject, certSerial, eDeliveryCert?)
    ONLINE --> ONLINE: PUT /api/v1/platforms/{id}<br/>(append-only INSERT; cert renewal etc.)
    ONLINE --> DELETED: DELETE /api/v1/platforms/{id}<br/>(latest row status='DELETED')
    DELETED --> ONLINE: POST again — new row, status='ONLINE'
    note right of Active
        Registry change → app emits NOTIFY registry_change_platforms, id
        in same transaction; other nodes LISTEN and reload
        from gates/platforms within ≤ 500 ms.
    end note
```

## Rationale

Platform metadata (cert subject/serial, base URL, capability flags) drives Platform-API auth and the subsetting decision in Epic 5. Append-only INSERTs preserve every cert rotation and capability change as an auditable history. `LISTEN/NOTIFY` keeps every gate node's in-memory platform cache fresh without polling.

