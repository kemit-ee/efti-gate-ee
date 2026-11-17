# Users

The `users` table is the list of gate administrators. There are no roles: an active row is full
admin access. These routes list, create, change, soft-delete users and revoke their tokens.

All routes are on ruuter (G1 :27086, G2 :28086) behind the [admin guard](./index.md#the-admin-guard)
(`DSL/Ruuter/admin/.guard.yml`). Get `$TOKEN` with [dev-login](./auth.md#post-authdev-login).

| Route | Purpose |
|---|---|
| [`GET /admin/v1/users`](#get-adminv1users) | List active users |
| [`GET /admin/v1/users/{id}`](#get-adminv1usersid) | One user, active or not |
| [`POST /admin/v1/users`](#post-adminv1users) | Create a user |
| [`PUT /admin/v1/users/{id}`](#put-adminv1usersid) | Replace `taraSub` and `name` |
| [`DELETE /admin/v1/users/{id}`](#delete-adminv1usersid) | Soft-delete a user |
| [`POST /admin/v1/users/revoke-token/{id}`](#post-adminv1usersrevoke-tokenid) | Invalidate every token the user holds |

## The user row

| Field | Type | Meaning |
|---|---|---|
| `id` | uuid | Logical id. Stays the same across all versions of the user. |
| `rowId` | uuid | Physical row. Every change inserts a new row (append-only), so it changes on every write. |
| `taraSub` | string | Estonian personal code without `EE`. Matched against the token's `personalCode`. |
| `name` | string | Display name. |
| `tokenRevokedAt` | timestamp or null | Tokens issued before this moment are refused. |
| `isUserActive` | boolean | `false` after a delete. Inactive users cannot log in. |
| `createdAt` | timestamp | When this version (row) was written. |

Reads take the latest row per `id` (`ORDER BY created_at DESC, revision DESC`).

## Common responses

These apply to every route on this page.

| Status | When | Body |
|---|---|---|
| 401 | no token, token rejected, or the caller has no active user row | RFC 7807 `unauthorized` (`application/problem+json`) |
| 503 | TIM or ReSql unreachable during the guard check | RFC 7807 `service-unavailable` |
| 502 | the ReSql call of the handler failed (also: `{id}` is not a UUID) | `{"error":"Data layer unavailable"}` |

---

## `GET /admin/v1/users`

`GET /admin/v1/users` · admin JWT · source `DSL/Ruuter/admin/GET/v1/users.yml` → `get_users.sql`

Lists the latest version of every **active** user, ordered by `id`. Soft-deleted users are not
listed.

| Query parameter | Required | Type | Meaning |
|---|---|---|---|
| `limit` | no | number | Page size. Default 20, clamped to 0..1000. |
| `offset` | no | number | Rows to skip. Default 0, negative becomes 0. |

| Status | When | Body |
|---|---|---|
| 200 | always (also empty) | array of user rows |
| 502 | ReSql error, for example a non-numeric `limit` | `{"error":"Data layer unavailable"}` |

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/users" -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 447

[{"createdAt":"2026-11-03T12:03:33.313091Z","id":"11111111-1111-1111-1111-111111111111","isUserActive":true,"name":"Super Admin","rowId":"0fccadcc-7971-4c89-8c5c-4582e041b695","taraSub":"60001019906","tokenRevokedAt":null},{"createdAt":"2026-11-03T15:59:59.511134Z","id":"e29a066a-c505-4b2e-8381-42ce65111292","isUserActive":true,"name":"Test Writer D","rowId":"5ad2ca9c-4564-498b-bf13-d99a18e5cc25","taraSub":"39001010011","tokenRevokedAt":null}]
```

Second page of size 1:

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/users?limit=1&offset=1" -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 225

[{"createdAt":"2026-11-03T15:59:59.511134Z","id":"e29a066a-c505-4b2e-8381-42ce65111292","isUserActive":true,"name":"Test Writer D","rowId":"5ad2ca9c-4564-498b-bf13-d99a18e5cc25","taraSub":"39001010011","tokenRevokedAt":null}]
```

Audit: `user.list` with `{"count":…,"limit":…,"offset":…}`.

---

## `GET /admin/v1/users/{id}`

`GET /admin/v1/users/{id}` · admin JWT · source `DSL/Ruuter/admin/GET/v1/users.yml` → `get_user_by_id.sql`

Same file as the list; a path segment switches it to single mode. Returns the latest version even
when the user is soft-deleted (`isUserActive: false`).

| Path parameter | Required | Type | Meaning |
|---|---|---|---|
| `id` | yes | uuid | Logical user id |

| Status | When | Body |
|---|---|---|
| 200 | user exists | user row |
| 404 | no user with this id | `{"error":"Not Found"}` |
| 502 | `id` is not a UUID (ReSql cast error) | `{"error":"Data layer unavailable"}` |

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/users/e29a066a-c505-4b2e-8381-42ce65111292" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 223

{"createdAt":"2026-11-03T15:59:59.511134Z","id":"e29a066a-c505-4b2e-8381-42ce65111292","isUserActive":true,"name":"Test Writer D","rowId":"5ad2ca9c-4564-498b-bf13-d99a18e5cc25","taraSub":"39001010011","tokenRevokedAt":null}
```

Unknown id:

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/users/00000000-0000-4000-8000-000000000000" \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
content-length: 21

{"error":"Not Found"}
```

Not a UUID:

```bash
curl -s -i "http://127.0.0.1:27086/admin/v1/users/not-a-uuid" -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
content-length: 34

{"error":"Data layer unavailable"}
```

Audit: `user.read` with `resourceId` = the id (only on 200).

---

## `POST /admin/v1/users`

`POST /admin/v1/users` · admin JWT · source `DSL/Ruuter/admin/POST/v1/users.yml` → `check_tara_sub_exists.sql`, `insert_user.sql`

Creates a user with a new random `id`. This is the only way a person gets admin access. The very
first administrator of a deployment is inserted by hand (`docs/specs/deploy/first-admin.md`).

| Header | Required | Value |
|---|---|---|
| `Authorization` | yes | `Bearer <token>` |
| `Content-Type` | yes | `application/json` |

| Body field | Required | Type | Meaning / validation |
|---|---|---|---|
| `taraSub` | yes | string | Personal code without `EE`. Not format-checked here. Must not belong to another **active** user. |
| `name` | yes | string | Display name. |

| Status | When | Body |
|---|---|---|
| 201 | created | the new user row |
| 400 | body missing, `taraSub` or `name` missing | RFC 7807-shaped `BAD_REQUEST_GENERAL`, `detail: "Required fields: taraSub, name."` |
| 409 | an active user already has this `taraSub` | `{"error":"Conflict","detail":"tara_sub already exists on an active user"}` |
| 500 | the duplicate check or the insert failed | `{"error":"Failed to create user"}` |

### Create

```bash
curl -s -i -X POST http://127.0.0.1:27086/admin/v1/users \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"taraSub":"39001010011","name":"Test Writer D"}'
```

```http
HTTP/1.1 201 Created
content-type: application/json
content-length: 223

{"createdAt":"2026-11-03T15:59:59.511134Z","id":"e29a066a-c505-4b2e-8381-42ce65111292","isUserActive":true,"name":"Test Writer D","rowId":"5ad2ca9c-4564-498b-bf13-d99a18e5cc25","taraSub":"39001010011","tokenRevokedAt":null}
```

### Duplicate `taraSub`

```bash
curl -s -i -X POST http://127.0.0.1:27086/admin/v1/users \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"taraSub":"39001010011","name":"Duplicate"}'
```

```http
HTTP/1.1 409 Conflict
content-type: application/json
content-length: 73

{"detail":"tara_sub already exists on an active user","error":"Conflict"}
```

### Missing field

```bash
curl -s -i -X POST http://127.0.0.1:27086/admin/v1/users \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"taraSub":"49001010023"}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 156

{"code":"BAD_REQUEST_GENERAL","detail":"Required fields: taraSub, name.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

### No token

```bash
curl -s -i -X POST http://127.0.0.1:27086/admin/v1/users \
  -H 'Content-Type: application/json' -d '{"taraSub":"49001010023","name":"x"}'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 173

{"detail":"Token is missing, invalid, expired, or the user has no active gate account.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

### A soft-deleted personal code can be used again

The duplicate check looks at active users only. Creating a user with the `taraSub` of a deleted
user gives a **new** `id`:

```http
HTTP/1.1 201 Created
content-type: application/json
content-length: 231

{"createdAt":"2026-11-03T16:05:26.674468Z","id":"9d44fd3c-7c75-425f-bb73-4c3f017329ab","isUserActive":true,"name":"Test Writer D 2 again","rowId":"b4698cf6-9e5d-4251-ae51-fb4a3ea99084","taraSub":"48505050040","tokenRevokedAt":null}
```

Audit: `user.create` with `details.before: null` and `details.after` = the new row.

---

## `PUT /admin/v1/users/{id}`

`PUT /admin/v1/users/{id}` · admin JWT · source `DSL/Ruuter/admin/PUT/v1/users.yml` → `get_user_by_id.sql`, `update_user.sql`

Replaces `taraSub` and `name`. Inserts a new row with the same `id`. `isUserActive` and
`tokenRevokedAt` are carried over, with one exception: when `taraSub` changes, `tokenRevokedAt` is
set to now, so every token issued for the old personal code stops working.

| Path parameter | Required | Type | Meaning |
|---|---|---|---|
| `id` | yes | uuid | Logical user id |

| Body field | Required | Type | Meaning / validation |
|---|---|---|---|
| `taraSub` | yes | string | New (or same) personal code. No duplicate check. |
| `name` | yes | string | New (or same) name. |

| Status | When | Body |
|---|---|---|
| 200 | updated | the new user row |
| 400 | `taraSub` or `name` missing | RFC 7807-shaped `BAD_REQUEST_GENERAL` |
| 404 | no user with this id | `{"error":"Not Found"}` |
| 500 | the insert returned nothing | `{"error":"Failed to update user"}` |
| 502 | `id` not a UUID, missing `id` (`PUT /admin/v1/users`), or ReSql error | `{"error":"Data layer unavailable"}` |

### Rename

```bash
curl -s -i -X PUT http://127.0.0.1:27086/admin/v1/users/e29a066a-c505-4b2e-8381-42ce65111292 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"taraSub":"39001010011","name":"Test Writer D (renamed)"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 233

{"createdAt":"2026-11-03T16:00:13.804578Z","id":"e29a066a-c505-4b2e-8381-42ce65111292","isUserActive":true,"name":"Test Writer D (renamed)","rowId":"b05e37cd-12ba-464c-829c-5eb000d8bc77","taraSub":"39001010011","tokenRevokedAt":null}
```

The user's tokens keep working after a rename.

### Change the personal code

```bash
curl -s -i -X PUT http://127.0.0.1:27086/admin/v1/users/e9ac2f94-b2c9-4fd6-a816-c820c08df517 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"taraSub":"48505050040","name":"Test Writer D 2"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 250

{"createdAt":"2026-11-03T16:00:23.357728Z","id":"e9ac2f94-b2c9-4fd6-a816-c820c08df517","isUserActive":true,"name":"Test Writer D 2","rowId":"1029d572-0a95-4704-8c76-1f25eb37ed62","taraSub":"48505050040","tokenRevokedAt":"2026-11-03T16:00:23.358414Z"}
```

The token this user got for the old code `49001010023` now answers 401 on `GET /auth/user`.

### Unknown id

```bash
curl -s -i -X PUT http://127.0.0.1:27086/admin/v1/users/00000000-0000-4000-8000-000000000000 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"taraSub":"49001010023","name":"x"}'
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
content-length: 21

{"error":"Not Found"}
```

### Missing field

```bash
curl -s -i -X PUT http://127.0.0.1:27086/admin/v1/users/e29a066a-c505-4b2e-8381-42ce65111292 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":"x"}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 156

{"code":"BAD_REQUEST_GENERAL","detail":"Required fields: taraSub, name.","status":400,"title":"Bad Request","type":"https://api.efti.ee/errors/bad-request"}
```

### No id in the path

```bash
curl -s -i -X PUT http://127.0.0.1:27086/admin/v1/users \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"taraSub":"49001010023","name":"x"}'
```

```http
HTTP/1.1 502 Bad Gateway
content-type: application/json
content-length: 34

{"error":"Data layer unavailable"}
```

Audit: `user.update` with `details.before` (old row) and `details.after` (new row).

### Notes

- PUT has **no duplicate check**. Setting a `taraSub` that an active user already has answers 200
  (tested: user `e9ac2f94…` was set to `39001010011`, which user `e29a066a…` holds, and back).
  While two active users share a code, a fresh login for that code resolves to two rows and is
  refused with 401. POST checks this; PUT should too.
- PUT on a soft-deleted user answers 200 and keeps `isUserActive: false`. It cannot bring a user
  back. Create a new user instead.

---

## `DELETE /admin/v1/users/{id}`

`DELETE /admin/v1/users/{id}` · admin JWT · source `DSL/Ruuter/admin/DELETE/v1/users.yml` → `soft_delete_user.sql`

Soft-deletes: inserts a new row with `is_active = false`. The user's tokens stop working at once,
because the guard only accepts active users. History stays in the table.

| Path parameter | Required | Type | Meaning |
|---|---|---|---|
| `id` | yes | uuid | Logical user id. Must not be the caller's own id. |

| Status | When | Body |
|---|---|---|
| 204 | deleted (also when already deleted) | none, but see the content-length note |
| 400 | `id` is the caller's own id | a JSON **string**: `"{\"error\": \"BadRequest\", \"detail\": \"Admin cannot delete their own account\"}"` |
| 500 | no user with this id, `id` not a UUID, or the delete could not be verified | `{"error":"User deletion could not be verified"}` |

### Delete

```bash
curl -s -i -X DELETE http://127.0.0.1:27086/admin/v1/users/e9ac2f94-b2c9-4fd6-a816-c820c08df517 \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 204 No Content
content-type: application/json
content-length: 2

```

Afterwards `GET /admin/v1/users/e9ac2f94-…` still finds the user:

```json
{"createdAt":"2026-11-03T16:00:47.985644Z","id":"e9ac2f94-b2c9-4fd6-a816-c820c08df517","isUserActive":false,"name":"Test Writer D 2","rowId":"84e1ebee-07ea-488d-b891-19525a3f11f1","taraSub":"48505050040","tokenRevokedAt":"2026-11-03T16:00:29.680018Z"}
```

The list no longer shows it, and a token this user got before the delete answers 401.

### Delete yourself

```bash
curl -s -i -X DELETE http://127.0.0.1:27086/admin/v1/users/11111111-1111-1111-1111-111111111111 \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 84

"{\"error\": \"BadRequest\", \"detail\": \"Admin cannot delete their own account\"}"
```

### Unknown id

```bash
curl -s -i -X DELETE http://127.0.0.1:27086/admin/v1/users/00000000-0000-4000-8000-000000000000 \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 500 Internal Server Error
content-type: application/json
content-length: 47

{"error":"User deletion could not be verified"}
```

Audit: `user.delete` with `{"user_id":"…","before":null,"after":null}`.

### Notes

- **204 with `content-length: 2`.** The route returns `""` with status 204, and the 2 bytes of the
  JSON `""` end up in the header. curl shows the 204 and moves on. Clients that trust
  Content-Length (Postman, Newman) can hang. See [Known issues](../../reference/known-issues.md).
- **Unknown id gives 500, not 404.** `check_delete` tests `body[0]?.isUserActive != false` before
  the empty-body test, so an empty result never reaches `not_found`. The 404 branch is dead code.
- **The 400 body is double-encoded.** The DSL `return` is a quoted string, so the response is a
  JSON string that contains JSON, not an object. `permissions-matrix.md` §3.3 also expects the code
  `BAD_REQUEST_GENERAL`, which this body does not carry.
- A second DELETE of the same user answers 204 again and writes another tombstone row.

---

## `POST /admin/v1/users/revoke-token/{id}`

`POST /admin/v1/users/revoke-token/{id}` · admin JWT · source `DSL/Ruuter/admin/POST/v1/users/revoke-token.yml` → `revoke_user_token.sql`

Sets `tokenRevokedAt` to now (a new row). Every token issued before that moment is refused on its
next request. The user can log in again and gets a working token. Use it when a person's session
may be compromised or they leave.

| Path parameter | Required | Type | Meaning |
|---|---|---|---|
| `id` | yes | uuid | Logical user id |

No body.

| Status | When | Body |
|---|---|---|
| 204 | revoked and verified by a re-read | none, but `content-length: 2` |
| 404 | no user with this id | `{"error":"Not Found"}` |
| 500 | the insert returned nothing | `{"error":"Failed to revoke user token"}` |
| 500 | the re-read did not show a `tokenRevokedAt` | `{"error":"Token revocation could not be verified"}` |
| 502 | `id` not a UUID, or ReSql error | `{"error":"Data layer unavailable"}` |

### Revoke

```bash
curl -s -i -X POST http://127.0.0.1:27086/admin/v1/users/revoke-token/e29a066a-c505-4b2e-8381-42ce65111292 \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 204 No Content
content-type: application/json
content-length: 2

```

The row now has `tokenRevokedAt`:

```bash
curl -s http://127.0.0.1:27086/admin/v1/users/e29a066a-c505-4b2e-8381-42ce65111292 \
  -H "Authorization: Bearer $TOKEN"
```

```json
{"createdAt":"2026-11-03T16:00:37.460796Z","id":"e29a066a-c505-4b2e-8381-42ce65111292","isUserActive":true,"name":"Test Writer D (renamed)","rowId":"bd42d2b2-59fc-4842-bdec-33aab6419dde","taraSub":"39001010011","tokenRevokedAt":"2026-11-03T16:00:37.460796Z"}
```

### The user's old token is refused

```bash
curl -s -i http://127.0.0.1:27086/auth/user -H "Authorization: Bearer $USER_TOKEN"
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 173

{"detail":"Token is missing, invalid, expired, or the user has no active gate account.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

### A new login works

The user logs in again (here with dev-login for `39001010011`, one second later):

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 102

{"id":"e29a066a-c505-4b2e-8381-42ce65111292","name":"Test Writer D (renamed)","taraSub":"39001010011"}
```

### Unknown id

```bash
curl -s -i -X POST http://127.0.0.1:27086/admin/v1/users/revoke-token/00000000-0000-4000-8000-000000000000 \
  -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 404 Not Found
content-type: application/json
content-length: 21

{"error":"Not Found"}
```

Audit: `user.revoke_token` with `{"user_id":"…"}`.

### Notes

- The check is `token issued_at > tokenRevokedAt`. A token minted in the same instant as the
  revocation may be refused; log in again a moment later.
- The declaration says the revocation goes "via TIM". It does not: it only writes the `users` row.
  The gate enforces it on every request through `check_user_auth.sql`; TIM keeps accepting the
  token, so it is refused by the gate, not by TIM.
- The same 204 + `content-length: 2` defect as DELETE. See [Known issues](../../reference/known-issues.md).
- Nothing stops an admin from revoking their own tokens. The caller's next request then answers 401.
  Not run locally: the dev admin must not be revoked.
- Not run locally: the 500 branches (they need a ReSql failure between two calls).
