# AGENTS.md — eFTI Gate (EE)

## What this is

Estonian national eFTI Gate (EU Regulation 2020/1056). Mediates dataset retrieval between certified eFTI Platforms and competent authorities; bridges to peer national gates over eDelivery AS4.

## Architecture at a glance

13 Docker Compose services. Three runtime layers:

| Layer | Tech | Port | Role |
|-------|------|------|------|
| **Ruuter** | Rust DSL engine | 8086 | HTTP API gateway — routes defined as YAML files |
| **ReSql** | Rust SQL executor | 8090 | Serves SQL files as HTTP endpoints |
| **Kotlin services** | JVM (klite framework) | 8081–8083 | edelivery (AS4), xml-mapper (XML↔JSON), multiplexer (fan-out) |

Supporting: PostgreSQL 18 (54321), TIM (8085, identity), TARA-mock (8888, OIDC), UI (8000, Vite/Svelte).

## Commands

```sh
# Start everything (builds images, runs migrations, starts all services + E2E tests)
docker compose up --build -d

# Watch DSL changes (auto-restart ruuter + resql on file save)
docker compose up --watch ruuter resql

# Run E2E tests manually (IntelliJ HTTP Client CLI)
docker compose run --rm http-tests

# Run Kotlin unit tests (from code/ directory)
cd code && ./gradlew test

# Run a single Kotlin subproject's tests
cd code && ./gradlew edelivery:test
cd code && ./gradlew xml-mapper:test
cd code && ./gradlew multiplexer:test
```

## Directory layout

```
DSL/
  Ruuter/
    efti/               # Internal API routes (served under /efti/)
      POST/api/v1/      # G2G endpoints: dataset, follow-up, consignments
      POST/internal/     # Auth helpers: check-admin-authority, check-user-authority
      GET/api/v1/        # gates/own, consignments, status, follow-up, test
    admin/              # Admin CRUD routes (served under /admin/)
      GET/v1/           # List/get: gates, platforms, authorities, users, audit
      POST/v1/          # Create: gates, platforms, authorities, users + ping, revoke-token, js-error
      PUT/v1/           # Update: gates, platforms, authorities, users
      DELETE/v1/        # Delete: gates, platforms, authorities, users, consignments
    auth/               # Authentication routes (served under /auth/)
      GET/              # user (current user profile)
      POST/             # callback, logout, dev-login
    mock-platform/      # Mock platform (served under /mock-platform/)
  Resql/efti/POST/      # SQL endpoint files (*.sql)
  Liquibase/            # DB migrations (initial/ + changelog/)
code/
  edelivery/            # AS4 messaging service
  xml-mapper/           # XML↔JSON conversion (FTI004/009/010/019/021/025/029/030)
  multiplexer/          # Fan-out search to all registered gates
  core/                 # Shared: ResqlClient, Party types, XSD schemas
tests/                  # IntelliJ HTTP Client test files (*.http) with assertions
```

## Nginx proxy (UI)

The UI container (`docker/ui/nginx.conf`) proxies browser requests to backend services:

| Location | Backend | Purpose |
|----------|---------|---------|
| `/admin/` | `http://ruuter:8086` | Admin CRUD (gates, platforms, authorities, users) |
| `/auth/` | `http://ruuter:8086` | Authentication (user, callback, logout, dev-login) |
| `/tim/` | `http://tim:8085/` | Token & Identity Manager |
| `/tara/` | `https://tara-mock:8080/` | TARA OIDC mock |
| `/` | static files | SPA fallback to `index.html` |

The UI API client (`code/ui/src/api/api.ts`) uses `/admin/v1/` as the default prefix. Paths starting with `/` (like `/auth/callback`) are used as-is, routing to the `auth` project.

## DSL conventions (Ruuter)

- Routes map 1:1 to file paths: `POST /admin/v1/gates` → `DSL/Ruuter/admin/POST/v1/gates.yml`
- Auth routes: `POST /auth/callback` → `DSL/Ruuter/auth/POST/callback.yml`
- Internal routes: `POST /efti/internal/check-admin-authority` → `DSL/Ruuter/efti/POST/internal/check-admin-authority.yml`
- Constants from `constants.ini` referenced as `[#VARIABLE]` (e.g., `[#OWN_GATE_ID]`, `[#EDELIVERY_URL]`)
- Request data: `incoming.body`, `incoming.headers`, `incoming.params.pathParams`
- `allowed_body: [xml]` — wraps raw XML body as `incoming.body.xml`
- `wrapper: false` — always return raw response (not JSON-wrapped)
- `template: api/v1/foo` — call another DSL file as subroutine, works only in the same top-level Ruuter project
- `.guard` files — placed in directory hierarchy; Ruuter runs the nearest guard before the route
- Guard hierarchy: `admin/` POST/PUT/DELETE = admin-only; `admin/` GET = any authenticated user; `auth/` POST = public; `auth/` GET = any authenticated user
- In this project we name guard files as `.guard.yml`, with extension

## SQL conventions (ReSql)

- Files in `DSL/Resql/efti/POST/` are served as `POST /efti/<filename_without_ext>`
- YAML header comment declares `description` and `params`
- All reads use `SELECT DISTINCT ON (id) … ORDER BY id, created_at DESC` (append-only pattern)
- The `app` role has only `SELECT, INSERT` — no UPDATE, no DELETE

## Database rules

1. **Append-only everywhere.** Every operational table is INSERT-only. "Updates" insert a new row with the same logical id; latest `created_at` wins.
2. **No JOINs on hot path.** Search columns are denormalised onto `consignments` directly.
3. **Archival by CronManager.** Non-latest rows moved by external Quartz scheduler.

## Kotlin services

- Framework: klite (lightweight, annotation-based)
- Build: Gradle multi-project under `code/`; `./gradlew <project>:test` for unit tests
- Source layout: `src/` for main, `test/` for tests (not standard `src/main/kotlin`)
- JVM 25, Kotlin 2.4.0
- Tests: JUnit 5, MockK, Atrium assertions
- Test JVM args: `-DENV=test -DOWN_GATE_ID=TEST`

## eDelivery / mock gate

- Gate-to-gate communication uses AS4 messaging via edelivery service
- edelivery party registry loads gates + platforms from DB, refreshes every 30 min
- Mock gate: register a second gate (e.g., EU-MOCK) with same `eDeliveryUrl` + `eDeliveryCert` as own gate; messages loop back to self
- Handler routing: `EftiMessageHandlers` checks `receiverId == ownPartyId` to decide local vs remote processing
- `-local` DSL endpoints override `gateId` to `OWN_GATE_ID` before calling templates (prevents infinite forwarding loop)

## Dev seed data (context:dev)

- Users: Super Admin (60001019906, ADMIN), Mari Tamm (60001017869, AUTHORITY)
- Platform: `mock` → `http://ruuter:8086/mock-platform` with `X-Api-Key: mock-secret-key`
- TARA identities: `docker/tara-mock/identities.json`

## Testing

- `tests/*/*.http` — IntelliJ HTTP Client format; run all with `docker compose run --rm http-tests`
  - `TEST_FILES=tests/admin/gates.http` can be prefixed to run only specific tests
- In these files, every new request starts with ### 
- Env file: `tests/http-client.env.json` (local/docker environments)
- Assertions: `> {% client.test("name", () => { client.assert(...) }) %}`
- Health check: `GET /efti/api/v1/test/baasikontoroll` (public, returns DB status)

## Post-change

- If anything listed in `AGENTS.md` changed - update the file
- Always run `git add` for new/changed files 

## Key gotchas

- `constants.ini` uses compose-internal URLs (`http://ruuter:8086`); `.env` uses localhost URLs
- Ruuter `http_codes_allow_list` must include any status you return (401, 403, 204 are not default)
- `internal_requests.block_private_networks: false` in `ruuter.yaml` — auth DSLs call TIM/ReSQL by compose service name
- edelivery test mode uses a hardcoded PKCS#12 keystore (see `KeyManager.kt`); production reads from `certs/own.p12`
- `PartyId` equality is case-insensitive (`.equals(ignoreCase = true)`)
