# EPIC 23 — Authentication and Access Flows

> Part of [Theme 1](theme_1_en.md)

**AS A** technical architect  
**I WANT** documented authentication and access flows with sequence diagrams  
**SO THAT** integration partners and developers understand exactly how authentication works in each channel type

**Reference:** [RA §8.1 Security Layers](../architecture/eFTI-Gate-Reference-Architecture.md#81-security-layers) — Authentication architecture for all three flows

**Authentication channels at a glance:**

```mermaid
flowchart TD
    Caller[Caller] --> Type{Channel?}
    Type -- Authority / Admin API --> F2[Flow 2: TARA OIDC JWT<br/>RS256, JWKS-validated; users.tara_sub lookup;<br/>sessions denylist check]
    Type -- Platform API --> F2b[Flow 2b: mTLS<br/>X.509 cert; platforms.cert_subject lookup]
    Type -- CronManager admin --> Fops[Static Bearer ARCHIVE_OPS_TOKEN<br/>literal env-var compare]
    Type -- Gate-to-gate --> F3[Flow 3: mTLS at AS4 access point<br/>EU Trust Service cert chain]
    F2 --> Allow[Resource access]
    F2b --> Allow
    Fops --> Allow
    F3 --> Allow
```

The gate is a stateless OAuth 2.0 Resource Server. There is no Admin-UI session cookie and no DB-side admin-session store; the JWT is the session.

Detailed sequences for each flow follow below.

#### Acceptance Criteria

- [ ] All three authentication patterns documented as sequence diagrams (see below)
- [ ] Each flow covers: authentication, authorisation check, error cases
- [ ] Diagrams published in GitHub documentation

##### Flow 1 — Admin UI login (UI-side OIDC → JWT to gate)

```mermaid
sequenceDiagram
    actor Admin
    participant UI as Admin UI (browser)
    participant TARA as TARA (RIA)
    participant Gate as Gate Backend (Resource Server)

    Admin->>UI: Open admin UI
    UI->>TARA: OIDC authorize (client_id, scope=openid, state, nonce)
    TARA->>Admin: Display ID-card / Mobile-ID / Smart-ID
    Admin->>TARA: Authenticate
    TARA-->>UI: id_token (RS256 JWT, sub=PIC, claims iss/aud/exp/jti)

    Note over UI: UI persists the JWT in browser storage (sessionStorage)<br/>and attaches it as Authorization: Bearer to every gate call.<br/>No cookie. No server-side session.

    UI->>Gate: GET /api/v1/user<br/>Authorization: Bearer <TARA-JWT>
    Note over Gate: Gate validates JWT against cached TARA JWKS,<br/>checks sessions denylist, resolves users by tara_sub.
    Gate-->>UI: 200 OK (current user profile)
    UI-->>Admin: Render admin home
```

Logout is `POST /api/v1/auth/logout` carrying the same Bearer; the gate writes the JWT's `jti` to the `sessions` denylist with `reason='logout'`. Subsequent calls with the same JWT return `401 TOKEN_INVALID`.

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
