# Architecture: eDelivery AS4 Message Flow

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the eDelivery AS4 Message Flow surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/integrations/as4_message_flow.md`](../../cfr/integrations/as4_message_flow.md).

## AS4 message types at a glance

```mermaid
flowchart LR
    GA[Gate A] -- identifierQuery / uilQuery / postFollowUpRequest --> AP[eDelivery AS4 AP<br/>embedded or Domibus<br/>SOAP, WS-Security, sign + encrypt]
    AP --> GB[Gate B]
    GB -- identifierResponse / uilResponse --> AP
    AP -- async via async_responses<br/>+ LISTEN/NOTIFY --> GA
    GB -. SOAP fault on parse error<br/>or unknown Action .-> AP
```

## Acceptance Criteria

**Business rules:**
- [ ] Both AS4 flows (outgoing identifier search, incoming UIL request) are documented as sequence diagrams below.
- [ ] Each diagram covers: SOAP envelope construction, WS-Security signing + encryption, parse + signature verification on the receiver, and failure handling (SOAP fault on parse error or unknown Action).
- [ ] The diagrams stay in sync with Epic 10 ACs — any change to the AS4 contract there must be reflected here in the same PR.

### Flow 1 — Outgoing identifier search (Gate → eDelivery → Remote Gate)

```mermaid
sequenceDiagram
    participant Gate as Gate Backend
    participant EDelivery as eDelivery AS4 AP (embedded or Domibus)
    participant RemoteEDelivery as Remote Gate eDelivery
    participant RemoteGate as Remote Gate Backend

    Gate->>Gate: Build identifierQuery XML (UIL / vehicle_plate)
    Gate->>Gate: Wrap in AS4 envelope (SOAP header: From, To, Service, Action)
    Gate->>Gate: Sign and encrypt payload (WS-Security)
    Gate->>EDelivery: POST /services/backend (SOAP/AS4)
    EDelivery->>RemoteEDelivery: AS4 message (over internet)
    RemoteEDelivery->>RemoteGate: POST /services/msh (forwarded payload)
    RemoteGate->>RemoteGate: Process identifierQuery
    RemoteGate-->>RemoteEDelivery: identifierResponse XML
    RemoteEDelivery-->>EDelivery: AS4 response message
    EDelivery-->>Gate: Incoming identifierResponse (async callback)
    Gate->>Gate: Parse response, forward via SSE to authority officer
```

### Flow 2 — Incoming UIL request (Remote Gate → Gate → Platform)

```mermaid
sequenceDiagram
    participant RemoteGate as Remote Gate
    participant EDelivery as eDelivery AS4 AP (embedded or Domibus)
    participant Gate as Gate Backend
    participant Platform

    RemoteGate->>EDelivery: AS4 uilQuery message
    EDelivery->>Gate: POST /services/msh (decrypted payload)
    Gate->>Gate: Parse SOAP envelope, validate signature
    Gate->>Gate: Identify message type (uilQuery / identifierQuery / followUp)
    Gate->>Platform: GET /datasets/:datasetId (subset request)
    Platform-->>Gate: XML dataset
    Gate->>Gate: Build uilResponse AS4 message
    Gate->>EDelivery: POST /services/backend (uilResponse)
    EDelivery-->>RemoteGate: AS4 response
```

## Rationale

eDelivery AS4 is the EU's standard cross-border message bus; documenting the on-wire shape (SOAP envelope, WS-Security, async callback via `async_responses` + `LISTEN/NOTIFY`) is what lets a partner trace any failure mode without reading the gate source. The "embedded or Domibus" framing makes the AS4 implementation a deployment-time decision rather than a hard spec mandate.

