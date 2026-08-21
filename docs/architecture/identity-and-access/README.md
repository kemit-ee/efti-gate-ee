# Architecture: Identity and Access

## Changes

- **v1.1** — Reconciled with the implementation. §1.1 inverted: the database, not the JWT,
  is the authorisation snapshot, and the caller is resolved on every request. §1.2 no longer
  claims a self-contained stateless Resource Server. §1.3 revocation is immediate rather
  than refresh-denial. §1.4 channel table now names TIM as the issuer and validator. §4's
  rationale was already stated this way and is now consistent with §1.
- _Initial state. Change tracking begins at v1.0.0._

> Theme-wide architectural rules. Every epic under this theme — and every Acceptance Criterion (AC) it carries — must derive from or at minimum **not conflict with** the rules stated here. AC live in the corresponding epic files under [`docs/cfr/`](../../cfr/); this document describes the *contract those AC implement*.

**System-wide reference:** [eFTI Gate Reference Architecture §8 Security](../eFTI-Gate-Reference-Architecture.md). This document narrows the system-wide rules to the Identity & Access surface.

**Sub-architectures in this theme** (each is the architectural surface for the AC tracked in the linked epic):

- [User Management and RBAC](user_management_and_rbac.md) — AC: [`docs/cfr/identity-and-access/user_management_and_rbac.md`](../../cfr/identity-and-access/user_management_and_rbac.md)
- [Authentication](authentication.md) — AC: [`docs/cfr/identity-and-access/authentication.md`](../../cfr/identity-and-access/authentication.md)
- [Authentication and Access Flows](authentication_and_access_flows.md) — AC: [`docs/cfr/identity-and-access/authentication_and_access_flows.md`](../../cfr/identity-and-access/authentication_and_access_flows.md)

---

## 1. Overarching rules

These are the cross-cutting invariants that every epic in this theme derives from. AC bullets in the epic files specialise these rules to verifiable conditions on specific endpoints, error codes, or DB state.

### 1.1 The database is the authorisation snapshot, not the JWT

> **Changed v1.1.** This rule previously said the opposite — that the gate minted a JWT
> carrying `roles`/`subsets`/`scopes` and trusted those claims for the token's whole validity
> window, with no hot-path DB lookup. See [authentication.md §2.1](authentication.md) for why
> that was reversed.

The token proves **identity and freshness only**. It carries `sub` / `personalCode`, `iat`,
`exp` and `jti`; it does **not** carry roles, subsets or scopes, and the gate would not trust
them if it did. On every request the gate resolves the caller to a `users` row and reads the
permission set from there.

The consequence that matters: the authorisation snapshot cannot drift from the database. A
role change, a subset change, a soft-delete, an identifier correction or a revocation all
take effect on the very next request, with no TTL to wait out.

The TTL therefore is *not* the granularity of permission propagation — it only bounds how
long an otherwise-valid session lasts before the user logs in again.

### 1.2 No server-side session state in the gate

For Authority and Admin traffic: **no server-side session and no `session_id` cookie in the
gate**. Ruuter holds nothing between requests, so multi-node deployment needs no session
affinity — any node can serve any request.

The gate is not, however, a self-contained Resource Server: validating a request means asking
TIM about the token and reading one `users` row. Both are shared, external state, so a node
is stateless while the *system* is not. That is the deliberate trade in §1.1.

### 1.3 Revocation is immediate

> **Changed v1.1.** Previously *refresh denial* — the in-flight JWT was honoured to its `exp`
> and only the next refresh was blocked, giving one TTL window of latency. Because §1.1 now
> resolves the caller per request, revocation lands on the next request instead.

- **Logout** (`POST /api/v1/auth/logout`): blacklists the token in TIM, then appends a
  `sessions` row (`jti`, `expires_at`, `reason='logout'`) as the audit record. Enforcement
  first, audit second, so a failed INSERT cannot block a revocation. Idempotent.
- **Admin revoke** (`POST /api/v1/users/{userId}/revoke-token`): INSERTs a new `users` row
  with `token_revoked_at = NOW()` (append-only). Every token that user holds is rejected from
  the next request onward, because the identity query compares the presented token's issuance
  time against that column.
- **Soft-delete** (`is_active = FALSE` on the latest row) and a **corrected `tara_sub`** are
  rejected by the same query, with the same immediacy.
- **Break-glass JWT**: hard-coded 600 s TTL (`BREAK_GLASS_JWT_TTL_SECONDS`).

The cost of immediacy is one indexed single-table read per request. Environments that would
rather trade revocation latency for throughput can move the permission set into the token and
drop the lookup; the schema supports either shape unchanged.

### 1.4 Channel routing

The gate has exactly four authentication channels, each with one credential type and one validation pipeline. There are no fallbacks between channels.

| Channel | Credential on the wire | Validated against | Hot-path DB lookup |
|---|---|---|---|
| Authority / Admin API | TIM-issued RS256 JWT, as `Authorization: Bearer` | TIM `GET /jwt/userinfo` (signature + blacklist), then the resolved `users` row for permissions | `users` by `tara_sub`, every request |
| Authority / Admin login (one-time) | TARA OIDC RS256 ID token | TIM performs the code exchange and validates against the TARA JWKS | none — TIM owns this hop; Ruuter is not involved |
| Platform API | mTLS X.509 client cert | Reverse proxy + cert chain | `platforms` by `(cert_subject, cert_serial)` |
| CronManager admin endpoints | Static Bearer | Literal compare against `ARCHIVE_OPS_TOKEN` env var | none |
| Gate-to-gate (G2G) | mTLS at AS4 access point | EU Trust Service trust list, OCSP/CRL fail-closed | none (peer gate cert) |

The break-glass channel (`POST /api/v1/auth/local-token`) is **default-disabled** (`LOCAL_ADMIN_FALLBACK_ENABLED=false`); when enabled, it issues a gate-signed JWT (`tara_sub='local-admin'`, 600 s TTL) that then follows the Authority/Admin path. Break-glass is therefore not a fifth channel — it's a recoverable backdoor onto channel 1.

### 1.5 Secrets never live in the container image

TARA client secret, break-glass JWT signing key, mTLS certificates, and the static `ARCHIVE_OPS_TOKEN` are all loaded at startup from a runtime secret (Kubernetes Secret / Vault). The container image is never the carrier.

---

## 2. Authorisation decision tree

The single decision tree that every request runs through, regardless of which channel it arrived on. Each leaf maps to an HTTP status + `efti.error.code`.

```mermaid
flowchart TD
    Req[Incoming request] --> Cred{Credential type?}
    Cred -- "Bearer JWT (Authority/Admin)" --> JWT[Validate token at TIM<br/>resolve latest users row<br/>by tara_sub<br/>check is_active and<br/>token_revoked_at]
    Cred -- "mTLS (Platform)" --> MTLS[Resolve active platforms<br/>by cert subject + serial]
    Cred -- "Static opsToken (CronManager)" --> OPS[Literal compare against env var]
    JWT -- invalid --> R401[401 TOKEN_INVALID]
    MTLS -- 0 / >1 --> R403P[403 FORBIDDEN_NO_PLATFORM<br/>or FORBIDDEN_MULTI_PLATFORM]
    OPS -- mismatch --> R403O[403 FORBIDDEN]
    JWT -- valid --> RoleCheck{Resolved users.roles<br/>contains required role?}
    RoleCheck -- no --> R403[403 FORBIDDEN]
    RoleCheck -- yes --> Subset{Authority subset request<br/>⊆ users.subsets?}
    Subset -- no --> R403S[403 FORBIDDEN_SUBSET]
    Subset -- yes --> Scope{"Admin write target<br/>∈ users.roles[ADMIN]?"}
    Scope -- no --> R403WA[403 FORBIDDEN_WRITE_ACCESS]
    Scope -- yes --> Allow[200 OK / 201 Created]
    MTLS -- 1 active --> Allow
    OPS -- match --> Allow
```

The canonical path × role × subset matrix is in [`docs/specs/permissions-matrix.md`](../../specs/permissions-matrix.md) §1; the canonical error catalog is in [`docs/specs/errors.json`](../../specs/errors.json).

---

## 3. Theme business value

- TARA authentication eliminates password management overhead and satisfies the Estonian e-government standard for production identity.
- Centralised identity management via TARA + `users` table.
- GDPR Art. 30 compliance — record of processing with audit log of every authorisation denial.

## 4. Rationale

Identity is federated (TARA owns it for Authority/Admin), cert-based (mTLS for Platform and G2G), or pinned-by-token (CronManager). Authorisation is computed from the DB-resolved row on every request — never from JWT claims directly — because the gate's authorisation snapshot must be current, not as-of-token-minting. The gate maintains no admin session state; this is what makes it horizontally scalable without session affinity and what makes revocation cheap (insert into `sessions` denylist or new `users` row with `token_revoked_at`).
