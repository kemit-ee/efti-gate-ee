# EPIC 23 — Authentication and Access Flows

> Part of [Theme 1](theme_1_en.md)

**AS A** technical architect  
**I WANT** documented authentication and access flows with sequence diagrams  
**SO THAT** integration partners and developers understand exactly how authentication works in each channel type

**Reference:** [RA §8.1 Security Layers](../architecture/eFTI-Gate-Reference-Architecture.md#81-security-layers) — Authentication architecture for all three flows

**Three authentication channels at a glance:**

```mermaid
flowchart TD
    Caller[Caller] --> Type{Channel?}
    Type -- Admin UI --> F1[Flow 1: TARA/OIDC<br/>session cookie]
    Type -- Platform/Authority API --> F2[Flow 2: Bearer JWT RS256<br/>signature + exp + role check]
    Type -- Gate-to-gate --> F3[Flow 3: mTLS<br/>cert OCSP/CRL check]
    F1 --> Allow[Resource access]
    F2 --> Allow
    F3 --> Allow
```

Detailed sequences for each flow follow below.

#### Acceptance Criteria

- [ ] All three authentication patterns documented as sequence diagrams (see below)
- [ ] Each flow covers: authentication, authorisation check, error cases
- [ ] Diagrams published in GitHub documentation

##### Flow 1 — Admin UI login (TARA/OIDC)

```mermaid
sequenceDiagram
    actor Admin
    participant UI as Admin UI
    participant Gate as Gate Backend
    participant TARA as TARA (OIDC)
    participant DB as Database

    Admin->>UI: Open admin UI
    UI->>Gate: GET /auth/login
    Gate->>TARA: Redirect OIDC authorize (client_id, scope, state)
    TARA->>Admin: Display authentication page (ID-card / Mobile-ID / Smart-ID)
    Admin->>TARA: Authenticate
    TARA->>Gate: GET /auth/callback?code=...&state=...
    Gate->>TARA: POST /token (code, client_secret)
    TARA-->>Gate: id_token (JWT), access_token
    Gate->>DB: Store session (session_id, user_id, exp)
    Gate-->>UI: Set-Cookie session_id (HttpOnly, Secure)
    UI-->>Admin: Redirect to admin home
```

##### Flow 2 — Platform/Authority API authentication (JWT Bearer)

```mermaid
sequenceDiagram
    actor Admin
    participant Gate as Gate Backend
    participant Platform as Platform / Authority

    Admin->>Gate: POST /api/v1/users (generateSecret=true)
    Gate-->>Admin: JWT token (signed RS256)

    Note over Platform,Gate: Later API request

    Platform->>Gate: POST /v1/identifiers/:id<br/>Authorization: Bearer <JWT>
    Gate->>Gate: Validate JWT (signature, exp, iss)
    Gate->>Gate: Check role type + Party ID (checkWriteAccess)
    alt Token valid and access permitted
        Gate-->>Platform: 200 OK
    else Token expired / invalid signature
        Gate-->>Platform: 401 Unauthorized (RFC 7807)
    else Access denied
        Gate-->>Platform: 403 Forbidden (RFC 7807)
    end
```

##### Flow 3 — Gate-to-gate fast protocol (mTLS)

```mermaid
sequenceDiagram
    participant GateA as Gate A
    participant GateB as Gate B

    Note over GateA,GateB: TLS handshake with mTLS
    GateA->>GateB: TLS ClientHello + client certificate
    GateB->>GateB: Validate GateA certificate (CA, OCSP/CRL)
    GateB-->>GateA: TLS ServerHello + server certificate
    GateA->>GateA: Validate GateB certificate

    GateA->>GateB: POST /services/fast<br/>(identifierQuery / uilQuery)
    GateB->>GateB: Process request
    GateB-->>GateA: 200 OK (XML response)
```

---
