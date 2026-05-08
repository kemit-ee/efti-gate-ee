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

##### Flow 2 — Authority / Admin API (TARA OIDC JWT)

```mermaid
sequenceDiagram
    actor Officer as Authority Officer / Gate Admin
    participant TARA as TARA (RIA)
    participant Gate as eFTI Gate
    participant DB as PostgreSQL

    Officer->>TARA: OIDC login (eID / Mobile-ID / Smart-ID)
    TARA-->>Officer: ID Token (RS256 JWT, sub = Estonian PIC, claims iss/aud/exp/jti)

    Note over Officer,Gate: Subsequent API request

    Officer->>Gate: GET /v1/identifiers/123ABC<br/>Authorization: Bearer <TARA-JWT>
    Gate->>Gate: Validate JWT against cached TARA JWKS
    Gate->>DB: SELECT 1 FROM sessions WHERE jti = $1 AND expires_at > NOW()
    DB-->>Gate: 0 rows (not in denylist)
    Gate->>DB: SELECT … FROM users WHERE tara_sub = jwt.sub AND is_active = TRUE
    DB-->>Gate: User{roles=[AUTHORITY], subsets=[EU07], scope=[auth-mta]}
    alt JWT valid + user resolved + role matches route
        Gate-->>Officer: 200 OK
    else Signature/exp/aud invalid OR jti revoked OR no users row
        Gate-->>Officer: 401 TOKEN_INVALID (RFC 7807)
    else Wrong role / out-of-scope subset / out-of-scope entity
        Gate-->>Officer: 403 FORBIDDEN (RFC 7807)
    end
```

##### Flow 2b — Platform API (mTLS)

```mermaid
sequenceDiagram
    participant Platform as Platform Operator
    participant Proxy as Reverse Proxy
    participant Gate as eFTI Gate
    participant DB as PostgreSQL

    Platform->>Proxy: POST /v1/identifiers/:datasetId<br/>(client cert: Member-State-issued for the platform's eDelivery AP)
    Proxy->>Proxy: Validate cert chain
    Proxy->>Gate: forwarded request + X-Client-Cert-Subject + X-Client-Cert-Serial
    Gate->>DB: SELECT DISTINCT ON (id) FROM platforms<br/>WHERE cert_subject = $1 AND cert_serial = $2 AND is_active = TRUE
    DB-->>Gate: 1 row → platform_id resolved
    alt cert resolves to exactly 1 active platform
        Gate-->>Platform: 200 OK
    else 0 rows
        Gate-->>Platform: 403 FORBIDDEN_NO_PLATFORM
    else >1 rows (config error)
        Gate-->>Platform: 403 FORBIDDEN_MULTI_PLATFORM
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
