# Login and session

Four routes under `/auth` turn a person into a gate session and end it again. The admin UI calls
them. Everything else in the admin API expects the token they hand out.

| Route | Purpose | Auth |
|---|---|---|
| [`POST /auth/callback`](#post-authcallback) | Exchange a TARA authorization code for a gate JWT | none |
| [`POST /auth/dev-login`](#post-authdev-login) | Mint a gate JWT without TARA (local and CI only) | none, `DEV_LOGIN_ENABLED=true` |
| [`POST /auth/logout`](#post-authlogout) | Blacklist the presented JWT at TIM | none (the token itself) |
| [`GET /auth/user`](#get-authuser) | Return the caller's own user row | gate JWT |

All four are served by ruuter (G1 :27086, G2 :28086). In a deployment the UI nginx proxies
`/auth/` to ruuter (`docker/ui/nginx.conf`). The `POST` routes share the public guard
`DSL/Ruuter/auth/POST/.guard.yml`; `GET /auth/user` sits behind `DSL/Ruuter/auth/GET/.guard.yml`,
which calls `efti/internal/check-user-authority`.

The dev credentials on this page (the personal code `60001019906`) are public in the repository.

## The token

TIM mints the token. It is an RS256 JWT. The payload of a dev-login token, decoded:

```json
{"iss":"TIM","sub":"EE60001019906","aud":["tim-service"],"exp":1793725159,"iat":1793721559,
 "jti":"8de0875b-ded6-46e8-af95-65ddf244b130","firstName":"Super","lastName":"Admin",
 "personalCode":"60001019906","token_type":"custom_jwt"}
```

- `personalCode` is what the gate matches against `users.tara_sub`. `sub` keeps TARA's `EE` prefix.
- Lifetime: 60 minutes from dev-login, 480 minutes from callback (`expirationInMinutes` in the DSL).
- The client sends it as `Authorization: Bearer <token>`. The gate never reads a cookie. Only on
  the internal hop to TIM is the value re-framed as `cookie: eftiJwtCookie=<token>`.
- A token says who the person is. It does not grant anything. Every request is checked against
  TIM (`GET /jwt/userinfo`) and then against the latest `users` row. See
  [Administrators](./index.md#identity-model).

---

## `POST /auth/callback`

The browser comes back from TARA with `code` and `state`. The admin UI posts both here. The gate
hands them to TIM, which checks `state`, exchanges the code at TARA, and verifies the ID token. The
gate then asks TIM to mint the gate JWT and returns it.

How the UI gets there (`code/ui/src/App.svelte`, `AuthCallbackPage.svelte`):

1. No token in `sessionStorage`, or `GET /auth/user` fails → the UI calls
   `GET /tim/auth/login/tara?redirect_uri=<origin>/callback` (nginx proxies `/tim/` to TIM).
2. TIM answers with an `authorization_url`. The browser goes to TARA (locally TARA-Mock behind the
   nginx path `/tara/`).
3. TARA redirects to `<origin>/callback?code=…&state=…`.
4. The callback page posts `{code, state}` to `/auth/callback`, stores the returned token in
   `sessionStorage` and opens `/gates`.

`POST /auth/callback` · on ruuter (G1 :27086) · no credential · source `DSL/Ruuter/auth/POST/callback.yml`

### Request

| Header | Required | Value |
|---|---|---|
| `Content-Type` | yes | `application/json` |
| `X-Forwarded-For` | no | client address, copied into the audit row (set by the UI nginx) |

| Body field | Required | Type | Meaning / validation |
|---|---|---|---|
| `code` | yes | string | Authorization code from TARA (TARA-Mock locally). |
| `state` | yes | string | OAuth2 `state`. TIM accepts each value once. |

### Responses

| Status | When | Body |
|---|---|---|
| 200 | TIM accepted the code and minted a token | `{"token":"eyJ…"}` |
| 400 | body missing, or `code` or `state` missing | `{"error":"code and state are required"}` |
| 401 | TIM `GET /auth/callback/tara` answered non-2xx (bad code, reused `state`, TARA error) | `{"error":"Authentication failed"}` |
| 500 | TIM `POST /jwt/custom/generate` answered non-2xx | `{"error":"JWT generation failed"}` |

### Flow inside the gate

1. `GET [TIM]/auth/callback/tara?code=…&state=…`. TIM answers with `session_id` and `user_profile`.
2. The `EE` prefix is stripped from `user_profile.user_id` (`EE60001019906` → `60001019906`).
3. `POST [TIM]/jwt/custom/generate` with `sub` (raw TARA id), `personalCode`, 480 minutes,
   `JWTName: eftiJwtCookie`, authenticated with `X-TIM-Admin-Token`.
4. An audit row `user.login` is written: `outcome: success` with `personal_code`, or
   `outcome: failure` with `failure_reason` `tara_callback_failed` / `jwt_generation_failed`.

The callback does not look up the `users` table. A person with a valid TARA login but no active
user row gets a token, and every admin request with that token answers 401.

### Examples

#### Missing `state`

```bash
curl -s -i -X POST http://127.0.0.1:27086/auth/callback \
  -H 'Content-Type: application/json' -d '{"code":"abc"}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 39

{"error":"code and state are required"}
```

#### A code TIM does not know

```bash
curl -s -i -X POST http://127.0.0.1:27086/auth/callback \
  -H 'Content-Type: application/json' -d '{"code":"bogus-code","state":"bogus-state"}'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/json
content-length: 33

{"error":"Authentication failed"}
```

The audit log then holds:

```json
{"action":"user.login","resource":"user","userId":null,
 "details":{"failure_reason":"tara_callback_failed","outcome":"failure"}}
```

#### Happy path

Not run locally: the local stack has no TARA (or TARA mock), so there is no valid `code`/`state`
pair to post. Use `/auth/dev-login` there. The success
response has the same shape as dev-login: `200` with `{"token":"eyJ…"}`.

### Notes

- `docs/architecture/identity-and-access/authentication.md` §2 says the code exchange happens in TIM
  and that "Ruuter never sees" the login, with the browser starting at
  `/oauth2/authorization/tara` and TARA returning to TIM's `/authenticate`. The code does it
  differently: the UI starts at TIM's `/auth/login/tara`, TARA returns to the UI's `/callback`, and
  the UI posts the code to Ruuter's `/auth/callback`, which forwards it to TIM. TIM still does the
  back-channel exchange and holds the TARA client secret. The DSL and UI code win.
- The same document gives TIM's own session timeout (default 30 minutes) as the token TTL. The
  callback asks TIM for 480 minutes explicitly.

---

## `POST /auth/dev-login`

Mints a gate JWT for any valid Estonian personal code, with no TARA round trip. Local development
and CI only.

`POST /auth/dev-login` · on ruuter (G1 :27086) · no credential · only when `DEV_LOGIN_ENABLED=true` · source `DSL/Ruuter/auth/POST/dev-login.yml`

### Request

| Header | Required | Value |
|---|---|---|
| `Content-Type` | yes | `application/json` |
| `X-Forwarded-For` | no | copied into the audit row |

| Body field | Required | Type | Meaning / validation |
|---|---|---|---|
| `personalCode` | yes | string | Estonian personal code: exactly 11 digits, first digit 1-6, valid mod-11 check digit. No `EE` prefix, no spaces. |
| `firstName` | no | string | Copied into the token claims. |
| `lastName` | no | string | Copied into the token claims. |

The check digit: weights `1 2 3 4 5 6 7 8 9 1` over the first ten digits, sum mod 11. If that is 10,
weights `3 4 5 6 7 8 9 1 2 3`, mod 11 again; 10 becomes 0.

### Responses

| Status | When | Body |
|---|---|---|
| 200 | token minted | `{"token":"eyJ…"}` |
| 400 | body missing or no `personalCode` | `{"error":"personalCode is required"}` |
| 400 | `personalCode` fails the format or checksum | `{"error":"personalCode is not a valid Estonian personal code"}` |
| 404 | `DEV_LOGIN_ENABLED` is not `true` | `{"error":"Not found"}` |
| 500 | TIM did not mint the token | `{"error":"JWT generation failed"}` |

The token carries `sub: "EE<personalCode>"`, `personalCode`, `firstName`, `lastName`, and lives 60
minutes.

### Examples

#### Log in as the dev admin

```bash
curl -s -i -X POST http://127.0.0.1:27086/auth/dev-login \
  -H 'Content-Type: application/json' \
  -d '{"personalCode":"60001019906","firstName":"Super","lastName":"Admin"}'
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 723

{"token":"eyJ…"}
```

Keep it in a shell variable for the other pages:

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:27086/auth/dev-login \
  -H 'Content-Type: application/json' \
  -d '{"personalCode":"60001019906","firstName":"Super","lastName":"Admin"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
```

#### No personal code

```bash
curl -s -i -X POST http://127.0.0.1:27086/auth/dev-login \
  -H 'Content-Type: application/json' -d '{"firstName":"A"}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 36

{"error":"personalCode is required"}
```

#### Wrong check digit

```bash
curl -s -i -X POST http://127.0.0.1:27086/auth/dev-login \
  -H 'Content-Type: application/json' \
  -d '{"personalCode":"39001010012","firstName":"A","lastName":"B"}'
```

```http
HTTP/1.1 400 Bad Request
content-type: application/json
content-length: 62

{"error":"personalCode is not a valid Estonian personal code"}
```

`"EE39001010011"` (with the TARA prefix) gives the same 400.

#### A valid code with no user row

dev-login does not look at the `users` table, so the token is minted. It is useless until an
administrator creates the user:

```bash
UT=$(curl -s -X POST http://127.0.0.1:27086/auth/dev-login \
  -H 'Content-Type: application/json' \
  -d '{"personalCode":"39001010011","firstName":"Test","lastName":"Unknown"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
curl -s -i http://127.0.0.1:27086/auth/user -H "Authorization: Bearer $UT"
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 173

{"detail":"Token is missing, invalid, expired, or the user has no active gate account.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

After `POST /admin/v1/users` with `taraSub` `39001010011`, the same token works without a new
login:

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 92

{"id":"e29a066a-c505-4b2e-8381-42ce65111292","name":"Test Writer D","taraSub":"39001010011"}
```

### Notes

- Audit: a success writes `user.login` with `{"dev_login":true,"outcome":"success","personal_code":"…"}`.
  A checksum failure writes `{"dev_login":true,"failure_reason":"invalid_personal_code","outcome":"failure"}`.
  A missing `personalCode` and the disabled case write nothing. `userId` is always `null` on these
  rows; no user row is looked up.
- Both local gates set `DEV_LOGIN_ENABLED=true`: every local gate's Ruuter reads the repository's
  `constants.ini` (`local/compose.yaml`).
  Production must set `false` (`docs/specs/deploy/resource-and-secrets-requirements.md`).
- dev-login mints a token for any valid code. That is not a way in by itself: without an active
  user row the token is refused everywhere.

---

## `POST /auth/logout`

Blacklists the presented token at TIM. From then on TIM's `/jwt/userinfo` rejects it, so the next
request with it fails. The UI (`Navbar.svelte`) calls it and then drops its `sessionStorage` copy.

`POST /auth/logout` · on ruuter (G1 :27086) · no guard; the token to end is the `Authorization` header · source `DSL/Ruuter/auth/POST/logout.yml`

### Request

| Header | Required | Value |
|---|---|---|
| `Authorization` | no | `Bearer <token>` – the token to blacklist |

No body.

### Responses

| Status | When | Body |
|---|---|---|
| 200 | always: blacklisted, already dead, garbage, or no header | `{"message":"logged out"}` |

Logout is idempotent and never fails towards the caller. Without a `Bearer` header nothing is sent
to TIM. When TIM answers non-2xx (already blacklisted, not a JWT), an audit row
`user.logout` with `failure_reason: tim_blacklist_failed` and `tim_status` is written. A
successful logout writes no audit row.

### Examples

#### End a session

```bash
curl -s -i -X POST http://127.0.0.1:27086/auth/logout -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 24

{"message":"logged out"}
```

#### The token stops working

```bash
curl -s -i http://127.0.0.1:27086/auth/user -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 173

{"detail":"Token is missing, invalid, expired, or the user has no active gate account.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

#### Logout again, or with garbage, or without a header

All three answer the same `200 {"message":"logged out"}`. The audit log shows the two that reached
TIM:

```json
{"action":"user.logout","details":{"failure_reason":"tim_blacklist_failed","outcome":"failure","tim_status":409}}
{"action":"user.logout","details":{"failure_reason":"tim_blacklist_failed","outcome":"failure","tim_status":400}}
```

409 is the repeat logout of the same token, 400 the `Bearer garbage` call.

### Notes

- Logout ends one token. Other tokens of the same person stay valid. To end all of them, use
  [revoke-token](./users.md#post-adminv1usersrevoke-tokenid).
- `authentication.md` §7 and `permissions-matrix.md` §6 say logout also appends a `sessions` row
  and answers 204. The DSL does neither: it answers 200 with a body and writes no `sessions` row.

---

## `GET /auth/user`

Returns the caller's own identity as the gate resolved it. The admin UI uses it to show who is
logged in.

`GET /auth/user` · on ruuter (G1 :27086) · gate JWT of an active user · source `DSL/Ruuter/auth/GET/user.yml`

### Request

| Header | Required | Value |
|---|---|---|
| `Authorization` | yes | `Bearer <token>` |

### Responses

| Status | When | Body |
|---|---|---|
| 200 | token valid and the person has an active user row | `{"id","name","taraSub"}` |
| 401 | no or non-Bearer header, token rejected by TIM (expired, blacklisted, not a JWT), no active user row, or token issued before `tokenRevokedAt` | RFC 7807 `unauthorized` |
| 503 | TIM or ReSql unreachable | RFC 7807 `service-unavailable` |

### Examples

#### Who am I

```bash
curl -s -i http://127.0.0.1:27086/auth/user -H "Authorization: Bearer $TOKEN"
```

```http
HTTP/1.1 200 OK
content-type: application/json
content-length: 90

{"id":"11111111-1111-1111-1111-111111111111","name":"Super Admin","taraSub":"60001019906"}
```

#### No token

```bash
curl -s -i http://127.0.0.1:27086/auth/user
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 173

{"detail":"Token is missing, invalid, expired, or the user has no active gate account.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

`Authorization: Bearer garbage` answers the same 401.

### Notes

- The declaration lists `rowId`, `tokenRevokedAt`, `isUserActive` and `createdAt` as returned
  fields. The route returns only `id`, `name` and `taraSub`, because `check_user_auth.sql` selects
  only these three. Use [`GET /admin/v1/users/{id}`](./users.md) for the full row.
- Every 401 writes an `auth.auth_denied` audit row (`{"status":401}`, `userId` null).
- The 503 body is: `{"type":"https://api.efti.ee/errors/service-unavailable","title":"Service Unavailable","status":503,"detail":"…Retry the request."}`.
  Not run locally: it needs TIM or ReSql to be down, and this page must not stop containers.
