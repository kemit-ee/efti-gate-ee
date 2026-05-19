# EPIC 2 — Authentication

## Changes

- _Initial state. Change tracking begins at v1.0.0 (not yet released)._

> Part of [Theme 1](README.md). Architecture: [identity-and-access/README.md](../../architecture/identity-and-access/README.md) (theme-wide rules) + [identity-and-access/authentication.md](../../architecture/identity-and-access/authentication.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** system administrator or authority user<br>
**I WANT** secure authentication mechanisms (TARA, JWT, mTLS)<br>
**SO THAT** only authorized parties can access the gate.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **API operations** | `POST /api/v1/auth/logout` |
| | `POST /api/v1/auth/local-token` (default-disabled break-glass) |
| | `POST /api/v1/users/{userId}/revoke-token` |
| | `POST /services/fast` (gate-to-gate fast protocol, mTLS) |
| | Full request / response / error shapes: [`openapi.yaml`](../../specs/openapi.yaml) |
| **Schema** | `users` (`tara_sub`, `secret_hash` for break-glass only, `token_revoked_at`) |
| | `sessions` (JWT denylist: `jti`, `revoked_at`, `reason`) |
| | Full schema: [`db/schema.sql`](../../specs/db/schema.sql) |
| **Error codes** | `TOKEN_INVALID` |
| | `FORBIDDEN_NO_PLATFORM` |
| | `FORBIDDEN_MULTI_PLATFORM` |
| | Full catalog: [`errors.json`](../../specs/errors.json) |
| **Access-check rules** | Credential routing + JWT validation pipeline: [`permissions-matrix.md`](../../specs/permissions-matrix.md) §1.1, §8.1 |
| **Environment** | `TARA_OIDC_DISCOVERY_URL`, `TARA_CLIENT_ID`, `TARA_CLIENT_SECRET`, `TARA_JWKS_CACHE_SECONDS`, `ARCHIVE_OPS_TOKEN`, `LOCAL_ADMIN_FALLBACK_ENABLED`, `BREAK_GLASS_JWT_SIGNING_KEY`, `BREAK_GLASS_JWT_TTL_SECONDS` — see [`non-functional.md`](../../specs/non-functional.md) §4.1 |
| **Diagrams** | [`seq-12-user-authentication.mmd`](../../specs/diagrams/seq-12-user-authentication.mmd) |
| | [`seq-16-mtls-fast-protocol.mmd`](../../specs/diagrams/seq-16-mtls-fast-protocol.mmd) |
| | [`flow-02-authorization-check.mmd`](../../specs/diagrams/flow-02-authorization-check.mmd) |
| **Architecture** | [identity-and-access/README.md](../../architecture/identity-and-access/README.md) (theme rules) + [identity-and-access/authentication.md](../../architecture/identity-and-access/authentication.md) (sub-architecture) |

## Acceptance Criteria

### Admin UI authentication (TARA OIDC → JWT, gate is Resource Server)

**Business rules:**
- [ ] The TARA OIDC code-exchange happens in the admin browser (or a thin client-side login helper), not in the gate. The gate validates JWTs but never exchanges codes itself.
- [ ] No server-side admin session, no `session_id` cookie. The JWT is the session.
- [ ] The `sessions` table is a JWT denylist (`jti`, `revoked_at`, `reason`) — **not** a session store.
- [ ] Multi-node deployment requires no session affinity: any node can validate any TARA JWT against the cached JWKS + the denylist.

**Denial scenarios:**
- [ ] JWT signature invalid.
- [ ] `iss` claim does not match the gate's signing-key issuer (for gate-JWT) or the configured TARA issuer (for TARA ID token at login/refresh).
- [ ] `aud` claim does not match the expected audience.
- [ ] `exp` is in the past.
- [ ] `sub` does not resolve to any active `users` row (checked only at login / refresh — see Revocation contract below).

### Authority and Platform API authentication

**Business rules:**
- [ ] An authority/admin user must be provisioned via `POST /api/v1/users` (carrying their `taraSub`) before they can authenticate. The gate does not auto-provision on first inbound JWT — it rejects.
- [ ] Authority/Admin → TARA JWT (validated as above).
- [ ] Platform → mTLS only; the cert subject DN + serial must resolve to exactly one active `platforms` row.

**Denial scenarios** (in addition to the JWT-validation ones above):
- [ ] Platform mTLS cert subject + serial resolves to >1 active `platforms` row (operator misconfiguration).

### Gate-to-gate fast protocol

**Business rules:**
- [ ] `POST /services/fast` is **mTLS-only**. There is no `X-API-Key` fallback.
- [ ] The presenting gate's certificate must chain to a trusted CA (EU Trust Service trust list); OCSP / CRL revocation checks **fail closed**.

**Denial scenarios:**
- [ ] Certificate from an unknown CA → TLS handshake fails; logged at WARN with caller IP.
- [ ] Revoked certificate (OCSP / CRL) → connection refused; logged.
- [ ] `X-API-Key` header presented in lieu of mTLS → unauthenticated; the header is never honoured.

### Authentication contract

- [ ] JWT signing: **RS256**. Both the gate-JWT signing key and the break-glass JWT signing key are loaded from a runtime secret (K8s Secret / vault) — never baked into the container image.
- [ ] JWT validation on the hot path checks signature + claims (`iss`, `aud`, `exp`) against the gate's own signing key only — **no DB query per request in the default profile**.
- [ ] JWT TTL is configurable per use case: long-lived (admin sessions), short-lived (API calls), or one-shot (single-use step-up). Pick the TTL based on the desired revocation latency.
- [ ] At login and refresh, the TARA OIDC ID token is validated against the cached TARA JWKS (RS256, `iss`, `aud`, `exp`, `sub`), the `users` row is resolved by `tara_sub`, and the gate mints its own JWT carrying `tara_sub`, `roles`, `subsets`, `scopes`.
- [ ] Clock-skew tolerance ±60 s per [`non-functional.md`](../../specs/non-functional.md) §4.
- [ ] mTLS certificates (Platform-API and AS4 access point) loaded from runtime secret at startup — never in the container image.

### Revocation contract (default profile: refresh denial)

- [ ] **Logout** (`POST /api/v1/auth/logout`): inserts `(jti, revoked_at, reason='logout')` into `sessions`. The currently-held gate-JWT lives until `exp`; subsequent refresh attempts for the same `jti` fail.
- [ ] **Admin revoke** (`POST /api/v1/users/{userId}/revoke-token`): inserts a new `users` row with `token_revoked_at = NOW()`. Append-only: the previous row is unchanged. The currently-held gate-JWT for that user lives until `exp`; subsequent refresh attempts fail because the refresh pipeline rejects a TARA ID token whose `iat` predates `users.token_revoked_at`.
- [ ] **Break-glass** (`POST /api/v1/auth/local-token`): default-disabled (`LOCAL_ADMIN_FALLBACK_ENABLED=false`). When enabled, validates against the single bcrypt local-admin row in `users.secret_hash` and issues a gate-signed JWT with hard-coded 600 s TTL (`BREAK_GLASS_JWT_TTL_SECONDS`).
- [ ] Revocation latency is bounded by the JWT TTL. For scopes where TTL-bounded latency is unacceptable, short-lived or one-shot JWTs are minted.
- [ ] **(Future opt-in mode — not in the default profile)**: a per-request denylist check against `sessions` (by `jti`) and `users.token_revoked_at` (by `jwt.iat`) can be enabled, restoring immediate revocation at the cost of two hot-path DB queries per request. Schema already supports this; toggle is a runtime config item.

<!-- issue-body:end -->
