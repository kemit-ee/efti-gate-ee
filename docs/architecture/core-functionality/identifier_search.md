# Architecture: Identifier Search (Authority API)

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Identifier Search (Authority API) surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/core-functionality/identifier_search.md`](../../cfr/core-functionality/identifier_search.md).

## Search decision at a glance

```mermaid
flowchart TD
    Q["GET /v1/identifiers/{identifier}<br/>Accept: text/event-stream"] --> Local["Query identifiers table<br/>status=active, pg_trgm plate match"]
    Local --> Count{local count > 0<br/>OR forceBroadcast?}
    Count -- local hits, no force --> SSEonly[SSE: stream local<br/>+ event: complete]
    Count -- empty or force --> Broadcast[Broadcast to ONLINE gates<br/>parallel, 8 s timeout]
    Broadcast --> Stream["SSE: gate, consignment, complete<br/>per-gate failures array"]
    SSEonly --> End([200 OK])
    Stream --> End
```

## Rationale

The gate is the **registry**, not the dataset store. Authority search hits the local registry first; only on a local miss does it broadcast to peer gates — to avoid leaking the search across the EU when the answer is local, and to avoid unnecessary cross-border load. The denormalised search columns and no-JOIN hot path keep the local query under the 50 ms SLO at scale.

