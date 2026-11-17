# Audit log

The gate writes an `audit_log` row for logins, admin reads and writes, and refused credentials.
This route reads them back, newest first, with filters.

`GET /admin/v1/audit` · on ruuter (G1 :27086) · admin JWT · source `DSL/Ruuter/admin/GET/v1/audit.yml` → `get_audit_log.sql`

Get `$TOKEN` with [dev-login](./auth.md#post-authdev-login).

## Request

| Header | Required | Value |
|---|---|---|
| `Authorization` | yes | `Bearer <token>` |

| Query parameter | Required | Type | Meaning |
|---|---|---|---|
| `resource` | no | string | Exact match on `resource`, for example `user`, `consignment`, `admin` |
| `resourceId` | no | string | Exact match on `resource_id` (a user id, dataset id, gate id …) |
| `userId` | no | uuid | Rows written for this acting user. Must be a UUID, else 502. |
| `from` | no | timestamp | `recorded_at >= from`. Any PostgreSQL timestamp literal: `2026-11-03`, `2026-11-03T16:02:00Z`, even `yesterday`. |
| `to` | no | timestamp | `recorded_at <= to`, same format |
| `limit` | no | number | Default 20, clamped to 0..1000 |
| `offset` | no | number | Default 0 |

All filters are combined with AND. Order: `recorded_at DESC, row_id DESC`.

## Responses

| Status | When | Body |
|---|---|---|
| 200 | always (also `[]`) | array of audit rows |
| 401 | no or bad token, no active user row | RFC 7807 `unauthorized` |
| 503 | TIM or ReSql unreachable during the guard check | RFC 7807 `service-unavailable` |
| 502 | ReSql error: `from`/`to` not a timestamp, `userId` not a UUID, `limit` not a number | `{"error":"Data layer unavailable"}` |

## The row

| Field | Type | Meaning |
|---|---|---|
| `rowId` | uuid | Row id |
| `recordedAt` | timestamp | When it was written |
| `action` | string | `<resource>.<verb>`, see the list below |
| `resource` | string | What kind of thing was touched |
| `resourceId` | string or null | Which one |
| `userId` | uuid or null | The acting admin (the guard's caller). `null` on logins, logouts and refused credentials. |
| `ipAddress` | always `null` in responses | See Notes |
| `details` | object | Action-specific JSON. Ids, counts and filters; for user writes `before`/`after` rows. Never request content or secrets. |

## What is audited

Every write is best-effort and post-commit (`docs/specs/logging-spec.md` §5.2): a failed audit
insert never fails or rolls back the request.

### Login and session

| `action` | `resource` | Written by | `details` |
|---|---|---|---|
| `user.login` | `user` | `auth/POST/callback.yml` | `outcome: success` + `personal_code`; or `outcome: failure` + `failure_reason` `tara_callback_failed` / `jwt_generation_failed` |
| `user.login` | `user` | `auth/POST/dev-login.yml` | the same with `dev_login: true`; extra `failure_reason` `invalid_personal_code` |
| `user.logout` | `user` | `auth/POST/logout.yml` | only on failure: `failure_reason: tim_blacklist_failed`, `tim_status` |
| `auth.auth_denied` | `auth` | `auth/GET/.guard.yml` | `status` (401) |

### Admin actions (all with `userId` = the admin)

| `action` | `resource` | `resourceId` | Route |
|---|---|---|---|
| `user.list` | `user` | – | `GET /admin/v1/users` |
| `user.read` | `user` | user id | `GET /admin/v1/users/{id}` |
| `user.create` | `user` | new user id | `POST /admin/v1/users` (`before: null`, `after`) |
| `user.update` | `user` | user id | `PUT /admin/v1/users/{id}` (`before`, `after`) |
| `user.delete` | `user` | user id | `DELETE /admin/v1/users/{id}` |
| `user.revoke_token` | `user` | user id | `POST /admin/v1/users/revoke-token/{id}` |
| `gate.list` / `gate.read` | `gate` | gate id on read | `GET /admin/v1/gates[/{id}]` |
| `platform.list` / `platform.read` | `platform` | platform id on read | `GET /admin/v1/platforms[/{id}]` |
| `authority.list` / `authority.read` | `authority` | authority id on read | `GET /admin/v1/authorities[/{id}]` |
| `consignment.list` | `consignment` | – | `GET /admin/v1/consignments` |
| `consignment.delete` | `consignment` | dataset id | `DELETE /admin/v1/consignments/{id}` (`triggered_by: admin`) |
| `consignment-counts.list` | `consignment-counts` | – | `GET /admin/v1/consignment-counts` |
| `consignment-summary.read` | `authority` | authority id | `GET /admin/v1/consignment-summary` |
| `audit.list` | `audit_log` | – | this route, with the filters used |
| `admin.auth_denied` | `admin` | – | `admin/.guard.yml`, any refused `/admin/**` call (`status`) |

Not audited: `GET /admin/v1/gates/own`, `GET /admin/v1/consignment-history`,
`POST /admin/v1/js-error`, `GET /auth/user` on success.

### Other surfaces

| `action` | `resource` | Written by |
|---|---|---|
| `efti-internal.auth_denied` | `efti-internal`, `efti-internal-authority` | `efti/{GET,POST}/api/v1/.guard.yml` and `…/authority/.guard.yml`: missing or wrong `X-Internal-Service-Token` |
| `platform.auth_denied` | `platform` | `platforms/.guard.yml`: no or unknown `X-Api-Key`, or a key that matches more than one platform |
| `xroad.auth_denied` | `xroad` | `Ruuter-xroad/xroad/.guard.yml` (and the `provide/v1` copy): missing or malformed `X-Road-Client`, missing or non-UUID `X-Road-Id`, unknown or ambiguous member code |
| `ops.auth_denied` | `ops` | `ops/.guard.yml`: wrong ops token |
| `search.no_peers` | `search` | `efti/POST/api/v1/authority/search.yml`: a cross-gate search with no peer gate in the registry |
| `as4.handler_failed` | `as4_message` | edelivery (`AsyncHandlers.kt`): an inbound AS4 handler failed after the peer was answered |

## Examples

### The latest rows

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/audit?limit=3" -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 979

[{"action":"consignment-summary.read","details":{"authority_id":"auth-xroad-tm","count":2},"ipAddress":null,"recordedAt":"2026-11-03T16:02:46.915823Z","resource":"authority","resourceId":"auth-xroad-tm","rowId":"8bf0d2c3-219a-4147-a65d-4773babe8f07","userId":"11111111-1111-1111-1111-111111111111"},{"action":"consignment-summary.read","details":{"authority_id":"auth-xroad-tm","count":0,"from":"2020-01-01","to":"2020-12-31"},"ipAddress":null,"recordedAt":"2026-11-03T16:02:46.852912Z","resource":"authority","resourceId":"auth-xroad-tm","rowId":"b606dedb-09a2-490f-a13c-527aeca87b7e","userId":"11111111-1111-1111-1111-111111111111"},{"action":"consignment-summary.read","details":{"authority_id":"auth-summary-none","count":1,"from":"2026-11-03","to":"2026-11-03"},"ipAddress":null,"recordedAt":"2026-11-03T16:02:46.800646Z","resource":"authority","resourceId":"auth-summary-none","rowId":"72baf67a-ad11-40ea-a48a-332b8158f40d","userId":"11111111-1111-1111-1111-111111111111"}]
```

### Everything that happened to one user

```bash
curl -s "http://127.0.0.1:27086/admin/v1/audit?resource=user&resourceId=da78ba38-1ac6-4b32-80eb-686484f6f304" \
  -H "Authorization: Bearer $TOKEN"
```

```json
[{"action":"user.read","details":{"user_id":"da78ba38-1ac6-4b32-80eb-686484f6f304"},"ipAddress":null,"recordedAt":"2026-11-03T16:05:46.062056Z","resource":"user","resourceId":"da78ba38-1ac6-4b32-80eb-686484f6f304","rowId":"3506a110-3436-411b-bd44-bc8a7ca218c2","userId":"11111111-1111-1111-1111-111111111111"},{"action":"user.revoke_token","details":{"user_id":"da78ba38-1ac6-4b32-80eb-686484f6f304"},"ipAddress":null,"recordedAt":"2026-11-03T16:05:45.943212Z","resource":"user","resourceId":"da78ba38-1ac6-4b32-80eb-686484f6f304","rowId":"c301d439-2568-4b53-be91-d8057a8f85cd","userId":"11111111-1111-1111-1111-111111111111"},{"action":"user.create","details":{"after":{"createdAt":"2026-11-03T16:05:45.667150Z","id":"da78ba38-1ac6-4b32-80eb-686484f6f304","isUserActive":true,"name":"Mari Maasikas","rowId":"0caab8e4-0e4f-4d65-a545-0bcc27a91e76","taraSub":"38505050039","tokenRevokedAt":null},"before":null,"user_id":"da78ba38-1ac6-4b32-80eb-686484f6f304"},"ipAddress":null,"recordedAt":"2026-11-03T16:05:45.676650Z","resource":"user","resourceId":"da78ba38-1ac6-4b32-80eb-686484f6f304","rowId":"e486b496-49f1-4ac3-8e18-7510aaf9be6c","userId":"11111111-1111-1111-1111-111111111111"}]
```

### One admin, one resource, a time window

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/audit?userId=11111111-1111-1111-1111-111111111111&resource=consignment&from=2026-11-03T16:02:00Z&to=2026-11-03T16:02:10Z&limit=2" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 618

[{"action":"consignment.list","details":{"count":28,"limit":"1000"},"ipAddress":null,"recordedAt":"2026-11-03T16:02:06.834128Z","resource":"consignment","resourceId":null,"rowId":"9703607c-369e-4dd8-866a-d7bfd34ba603","userId":"11111111-1111-1111-1111-111111111111"},{"action":"consignment.delete","details":{"dataset_id":"73314152-cbe2-4cb5-83f0-d19965bac2e6","triggered_by":"admin"},"ipAddress":null,"recordedAt":"2026-11-03T16:02:06.667987Z","resource":"consignment","resourceId":"73314152-cbe2-4cb5-83f0-d19965bac2e6","rowId":"5a020dca-be8e-4dc0-9907-cdadf8503415","userId":"11111111-1111-1111-1111-111111111111"}]
```

### Logins

```bash
curl -s "http://127.0.0.1:27086/admin/v1/audit?resource=user&limit=200" -H "Authorization: Bearer $TOKEN"
```

Shortened to the login and logout rows (`action`, `details`):

```text
user.logout  {"failure_reason": "tim_blacklist_failed", "outcome": "failure", "tim_status": 400}
user.logout  {"failure_reason": "tim_blacklist_failed", "outcome": "failure", "tim_status": 409}
user.login   {"dev_login": true, "outcome": "success", "personal_code": "48505050040"}
user.login   {"failure_reason": "tara_callback_failed", "outcome": "failure"}
user.login   {"dev_login": true, "failure_reason": "invalid_personal_code", "outcome": "failure"}
user.login   {"dev_login": true, "outcome": "success", "personal_code": "60001019906"}
```

### Refused admin calls

```bash
curl -s "http://127.0.0.1:27086/admin/v1/audit?resource=admin&limit=1" -H "Authorization: Bearer $TOKEN"
```

```json
[{"action":"admin.auth_denied","details":{"status":401},"ipAddress":null,"recordedAt":"2026-11-03T16:05:45.998240Z","resource":"admin","resourceId":null,"rowId":"0a5e6ea2-7f10-484c-b5df-6f06f3871493","userId":null}]
```

The row does not say which route or which token was refused.

### A malformed filter

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/audit?from=notadate" -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
content-length: 34

{"error":"Data layer unavailable"}
```

`?userId=abc` and `?limit=abc` answer the same 502. `?from=yesterday` is accepted (PostgreSQL
reads it as yesterday 00:00).

## Notes

- **`ipAddress` is always `null` in the response, even when it was stored.** The handlers copy
  `X-Forwarded-For` into the `inet` column `ip_address`. Tested: three calls with
  `X-Forwarded-For: 192.0.2.10/11/12` stored the addresses (checked with a `psql` SELECT on
  `audit_log`), but `GET /admin/v1/audit` returns `null` for those rows. `get_audit_log.sql`
  selects the `inet` column without a `::text` cast; ReSql apparently does not serialise `inet`.
  Without a proxy in front (the local stack), nothing sets `X-Forwarded-For` and the column stays
  empty anyway. In a deployment the UI nginx sets it.
- **Authority data access is not audited.** `permissions-matrix.md` §4 and its audit table require
  `identifier.search`, `dataset.deliver` and `followup.send` rows for every authority access
  (GDPR Art. 30, 7 years). No DSL route or service writes these actions today. The X-Road
  guards only audit refusals.
- `permissions-matrix.md` lists `user.access.denied` for authorisation denials. The DSL writes
  `<surface>.auth_denied` instead.
- The audit read is itself audited (`audit.list`), so every call adds a row.
- The table is append-only. There is no delete or update route.
