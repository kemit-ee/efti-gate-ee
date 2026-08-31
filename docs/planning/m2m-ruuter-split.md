# Ruuter split: UI-facing (`efti`) vs machine-to-machine (`m2m`)

Working plan for branch `refactor/split-m2m-ruuter`. Status as of the rename commit.

## Motivation

`POST/api/v1/.guard` on the `efti` Ruuter is a forced no-op because that one
directory mixes three security domains:

| Domain | Credential | Caller |
|---|---|---|
| Admin UI API | admin JWT (TIM/TARA) | browser |
| Authority API | (per ADR-004) X-Road client header **or** eDelivery, no UI | authority backends / peer gates |
| Platform API | `X-Api-Key` header → `platforms` registry (ADR-004, 2026-08-25) | eFTI platforms |
| Peer-gate G2G | AS4 via the `edelivery` service (loopback) | peer eFTI gates |

Admin enforcement is currently copy-pasted as an inline `check-admin-authority`
call at the top of `gates.yml`, `users.yml`, `platforms.yml`, `authorities.yml`,
`users/revoke-token.yml`. DRY violation and easy to forget on a new route.

## Target

Two Ruuter instances, each with uniform, meaningful directory guards:

### `efti` (port 8086) — UI-facing only
- `POST/api/v1/auth/*` — public (login/callback/logout)
- `POST/api/v1/.guard` — **admin JWT** (replaces the no-op; same shape as PUT/DELETE)
- `GET/api/v1/.guard` — any authenticated user (unchanged)
- `PUT` / `DELETE` — admin (unchanged)
- Routes: `gates`, `users`, `platforms`, `authorities`, `users/revoke-token`,
  and all admin GET reads (`audit`, `status`, `user`, `gates/own`, list endpoints)
- Inline `check_admin` blocks deleted from the 4 POST files.

### `m2m` (port 8087) — machine-to-machine
```
m2m/POST/
  xroad/.guard        X-Road client header + authorities registry lookup   [done]
  xroad/v1/echo.yml                                                        [done]
  edelivery/.guard    peer-gate AS4 — reached only via the edelivery svc (loopback trust)
  platform/.guard     X-Api-Key header → sha256 → platforms registry (active rows)
  authority/.guard    per ADR-004 (X-Road or eDelivery); starts as loopback trust
```

## Endpoint inventory & target

| Current (`efti`) | Target (`m2m`) | Notes |
|---|---|---|
| `POST api/v1/dataset-xml` | `POST edelivery/dataset-xml` | `template:` → `edelivery/dataset` |
| `POST api/v1/dataset` | `POST authority/dataset` | forwards to platform / remote gate |
| `POST api/v1/follow-up-xml` | `POST edelivery/follow-up-xml` | `template:` → `edelivery/follow-up` |
| `POST api/v1/follow-up` | `POST authority/follow-up` | |
| `POST api/v1/consignments-xml` | `POST edelivery/consignments-xml` | `template:` → `edelivery/consignments` |
| `POST api/v1/consignments` | `POST platform/consignments` | "Accept consignment XML from Platforms (External API)" |
| `POST api/v1/consignments/search-xml` | `POST edelivery/consignments-search-xml` | `template:` → search |
| `POST api/v1/consignments/search` | `POST platform/consignments-search` | plain DB query, no auth today |
| `POST api/v1/authority/search` | `POST authority/search` | `template:` → consignments/search |
| `POST api/v1/authority/follow-up` | `POST authority/follow-up-notify` | stub today |
| `POST api/v1/ping` | `POST platform/ping` | 204 reachability probe |
| `POST api/v1/gates/:id/ping` | `POST m2m/... TBD` | admin-triggered? or peer? **OPEN** |
| `POST api/v1/platforms/:id/ping` | `POST m2m/... TBD` | admin-triggered? **OPEN** |

### Open questions before moving

1. **`gates/:id/ping`, `platforms/:id/ping`** — who calls these? If the admin UI
   ("test connectivity" button) they stay on `efti` under the admin guard. If a
   scheduled job / CronManager, they belong on `m2m` behind an ops credential.
2. **GET-side authority endpoints** — `GET api/v1/authority/dataset` (Authority
   API dataset retrieval, TARA JWT today), `GET api/v1/follow-up`,
   `GET api/v1/consignments`. If authority has no UI these are `m2m` too, which
   means a `GET` tree under `m2m` with its own guards. Currently scoped as
   "stays on `efti`, already JWT-guarded" — confirm.
3. **`template:` cross-project** — every `*-xml` endpoint and `authority/search`
   use Ruuter `template:` includes. Caller + callee must sit in the same project;
   all are in the move set, but the `template:` path strings need rewriting to the
   new relative locations and this must be verified against a running engine.

## Platform API-key auth (ADR-004)

- Liquibase `changelog/20260825-platform-api-key.sql`:
  - `CREATE EXTENSION IF NOT EXISTS pgcrypto;`
  - `ALTER TABLE platforms ADD COLUMN api_key_hash BYTEA;`
  - dev-context backfill: `UPDATE platforms SET api_key_hash = digest('mock-secret-key','sha256') WHERE id = 'mock';`
- ReSQL `get_platform_by_api_key.sql` — latest non-DELETED row where
  `api_key_hash = digest(:apiKey,'sha256')`; 401 on none, 403 on >1.
- `insert_platform` / `update_platform` / `update_platform_ping` / `soft_delete_platform`
  carry `api_key_hash` forward (append-only table); `update_platform` takes an
  optional plaintext `apiKey` and re-hashes.
- `get_platforms` / `get_platform_by_id` expose `api_key_hash IS NOT NULL AS has_api_key`,
  never the hash.
- POST/PUT `platforms.yml` accept `apiKey` in the body; openapi + api_endpoints updated.
- `m2m/POST/platform/.guard` reads `X-Api-Key`, calls `get_platform_by_api_key`.

## Wiring

- `code/edelivery/src/RuuterClient.kt` — `baseUrl` → `RUUTER_M2M_URL` + new paths
  (`/m2m/edelivery/consignments-xml`, `/m2m/edelivery/consignments-search-xml`,
  `/m2m/edelivery/dataset-xml`, `/m2m/edelivery/follow-up-xml`).
- `compose.yml` — `edelivery.environment` gains `RUUTER_M2M_URL=http://ruuter-m2m:8087`.
- `tests/http/*.http` — `datasets.http`, `authority-api.http`, `consignments.http`,
  `ping.http`, `mock-platform.http` retargeted.

## Docs to update

- `docs/architecture/decisions/004-platform-api-key.md` — **new ADR** (Rainer Türner,
  Sten Viljus, Anton Keks, 2026-08-25).
- `docs/architecture/decisions/005-m2m-ruuter-split.md` — **new ADR** (this split).
- `docs/specs/openapi.yaml` — split servers, `X-Api-Key` security scheme, path moves.
- `docs/specs/api_endpoints.md` — regenerate the route table.
- `docs/specs/permissions-matrix.md` — Platform API row: mTLS → `X-Api-Key`.
- `docs/architecture/user-interfaces/{admin_ui,authority_ui}.md`,
  `docs/architecture/integrations/{edelivery_as4,x_road_integration,README}.md`,
  `docs/architecture/security-and-compliance/README.md` — reference the two Ruuters.
- `docs/cfr/**` — AC mirror, update where they name the transport/credential.
