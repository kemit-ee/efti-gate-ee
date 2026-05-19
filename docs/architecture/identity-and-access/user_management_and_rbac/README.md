# Architecture: User Management and RBAC

> Sub-architecture for the RBAC surface. For overarching rules that apply across the whole theme (authorisation snapshot in DB, stateless Resource Server, append-only revocation, channel routing, secret loading) see [theme README](../README.md). AC are in [`docs/cfr/identity-and-access/user_management_and_rbac/README.md`](../../../cfr/identity-and-access/user_management_and_rbac/README.md).

## 1. Role and scope model

Two role types, one subset axis, one scope-ID axis:

| Concept | Storage | Purpose |
|---|---|---|
| **Role** | `users.roles JSONB`, restricted to `AUTHORITY` or `ADMIN` (plus the reserved Super Admin marker) | What kinds of endpoints the user may call. |
| **Subset** | `users.subsets TEXT[]` | Which slice of authority data the user may read — must be a subset of the *parent* authority's subsets. |
| **Scope-ID** | inside `users.roles[ADMIN]` | Which entities (gates) an admin may write to. |
| **Platform binding** | `platforms.cert_subject`, `platforms.cert_serial` | Which platform an mTLS caller is, resolved entirely from the cert. No `platforms` reference in `users`. |

A user can hold multiple roles and multiple Party IDs under one role. The platform identity is *not* a `users` row — it is a `platforms` row resolved from the client certificate.

## 2. Admin write-access — the two-gate check

Admin write operations require **two** checks, both must pass:

1. The caller's resolved `users.roles` must contain `ADMIN`. (Role-type check.)
2. The target entity's id must appear in the caller's `users.roles[ADMIN]` scope-IDs. (Scope check.)

Failing (1) → `403 FORBIDDEN`. Failing (2) → `403 FORBIDDEN_WRITE_ACCESS`. The two error codes are distinguishable on the wire so an operator misconfiguration (admin without the right scope-ID) is debuggable separately from a wrong-role attempt.

An admin **cannot delete their own account**. The check is enforced at the application layer, not at the DB layer, to keep the schema generic.

## 3. New-user role inheritance

A new user inherits **only** the creator's roles. Exception: Super Admin can be granted only by an existing Super Admin. This rule prevents privilege escalation by horizontal compromise — a regular admin cannot mint a Super Admin.

## 4. Authority subset constraint

An authority user's `subsets` must be a subset (set inclusion) of the parent authority's `subsets`. Enforced at write time on `POST /api/v1/users`; verified at every read of the user's permission set. The check is "request subset ⊆ user subsets ⊆ parent authority subsets" — chained at request time.

## 5. Audit invariant

Every authorisation denial is logged with: caller user id (if resolved, else `null`), endpoint, denial reason, source IP, timestamp. This is the GDPR Art. 30 record-of-processing input.

## 6. `tara_sub` uniqueness

`tara_sub` is unique across **active** rows (`is_active = TRUE`). Inserting a user with a `tara_sub` that already resolves to an active row → reject. Because the table is append-only, a `tara_sub` may exist on multiple historical rows; uniqueness applies only to the current latest-row-by-`created_at` projection.

---

## See also

- [`docs/specs/permissions-matrix.md`](../../../specs/permissions-matrix.md) §1 — canonical path × role × subset matrix.
- [`docs/specs/db/schema.sql`](../../../specs/db/schema.sql) — `users`, `sessions` table definitions.
- [`docs/specs/errors.json`](../../../specs/errors.json) — `FORBIDDEN`, `FORBIDDEN_SUBSET`, `FORBIDDEN_WRITE_ACCESS`, `FORBIDDEN_NO_PLATFORM`, `FORBIDDEN_MULTI_PLATFORM`, `TOKEN_INVALID`, `BAD_REQUEST_GENERAL`.
- [`docs/specs/diagrams/flow-02-authorization-check.mmd`](../../../specs/diagrams/flow-02-authorization-check.mmd) — full decision-tree source.
