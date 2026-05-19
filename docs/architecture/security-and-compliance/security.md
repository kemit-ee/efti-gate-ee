# Architecture: Security

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Security surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/security-and-compliance/security.md`](../../cfr/security-and-compliance/security.md).

## Security layer stack at a glance

```mermaid
flowchart TD
    In[Inbound request] --> RL[Rate limit<br/>100 req/min/IP → 429]
    RL --> TLS[TLS / mTLS termination<br/>Runtime-loaded certs<br/>OCSP/CRL check]
    TLS --> AuthN[AuthN: TARA OIDC / JWT RS256 / mTLS]
    AuthN --> AuthZ[AuthZ: role + Party ID + subset]
    AuthZ --> EUReg[EU platform registry check<br/>Art 7+12 Reg 2020/1056]
    EUReg --> Audit[audit_log INSERT-only<br/>RFC 7807 errors out]
    Audit --> Handler[Resource handler]
```

## Rationale

Security here is layered: rate-limit / TLS / authN / authZ / EU-registry / audit / handler. Each layer either accepts or rejects; rejections produce RFC 7807 responses without leaking internals. Runtime-loaded secrets + fail-closed OCSP are the two non-negotiables that lift the bar from "demo gate" to "regulator-auditable production gate".

