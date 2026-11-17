# Frontend error reports

The admin UI posts uncaught JavaScript errors here so they end up in the gate's log. The gate
stores nothing and returns nothing.

`POST /admin/v1/js-error` · on ruuter (G1 :27086) · admin JWT · source `DSL/Ruuter/admin/POST/v1/js-error.yml`

## Request

| Header | Required | Value |
|---|---|---|
| `Authorization` | yes | `Bearer <token>` (see [dev-login](./auth.md#post-authdev-login)) |
| `Content-Type` | no | `application/json` for an object; other types are wrapped (see below) |

The body is free-form. There is no field contract. The admin UI (`code/ui/src/api/errorHandlers.ts`)
hooks `window.onerror` and sends `message`, `source`, `line`, `column`, `href`, `userAgent` and
`stack`. The example below sends a subset of these.

## Responses

| Status | When | Body |
|---|---|---|
| 204 | always once the guard passed: object, array, text, or no body | none, but `content-length: 2` |
| 400 | the body is `null` | RFC 7807-shaped `BAD_REQUEST_GENERAL`, `detail: "A JSON request body is required."` |
| 401 | no or bad token, no active user row | RFC 7807 `unauthorized` |
| 503 | TIM or ReSql unreachable during the guard check | RFC 7807 `service-unavailable` |

## Examples

### Report an error

```bash
curl -s -i -X POST http://127.0.0.1:27086/admin/v1/js-error \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"TypeError: x is undefined","source":"UsersPage.tsx","line":42,"url":"/admin/users"}'
```

```http
HTTP/1.1 204 No Content
content-type: application/json
content-length: 2

```

The ruuter log (`docker logs efti-g1-ruuter`) shows:

```text
16:04:13.585 INFO  [t=7743aac6 admin] ▸ log_error (log) 1.0ms → respond  msg="JS error: {"line":42,"message":"TypeError: x is undefined","source":"UsersPage.tsx","url":"/admin/users"}"
```

### Other bodies

No body, `Content-Type: text/plain` with `boom`, and the JSON array `[1,2]` all answer 204. The log
lines show how Ruuter wrapped them:

```text
… msg="JS error: {}"
… msg="JS error: {"plain":"boom"}"
… msg="JS error: {"value":[1,2]}"
```

### No token

```bash
curl -s -i -X POST http://127.0.0.1:27086/admin/v1/js-error \
  -H 'Content-Type: application/json' -d '{"message":"x"}'
```

```http
HTTP/1.1 401 Unauthorized
content-type: application/problem+json
content-length: 173

{"detail":"Token is missing, invalid, expired, or the user has no active gate account.","status":401,"title":"Unauthorized","type":"https://api.efti.ee/errors/unauthorized"}
```

## Notes

- **204 with `content-length: 2`.** Same defect as the other `return: ""` routes. curl is fine;
  Postman and Newman can hang. See [Known issues](../../reference/known-issues.md).
- The 400 branch was not reached locally: Ruuter turns a missing body into `{}`, so
  `incoming.body == null` never holds for a plain HTTP request.
- The body goes into the log verbatim, including `href` and the stack. The UI must not put tokens
  or personal data into a report.
- Only logged-in admins can report: a report sent before login (or after the token expired) gets
  401, and the UI ignores the failure (`.catch(e => console.error(e))`).
- No audit row is written.
