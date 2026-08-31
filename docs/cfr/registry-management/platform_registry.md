# EPIC 7 — Platform Registry Management (Admin API)

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Registry Management](README.md). Architecture: [registry-management/README.md](../../architecture/registry-management/README.md) (theme-wide rules) + [registry-management/platform_registry.md](../../architecture/registry-management/platform_registry.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** system administrator
**I WANT** to manage the eFTI platform registry
**SO THAT** platforms can register identifiers and authorities can retrieve datasets.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **API operations** | `GET/POST/PUT/DELETE /api/v1/platforms[/{platformId}]` |
| | `POST /api/v1/platforms/{platformId}/ping` |
| | Full request / response / error shapes: [`openapi.yaml`](../../specs/openapi.yaml) |
| **Schema** | `platforms` (append-only; logical id = `platforms.id`; latest row by `created_at` wins; `status='DELETED'` on latest = soft-delete; columns: `base_url`, `cert_subject`, `cert_serial`, `e_delivery_cert`, `api_key_hash`/`api_key_hint`/`api_key_generated_at` for the X-Api-Key credential — ADR-004) |
| | Full schema: [`db/schema.sql`](../../specs/db/schema.sql) |
| **Access-check rules** | Admin write scope-ID check on the platform's owning gate: [`permissions-matrix.md`](../../specs/permissions-matrix.md) §8.1 |
| **Error codes** | `BAD_REQUEST_GENERAL` |
| | `FORBIDDEN_WRITE_ACCESS` |
| | `GATEWAY_UNAVAILABLE` |
| | Full catalog: [`errors.json`](../../specs/errors.json) |
| **Cluster sync** | `LISTEN/NOTIFY` on channel `registry_change_platforms` — see [`non-functional.md`](../../specs/non-functional.md) §3 |
| **Architecture** | [../../architecture/registry-management/README.md](../../architecture/registry-management/README.md) (theme rules) + [../../architecture/registry-management/platform_registry.md](../../architecture/registry-management/platform_registry.md) (sub-architecture) |
| | [RA §1 System Actors](../../architecture/eFTI-Gate-Reference-Architecture.md#1-system-actors--components) |
| **Diagrams** | [`seq-10-platform-registration.mmd`](../../specs/diagrams/seq-10-platform-registration.mmd) |
| | [`state-03-platform-status.mmd`](../../specs/diagrams/state-03-platform-status.mmd) |

## Acceptance Criteria

**Business rules:**
- [ ] Listing: all platforms are visible to authenticated admins.
- [ ] All writes (create / update / delete) are INSERTs of a new `platforms` row sharing the same logical `id`. Latest row wins.
- [ ] Delete is **always** soft (`status='DELETED'` on the latest row). There is no force-delete and no purge. Identifiers previously registered by the platform remain queryable.
- [ ] A platform with `eDeliveryCert` set is callable via both REST and eDelivery AS4. Without it, REST only.
- [ ] Platform is always responsible for subsetting — the gate forwards `subsetId` to the platform's `/v1/datasets/{datasetId}` endpoint (ADR-003).
- [ ] Manual ping (`POST /api/v1/platforms/{platformId}/ping`) checks HTTP reachability to `baseUrl`; response carries `responseTimeMs`.

**Denial scenarios:**
- [ ] `POST` with an `id` whose latest row is active → conflict.
- [ ] `PUT` / `DELETE` on a logical id that doesn't exist → not found.
- [ ] Manual ping: platform unreachable within timeout → `502`-class.

## Cluster-sync contract

- [ ] On every commit of a `platforms` INSERT, the application emits `NOTIFY registry_change_platforms, '<id>'` from the same transaction (no DB-side trigger). All gate nodes hold an open `LISTEN` on this channel and reload the latest row for the affected id within ≤ 500 ms.

<!-- issue-body:end -->
