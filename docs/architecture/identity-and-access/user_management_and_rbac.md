# Architecture: User Management and RBAC

## Changes

- **v1.1** — RBAC storage is two boolean columns, not an array. `users.roles TEXT[]` (`ADMIN` /
  `AUTHORITY`) is replaced by `users.is_admin` + `users.is_authority`. The model is unchanged: the
  Admin API requires `is_admin`; the Authority API allows `is_admin` OR `is_authority`.
- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the RBAC surface. For overarching rules that apply across the whole theme (authorisation snapshot in DB, stateless Resource Server, append-only revocation, channel routing, secret loading) see [theme README](README.md). AC are in [`docs/cfr/identity-and-access/user_management_and_rbac.md`](../../cfr/identity-and-access/user_management_and_rbac.md).

## 1. Role and scope model

The user entity is identified by `tara_sub` (the TARA OIDC `sub` claim). The gate resolves the caller by matching the JWT's `sub` claim against `users.tara_sub`.

| Concept | Storage | Purpose |
|---|---|---|
| **User identity** | `users.tara_sub` | The TARA personal identification code (Estonian PIC) that identifies the user. |
| **Admin access** | `users.is_admin` | `TRUE` = full Admin API (gate/platform/authority/user CRUD) **and** the Authority API. |
| **Authority access** | `users.is_authority` | `TRUE` = Authority API only (dataset search, follow-up, authority-search). The authority guards allow `is_admin` OR `is_authority`. |
| **Platform binding** | `platforms.e_delivery_cert` | Which platform an mTLS caller is, resolved from the cert. No `platforms` reference in `users`. |

The platform identity is *not* a `users` row — it is a `platforms` row resolved from the client certificate.

## 2. Admin write-access

Admin write operations require the caller to be authenticated with a valid TARA-issued JWT that resolves to an active `users` row.

An admin **cannot delete their own account**. The check is enforced at the application layer, not at the DB layer, to keep the schema generic.

## 3. New-user creation

A new user is created with `tara_sub`, `name`, and the `is_admin` / `is_authority` flags (both default to `FALSE` when omitted). The user is identified by their TARA personal identification code.

## 4. User identification

The user is identified by their `tara_sub` (TARA personal identification code). The gate resolves the caller by matching the JWT's `sub` claim against `users.tara_sub`. Only active users (`is_active = TRUE`) can authenticate.

## 5. Audit invariant

Every authorisation denial is logged with: caller user id (if resolved, else `null`), endpoint, denial reason, source IP, timestamp. This is the GDPR Art. 30 record-of-processing input.

## 6. `tara_sub` uniqueness

`tara_sub` is unique across **active** rows (`is_active = TRUE`). Inserting a user with a `tara_sub` that already resolves to an active row → reject. Because the table is append-only, a `tara_sub` may exist on multiple historical rows; uniqueness applies only to the current latest-row-by-`created_at` projection.

---

## See also

- [`docs/specs/permissions-matrix.md`](../../specs/permissions-matrix.md) §1 — canonical path × role × subset matrix.
- [`docs/specs/db/schema.sql`](../../specs/db/schema.sql) — `users`, `sessions` table definitions.
- [`docs/specs/errors.json`](../../specs/errors.json) — `FORBIDDEN`, `FORBIDDEN_SUBSET`, `FORBIDDEN_WRITE_ACCESS`, `FORBIDDEN_NO_PLATFORM`, `FORBIDDEN_MULTI_PLATFORM`, `TOKEN_INVALID`, `BAD_REQUEST_GENERAL`.
- [`docs/specs/diagrams/flow-02-authorization-check.mmd`](../../specs/diagrams/flow-02-authorization-check.mmd) — full decision-tree source.
