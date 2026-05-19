# EPIC 1 — User Management and RBAC

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme 1](README.md). Architecture: [identity-and-access/README.md](../../architecture/identity-and-access/README.md) (theme-wide rules) + [identity-and-access/user_management_and_rbac.md](../../architecture/identity-and-access/user_management_and_rbac.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** system administrator
**I WANT** role-based access control with resource-level filtering
**SO THAT** each user can only see and manage the resources they are permitted to access.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **API operations** | `POST/GET/PUT/DELETE /api/v1/users[/{userId}]` |
| | `POST /api/v1/users/{userId}/revoke-token` |
| | `POST /api/v1/auth/logout` |
| | `POST /api/v1/auth/local-token` |
| | Full request / response / error shapes: [`openapi.yaml`](../../specs/openapi.yaml) |
| **Schema** | `users` (`tara_sub`, `roles JSONB` restricted to `AUTHORITY` / `ADMIN`, `subsets TEXT[]`, `secret_hash TEXT NULL`, `token_revoked_at TIMESTAMPTZ`) |
| | `sessions` (JWT denylist) |
| | Partial index `(tara_sub, created_at DESC) WHERE tara_sub IS NOT NULL` |
| | Full schema: [`db/schema.sql`](../../specs/db/schema.sql) |
| **Error codes** | `TOKEN_INVALID` |
| | `FORBIDDEN` |
| | `FORBIDDEN_SUBSET` |
| | `FORBIDDEN_WRITE_ACCESS` |
| | `FORBIDDEN_NO_PLATFORM` |
| | `FORBIDDEN_MULTI_PLATFORM` |
| | `BAD_REQUEST_GENERAL` |
| | Full catalog: [`errors.json`](../../specs/errors.json) |
| **Access-check rules** | Full path × role × subset matrix: [`permissions-matrix.md`](../../specs/permissions-matrix.md) |
| **Architecture** | [identity-and-access/README.md](../../architecture/identity-and-access/README.md) (theme rules) + [identity-and-access/user_management_and_rbac.md](../../architecture/identity-and-access/user_management_and_rbac.md) (sub-architecture) |

## Acceptance Criteria

### Role management

**Business rules:**
- [ ] A new user inherits **only** the creator's roles. Exception: the Super Admin role can be granted only by an existing Super Admin.
- [ ] Listing users: Super Admin sees all; regular admin sees only users whose roles intersect their own.
- [ ] Deleting a user requires the target to be visible to the admin (same scope as listing).
- [ ] A user may be assigned multiple roles, and multiple Party IDs (`scope-IDs`) under a single role.
- [ ] An authority user's `subsets` must be a subset of the parent authority's `subsets`.
- [ ] An admin cannot delete their own account.
- [ ] `taraSub` is unique across **active** rows: creating a user with an already-active `taraSub` is rejected.

**Audit:**
- [ ] Every authorisation denial is logged with: caller user id, endpoint, denial reason, source IP, timestamp.

### Access control

**Path → role mapping** (canonical table in [`permissions-matrix.md`](../../specs/permissions-matrix.md) §1; summary here):

- [ ] `/api/v1/...` (Admin API) — caller's resolved `users.roles` must contain `ADMIN`.
- [ ] `/v1/identifiers/{identifier}`, `/v1/dataset/...`, `/v1/follow-up/{gateId}/...` (Authority API) — caller's resolved `users.roles` must contain `AUTHORITY`.
- [ ] `/v1/identifiers/{datasetId}` and the other Platform-API routes — mTLS-only; the cert subject DN + serial must resolve to exactly one active `platforms` row.
- [ ] Admin write operations check **both** that the caller has `ADMIN` AND that the target entity id is in the caller's `users.roles[ADMIN]` scope-IDs.

**Denial scenarios** (status codes and `efti.error.code` values in [`errors.json`](../../specs/errors.json)):

- [ ] Authority-role JWT calling an Admin endpoint (claims `roles` lacks `ADMIN`).
- [ ] Missing `Authorization` header on a JWT-protected route.
- [ ] Expired gate-JWT (`exp` past).
- [ ] Tampered JWT signature — no internal detail leaked to the caller.
- [ ] At login / refresh only: TARA `sub` does not resolve to any active `users` row — caller must be provisioned by an admin first.
- [ ] At login / refresh only: target user has been revoked (`users.token_revoked_at > tara_id_token.iat`), so the refresh is denied.
- [ ] At login / refresh only: token `jti` appears in `sessions` (logged out), so the refresh is denied.
- [ ] Platform mTLS cert subject + serial resolve to 0 active rows.
- [ ] Platform mTLS cert subject + serial resolve to >1 active row (operator misconfiguration).
- [ ] **(Future opt-in mode — not in the default profile)**: per-request denylist check fails — `jti` is in `sessions`, or `jwt.iat` predates `users.token_revoked_at`. Default profile waits for the JWT TTL to expire instead.

<!-- issue-body:end -->
