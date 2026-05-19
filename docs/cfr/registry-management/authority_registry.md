# EPIC 8 — Authority Registry Management (Admin API)

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Registry Management](README.md). Architecture: [registry-management/README.md](../../architecture/registry-management/README.md) (theme-wide rules) + [registry-management/authority_registry.md](../../architecture/registry-management/authority_registry.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** system administrator
**I WANT** to manage the registry of Competent Authorities
**SO THAT** authority users have controlled access to eFTI data.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **API operations** | `GET/POST/PUT/DELETE /api/v1/authorities[/{authorityId}]` |
| | Full request / response / error shapes: [`openapi.yaml`](../../specs/openapi.yaml) |
| **Schema** | `authorities` (append-only; logical id = `authorities.id`; latest row by `created_at` wins; `is_active=FALSE` on latest = soft-delete; columns: `country_code`, `name`, `subsets TEXT[]`) |
| | Full schema: [`db/schema.sql`](../../specs/db/schema.sql) |
| **Access-check rules** | Admin write scope-ID check on the authority's owning gate; subset filtering rules for authority users: [`permissions-matrix.md`](../../specs/permissions-matrix.md) |
| **Error codes** | `BAD_REQUEST_GENERAL` |
| | `INVALID_SUBSET` |
| | `FORBIDDEN_SUBSET` |
| | `FORBIDDEN_WRITE_ACCESS` |
| | Full catalog: [`errors.json`](../../specs/errors.json) |
| **Cluster sync** | `LISTEN/NOTIFY` on channel `registry_change_authorities` — see [`non-functional.md`](../../specs/non-functional.md) §3 |
| **Architecture** | [RA §2.3 Data Subsets](../../architecture/eFTI-Gate-Reference-Architecture.md#23-data-subsets) |
| **Diagrams** | [`seq-11-authority-registration.mmd`](../../specs/diagrams/seq-11-authority-registration.mmd) |
| | [`state-04-authority-status.mmd`](../../specs/diagrams/state-04-authority-status.mmd) |
| **Architecture** | [../../architecture/registry-management/README.md](../../architecture/registry-management/README.md) (theme rules) + [../../architecture/registry-management/authority_registry.md](../../architecture/registry-management/authority_registry.md) (sub-architecture) |

## Acceptance Criteria

**Business rules:**
- [ ] Listing: Super Admin sees all authorities; a regular Admin sees only authorities whose owning gate is in their `users.roles[ADMIN]` scope-IDs.
- [ ] All writes (create / update / delete) are INSERTs of a new `authorities` row sharing the same logical `id`. Latest row wins.
- [ ] Delete is **always** soft (`is_active=FALSE` on the latest row). User rows referencing the authority remain queryable. There is no purge.
- [ ] Subset assignment: every value in `authorities.subsets[]` must be a valid subset code (`EU01`–`EU07`).
- [ ] If a `PUT` removes a subset from `authorities.subsets`, every existing authority user whose `users.subsets` is no longer a subset of the parent's becomes immediately denied on their next request (per the `FORBIDDEN_SUBSET` rule). The admin must follow up with `PUT /api/v1/users/{userId}` to trim those users' subsets.

**Denial scenarios:**
- [ ] `POST` with an `id` whose latest row is active → conflict.
- [ ] `PUT` / `DELETE` on a logical id that doesn't exist → not found.
- [ ] `POST` / `PUT` carries a subset value outside the canonical set → `INVALID_SUBSET`.
- [ ] Admin writes to an authority whose owning gate is **not** in the caller's `users.roles[ADMIN]` scope-IDs → `FORBIDDEN_WRITE_ACCESS`.

## Cluster-sync contract

- [ ] On every commit of an `authorities` INSERT, the application emits `NOTIFY registry_change_authorities, '<id>'` from the same transaction (no DB-side trigger). All gate nodes hold an open `LISTEN` on this channel and reload the latest row for the affected id within ≤ 500 ms — subset removals therefore take effect in real time, not on next login.

<!-- issue-body:end -->
