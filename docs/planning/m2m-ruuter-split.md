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

### Resolved

1. **`gates/:id/ping`, `platforms/:id/ping`** — both a UI action and (future) an
   automated job. Kept on `efti` under the admin guard for the UI button; the
   ops-token `m2m` path for the scheduled job is a **follow-up**, not in this branch.
2. **GET-side authority endpoints** — `GET authority/dataset` and `GET follow-up`
   moved to `m2m/GET/authority/`. `GET consignments` stays on `efti` (admin list).
3. **Authority guard** — X-Road-Client header when present (validated against the
   authorities registry), otherwise allowed as an eDelivery/loopback call.
4. **Platform auth** — `X-Api-Key` header, SHA-256 hash lookup (ADR-004).

### Still to verify against a running stack

- `template:` resolution across the new `m2m` paths (`edelivery/v1/*-xml` →
  `authority/v1/*` and `platform/v1/*`).
- Guard resolution: does `m2m/POST/<domain>/.guard` cover `<domain>/v1/*`, and is a
  route with no ancestor `.guard` allowed or denied?
- `digest(:apiKey,'sha256')` bytea comparison + `app` role privilege on pgcrypto.

### Docs debt (prose still keyed to the old design)

- `permissions-matrix.md` §3.1–§3.3, §7, §8 diagrams and error tables still describe
  mTLS/`cert_subject` — a superseding note is in place at the top of §3.1 and the
  doc header, but the bodies want a full rewrite.
- `openapi.yaml` paths still use the pre-split single-server layout (documented in
  the file header + ADR-005 routing table); `platformApiKey` scheme and the
  generate-key path are in.
- `docs/cfr/**` AC mirror not yet touched.

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
