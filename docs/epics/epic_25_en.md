# EPIC 25 — eDelivery AS4 Message Flow

> Part of [Theme 4](theme_4_en.md)

**AS A** technical architect  
**I WANT** documented eDelivery AS4 message flows with sequence diagrams  
**SO THAT** developers understand exactly how inter-gate messages travel through the AS4 protocol

**References:**
- [Data Transformations](../specs/data-transformations.md) — XML→AS4 envelope wrapping; XSD validation; SOAP fault mapping
- [Diagrams](../specs/diagrams/seq-14-gate-to-gate-search.mmd) — Gate-to-gate AS4 search sequence; [seq-16](../specs/diagrams/seq-16-mtls-fast-protocol.mmd) — mTLS fast-protocol alternative
- [eDelivery XSD](../efti-analysis/xsd/edelivery/gate.xsd) — eDelivery message schema
- [RA §4 Protocol Architecture](../architecture/eFTI-Gate-Reference-Architecture.md#4-protocol-architecture-generic-envelope--variable-payload) — AS4 envelope and protocol model
- [RA §5.1 Identifier Query](../architecture/eFTI-Gate-Reference-Architecture.md#51-identifier-query-cross-border-search) — Cross-border search flow

**AS4 message types at a glance:**

```mermaid
flowchart LR
    GA[Gate A] -- identifierQuery / uilQuery / postFollowUpRequest --> Dom[Domibus AS4<br/>SOAP, WS-Security<br/>sign + encrypt]
    Dom --> GB[Gate B]
    GB -- identifierResponse / uilResponse --> Dom
    Dom -- async via async_responses<br/>+ LISTEN/NOTIFY --> GA
    GB -. SOAP fault on parse error<br/>or unknown Action .-> Dom
```

Detailed sequence diagrams for outgoing and incoming flows follow below.

#### Acceptance Criteria

- [ ] Both AS4 flows documented (outgoing identifierQuery and incoming uilResponse)
- [ ] Diagrams cover: SOAP envelope construction, signing, encryption, failure handling
- [ ] Diagrams published in GitHub documentation

##### Flow 1 — Outgoing identifier search (Gate → eDelivery → Remote Gate)

```mermaid
sequenceDiagram
    participant Gate as Gate Backend
    participant EDelivery as eDelivery (Domibus)
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

##### Flow 2 — Incoming UIL request (Remote Gate → Gate → Platform)

```mermaid
sequenceDiagram
    participant RemoteGate as Remote Gate
    participant EDelivery as eDelivery (Domibus)
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


---
