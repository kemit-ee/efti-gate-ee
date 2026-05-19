# Architecture: Identifier Search and Dataset Retrieval Flows

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Identifier Search and Dataset Retrieval Flows surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/core-functionality/search_and_retrieval_flows.md`](../../cfr/core-functionality/search_and_retrieval_flows.md).

## Four data flows at a glance

```mermaid
flowchart LR
    P[Platform] -- F1: register --> G1[Gate]
    A[Authority Officer] -- F2: search identifier --> G2[Gate]
    G2 -. F2: broadcast if local empty .-> Other[Other EU Gates]
    A -- F3: GET /v1/dataset/{uil} --> G3[Gate]
    G3 -- F3: own gate --> Plat[Platform]
    G3 -- F3: remote --> RG[Remote Gate]
    A -- F4: POST /v1/follow-up/... --> G4[Gate]
    G4 -- F4: route by gateId --> Plat
    G4 -- F4: route by gateId --> RG
```

## Acceptance Criteria

**Business rules:**
- [ ] Each of the four core data flows is documented as a sequence diagram below.
- [ ] Each flow covers at least: the happy path, and one denial / failure path (gate offline, empty result, unauthorised access, missing subset, etc.).
- [ ] The diagrams stay in sync with Epic 3 / 4 / 5 ACs — any change there must be reflected here in the same PR.

### Flow 1 — Identifier registration (Platform → Gate)

```mermaid
sequenceDiagram
    participant Platform
    participant Gate as Gate Backend
    participant DB as Database

    Platform->>Gate: POST /v1/identifiers/:datasetId<br/>Authorization: Bearer <JWT><br/>Body: XML (vehicle_plate, transport_mode, ...)
    Gate->>Gate: Validate JWT + role type
    Gate->>DB: Upsert consignment (datasetId, platformId, vehicle_plate)
    DB-->>Gate: OK
    Gate-->>Platform: 201 Created / 200 OK
```

### Flow 2 — Identifier search (Authority → Gate → Broadcast)

```mermaid
sequenceDiagram
    actor Officer as Authority Officer
    participant Gate as Gate Backend
    participant DB as Database
    participant OtherGates as Other EU Gates

    Officer->>Gate: GET /v1/identifiers?vehicle_plate=ABC123<br/>Accept: text/event-stream
    Gate->>Gate: Validate JWT + authority subset permissions
    Gate->>DB: Local search (vehicle_plate)

    alt Local results found
        DB-->>Gate: Consignment records
        Gate-->>Officer: SSE event: data (local results)
    else Local result empty → broadcast
        Gate->>OtherGates: Parallel requests to all ACTIVE gates
        OtherGates-->>Gate: Responses (XML / timeout)
        Gate-->>Officer: SSE event: data (remote results, one per gate)
    end

    Gate-->>Officer: SSE event: name=complete
```

### Flow 3 — Dataset retrieval by UIL

```mermaid
sequenceDiagram
    actor Officer as Authority Officer
    participant Gate as Gate Backend
    participant Platform
    participant RemoteGate as Remote Gate

    Officer->>Gate: GET /v1/datasets/:uil<br/>Authorization: Bearer <JWT>
    Gate->>Gate: Parse UIL → gateId + platformId + datasetId
    Gate->>Gate: Check subset permissions

    alt UIL points to own gate
        Gate->>Platform: GET /datasets/:datasetId (REST or AS4)
        Platform-->>Gate: XML dataset (full)
        Gate->>Gate: Apply subset filter (if supportsSubsetting=false)
        Gate-->>Officer: 200 OK XML (subset)
    else UIL points to remote gate
        Gate->>RemoteGate: POST /services/fast (uilQuery XML)
        RemoteGate-->>Gate: XML response
        Gate->>Gate: Apply subset filter
        Gate-->>Officer: 200 OK XML (subset)
    end
```

### Flow 4 — Follow-up message forwarding

```mermaid
sequenceDiagram
    actor Officer as Authority Officer
    participant Gate as Gate Backend
    participant Platform
    participant RemoteGate as Remote Gate

    Officer->>Gate: POST /v1/follow-up/:gateId/:platformId/:datasetId/:requestId<br/>Body: XML message

    alt gateId == own gate
        Gate->>Platform: Forward follow-up (REST client)
        Platform-->>Gate: 200 OK
    else gateId != own gate
        Gate->>RemoteGate: POST /services/fast (followUp XML)
        RemoteGate-->>Gate: 200 OK
    end

    Gate-->>Officer: 200 OK
```

## Rationale

These four flows are the data-plane surface the gate cares about (auth flows live in Epic 23). The broadcast-only-when-empty behaviour in Flow 2 is the spec's single biggest deviation from a naive "always broadcast" design — it lives here as the visual reference. Flow 3's local-vs-remote branching covers the same routing logic as Flow 4; collapsing them would obscure the asymmetry (Flow 3 has a subset filter, Flow 4 does not).

