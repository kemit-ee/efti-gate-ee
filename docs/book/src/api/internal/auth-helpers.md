# Auth helpers

Two routes that turn a gate JWT into a user row. Ruuter's own guards call them over the loopback
for every admin and every authenticated `auth/GET` request.

| Route | Called by | Source |
|---|---|---|
| `POST /efti/internal/check-admin-authority` | `DSL/Ruuter/admin/.guard.yml` (all of `/admin/**`) | `DSL/Ruuter/efti/POST/internal/check-admin-authority.yml` |
| `POST /efti/internal/check-user-authority` | `DSL/Ruuter/auth/GET/.guard.yml` (`GET /auth/user`) | `DSL/Ruuter/efti/POST/internal/check-user-authority.yml` |

There are no roles (permissions matrix v1.5). An active `users` row for the caller's personal code
is full admin access, so both routes check the same things. The admin variant adds one check
(`loginExpireDate`, see below).

`POST /efti/internal/check-admin-authority` and `POST /efti/internal/check-user-authority` · on ruuter (G1 :27086) · Bearer JWT, no service credential · source above

## Reachability

`efti/POST/internal/.guard.yml` accepts every request; it exists only so the folder has a guard.
The routes are protected by the network: the guards call `[#RUUTER_URL]` (`http://ruuter:8086`),
`docker/ui/nginx.conf` answers `404` for `/efti/internal/`, and `ruuter` has no public port. In the
local stack port 27086 is published on `127.0.0.1`, so you can call them directly.

## Request

| Header | Required | Value |
|---|---|---|
| `Authorization` | yes | `Bearer <JWT>` (case-insensitive `bearer `) |
| `X-Request-Id` | no | correlation; the guards forward the caller's |
| `X-Forwarded-For` | no | client address; the guards forward it, it is only logged |

No body is read. A cookie is not read either: the route takes the Bearer value and sends it to TIM
as `cookie: eftiJwtCookie=<token>`.

## What the routes check

1. `Authorization` is present, starts with `Bearer `, and the token is not empty.
2. TIM `GET /jwt/userinfo` with the token as the `eftiJwtCookie` cookie. TIM validates signature
   and expiry, and its own denylist (logout).
3. The userinfo has a non-empty `claims.personalCode` and `issued_at`.
4. Admin only: if userinfo has a parseable `loginExpireDate` in the past, deny.
5. ReSql `check_user_auth` with `tara_sub = personalCode` and `token_issued_at = issued_at`. It
   takes the latest row per user id (append-only `users`) and returns it only if the user is
   active and `token_revoked_at` is null or older than `issued_at`.
6. Exactly one row → `200` with it.

## Responses

| Status | When | Body |
|---|---|---|
| 200 | all checks pass | `{"id": "<user uuid>", "name": "…", "taraSub": "…"}` |
| 401 | no or non-Bearer header; empty token; TIM answered 4xx; no `personalCode` or `issued_at`; login expired (admin); no active user row; token issued before `token_revoked_at` | `{"type":"https://api.efti.ee/errors/unauthorized","title":"Unauthorized","status":401,"detail":"Token is missing, invalid, expired, or the user has no active gate account."}` |
| 503 | TIM unreachable or ≥ 500; ReSql unreachable or ≥ 300 | `{"type":"https://api.efti.ee/errors/service-unavailable","title":"Service Unavailable","status":503,"detail":"An identity dependency is temporarily unreachable. Retry the request."}` |

The calling guard returns the helper's body and status as `application/problem+json` and writes an
`admin.auth_denied` or `auth.auth_denied` audit row. A transport failure of the helper itself
(status 0) goes to the guard's own unavailable branch.

## Examples

The examples use a test user created for this page (`taraSub 38505047904`, id
`2a70be0b-c95c-4ccf-af3e-45a0f48219e5`), not the dev admin. Its JWT came from the dev login:

```bash
UT=$(curl -s -X POST http://127.0.0.1:27086/auth/dev-login -H 'Content-Type: application/json' \
  -d '{"personalCode":"38505047904","firstName":"Doc","lastName":"Writer"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
```

### Valid token, active user

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/internal/check-user-authority \
  -H "Authorization: Bearer $UT" -H "X-Request-Id: $(cat /proc/sys/kernel/random/uuid)"
```

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 0cdd6acf7489b523b1f3b3d817a6ce09
content-length: 91

{"id":"2a70be0b-c95c-4ccf-af3e-45a0f48219e5","name":"Doc Writer E","taraSub":"38505047904"}
```

`check-admin-authority` with the same token gave the same `200` and body.

### No header, a garbage token, or the token as a cookie

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/internal/check-user-authority
curl -s -i -X POST http://127.0.0.1:27086/efti/internal/check-admin-authority -H 'Authorization: Bearer garbage'
curl -s -i -X POST http://127.0.0.1:27086/efti/internal/check-admin-authority -H "Cookie: eftiJwtCookie=$UT"
```

All three:

```http
HTTP/1.1 401 Unauthorized
content-type: application/json
content-length: 173

{"detail":"Token is missing, invalid, expired, or the user has no active gate account.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

### Valid TIM token, no users row

A dev-login JWT for a personal code without a `users` row (`38001085718`):

```http
HTTP/1.1 401 Unauthorized
content-type: application/json
x-trace-id: f26ba0de53325d4d64731c60ac511d0c
content-length: 173

{"detail":"Token is missing, invalid, expired, or the user has no active gate account.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

### Revoked token

The admin revokes the test user's tokens
([Users](../admin/users.md), `POST /admin/v1/users/revoke-token/{id}`):

```bash
curl -s -i -X POST http://127.0.0.1:27086/admin/v1/users/revoke-token/2a70be0b-c95c-4ccf-af3e-45a0f48219e5 \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

```http
HTTP/1.1 204 No Content
content-type: application/json
x-trace-id: 8602b509e669e07fca75e4c6ecb6abbe
content-length: 2
```

The old token now fails on both helpers:

```bash
curl -s -i -X POST http://127.0.0.1:27086/efti/internal/check-admin-authority -H "Authorization: Bearer $UT"
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/json
x-trace-id: 4778dcdd4ce98c492c1e3ee978f3141a
content-length: 173

{"detail":"Token is missing, invalid, expired, or the user has no active gate account.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

TIM still accepts the old token. The public probe
[`/efti/api/v1/test/lubatud`](../operations/health.md#get-eftiapiv1testlubatud) shows it:

```json
{"userinfo":{"audience":["tim-service"],"claims":{"firstName":"Doc","lastName":"Writer","personalCode":"38505047904","token_type":"custom_jwt"},"expires_at":"2026-11-03T17:05:25Z","issued_at":"2026-11-03T16:05:25Z","issuer":"TIM","jwt_id":"91f13a18-37dd-4b4b-8ce8-1cf872452b90","subject":"EE38505047904"}}
```

So the revocation lives in the gate's `users.token_revoked_at`, not in TIM. A new login after the
revocation works again:

```http
HTTP/1.1 200 OK
content-type: application/json
x-trace-id: 0ac25a613c08ccfa8c6003a72bf45f74
content-length: 91

{"id":"2a70be0b-c95c-4ccf-af3e-45a0f48219e5","name":"Doc Writer E","taraSub":"38505047904"}
```

Not run locally: the `503` cases (TIM or ReSql down).

## Notes

- `issued_at` from TIM has second precision. A token issued in the same second as, but after, a
  revocation compares as older and is refused; log in again a second later.
- The admin-only `loginExpireDate` check never fires with the TIM in this stack: its userinfo has
  `expires_at`, not `loginExpireDate`. TIM's own expiry check still applies.
- `declaration.returns` lists `rowId`, `tokenRevokedAt`, `isUserActive` and `createdAt`; the
  SQL selects only `id`, `tara_sub` and `name`, so only those three come back.
- The 401 and 503 bodies are problem objects with `content-type: application/json`.
- `POST /admin/v1/users` does not validate the personal code; `POST /auth/dev-login` does
  (11 digits and the check digit). A user created with an invalid code can never log in with the
  dev login.
