# AGENTS.md — eFTI Gate (EE)

## What this is

Estonian national eFTI Gate (EU Regulation 2020/1056). Mediates dataset retrieval between certified eFTI Platforms and competent authorities; bridges to peer national gates over eDelivery AS4.

## Architecture at a glance

12 Docker Compose services. Three runtime layers:

| Layer | Tech | Port | Role |
|-------|------|------|------|
| **Ruuter** | Rust DSL engine | 8086 | HTTP API gateway — routes defined as YAML files. Also serves the X-Road national extension under `/xroad/` (`DSL/Ruuter/xroad/`, ADR-006) |
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
      POST/api/v1/      # G2G endpoints: dataset, follow-up, consignments-xml
      POST/internal/     # Auth helpers: check-admin-authority, check-user-authority
      GET/api/v1/        # gates/own, consignments, status, follow-up, test
    admin/              # Admin CRUD routes (served under /admin/)
      GET/v1/           # List/get: gates, platforms, authorities, users, audit, consignments
      POST/v1/          # Create: gates, platforms, authorities, users + ping, revoke-token, js-error
      PUT/v1/           # Update: gates, platforms, authorities, users
      DELETE/v1/        # Delete: gates, platforms, authorities, users, consignments
    auth/               # Authentication routes (served under /auth/)
      GET/              # user (current user profile)
      POST/             # callback, logout, dev-login
    platforms/          # Platform API routes (served under /platforms/)
      POST/v1/          # consignments (upload consignment XML)
    mock-platform/      # Mock platform (served under /mock-platform/)
    xroad/              # X-Road national extension (ADR-006), served under /xroad/
      POST/v1/          # echo (connectivity test); transport-means (identifier lookup: plate/IMO/
                        #   aircraft reg/container id, scope=existence|local); dataset, search,
                        #   follow-up (forward to core)
      GET/v1/           # subsets (subset-permission discovery)
      GET/health/       # ready (probe, unguarded)
  Ruuter-xroad-mock/     # PUBLIC X-Road mock — separate container (ruuter-xroad-mock:8088),
    developer/          #   no DB / no core, canned responses. Served under /developer/.
                        #   See docs/developer/x_road_developer_mock.md.
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
- `body` and `headers` are never null in Ruuter, no need to check for these
- `allowed_body: [xml]` — wraps raw XML body as `incoming.body.xml`
- **Input contract** (`declaration:` + `validate_input`, enforced by `scripts/validate-dsl.py` across all six projects):
  - `declaration.description` documents every expected body field / header / query param and which are optional. **This is the primary tool** — see the `allowlist` caveats below.
  - `declaration.allowlist.body` / `.header` / `.params` — `{field, type, description}` lists. The engine treats a present `allowlist` block as **strict**: it makes **every listed field mandatory** (a missing one is a pre-DSL 500 in a synthetic `declare` step; `required: false` is ignored) and **strips every field not listed** — and header/param stripping happens *before the guard runs*, so an `allowlist.header` that omits `x-road-id` / `authorization` etc. will break the guard. Practically: use `allowlist.body` only on routes where every body field is always required and no other input matters; avoid `allowlist.header` / `.params` entirely for now (the strict-key stripping costs more than the OpenAPI gain).
  - `validate_input:` (or `check_input:`) — the real contract: first step of the file, a `switch` that routes missing/malformed required input to a `400` step (`BAD_REQUEST_GENERAL` / `MISSING_REQUIRED_HEADER` per `docs/specs/errors.json`). Any route reading `incoming.body` needs one. Reconcile with an existing bespoke validator rather than adding a parallel step.
  - `.guard.yml` — prose `declaration.description` only; **no `allowlist`** (would trigger the `declare` enforcement against the guard context). The guard's `switch` steps are its contract.
- `wrapper: false` — always return raw response (not JSON-wrapped)
- `next:` step declaration is optional if it should advance to the next step in the file; otherwise, `next:` is required to call a specific step; `next: end` stops execution
- `template: api/v1/foo` — call another DSL file as subroutine, works only in the same top-level Ruuter project
- **Each top-level dir under the DSL mount is a Ruuter project** (`auth/`, `admin/`, `efti/`, `platforms/`, `mock-platform/`, `xroad/`). `dsl.project:` in `ruuter.yaml` does not gate loading.
- Guard files (Ruuter ≥ 0.9.7-rc) — every `.guard.yml` walking up from the route's directory runs, outermost-first, all must pass:
  - `<project>/.guard.yml` (**project-level**, Ruuter #39) — one file for every method in the project. Used for `admin/`, `platforms/`, and `xroad/` where the whole surface has one auth posture.
  - `<dir>/.guard.yml` (**directory-level**) — applies to every route at/under that dir. Used where posture varies by method/subtree (`efti/`).
  - `declaration.override_ancestors: true` on a nested guard **replaces** all ancestor guards (incl. project-level) for its subtree — used by `xroad/GET/health/.guard.yml` so the health probe is not forced to send `X-Road-Client`.
  - A guard may `assign` vars the handler then reads (`${caller}`, `${authority}`) — the same execution context flows through. Prefer this over a handler re-calling `check-*-authority` just to get the caller row.
  - Per-route sibling guards (`<route>.guard.yml` next to `<route>.yml`) — not used here; behaviour is version-specific (broken in 0.9.4-rc, fixed in 0.9.6-rc #41).
  - `template:` calls invoke the target handler as an engine subroutine and **bypass guards** — a public route can `template:` into a handler that lives under a guarded directory (this is how the G2G `-xml`/`-local` wrappers reach the guarded authority handlers).
- Guard map (see `docs/specs/permissions-matrix.md`):
  - `admin/` GET/POST/PUT/DELETE = authenticated (`check-admin-authority`) — one `admin/.guard.yml` covers all methods
  - `auth/` POST = public; `auth/` GET = any authenticated user (`check-user-authority`)
  - `efti/api/v1/**` (all of it — GET, POST, and `authority/`) = **gate-internal only**, matching `X-Internal-Service-Token` (ADR-006). No TARA/JWT path anywhere under `efti/api/v1/`, not even as a fallback — this surface is reached only by other gate components (the X-Road adapter today; edelivery for the G2G-inbound `-xml`/`-local`/`ping`/`search-xml` routes; G2G inbound proper is earmarked) over the internal network, never directly by a human. `efti/GET/api/v1/test/.guard.yml` overrides back to public for the diagnostic endpoints (`baasikontoroll`, `lubatud`, `piiratud`). The token is a generic internal-service credential — `core` stays X-Road-unaware; the X-Road adapter resolves the organisation from `X-Road-Client` and enforces `authorities.subsets` before forwarding. Deny is the fall-through: an absent or empty header can never match, even if the constant were unset.
  - `platforms/` = platform `X-Api-Key` hash (ADR-004) — one `platforms/.guard.yml`; also covers the G2G `consignments-xml`. **Deny is the fall-through branch**, each accept path an explicit positive condition, so a non-array ReSql body cannot fail open.
  - `xroad/` = `x-road-client` member code resolves to exactly one `ACTIVE` authority (ADR-006). One project-level `xroad/.guard.yml` for both methods; it `assign`s `${authority}` for handlers. **Deny is the fall-through branch** and each accept path an explicit positive condition, so a non-array ReSql body cannot fail open. `xroad/GET/health/.guard.yml` uses `override_ancestors` to stay public (the `efti` probes have no ancestor guard and need none). **`/xroad/**` shares port 8086 with the public gate API — the ingress MUST NOT expose it; only the Security Server may reach it.**
  - do not leave comments in DSL files/code that belong to commit messages

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

- Users: Super Admin (60001019906)
- Platform: `mock` → `http://ruuter:8086/mock-platform` with `X-Api-Key: mock-secret-key`
- TARA identities: `docker/tara-mock/identities.json`

## Testing

- `tests/*/*.http` — IntelliJ HTTP Client format; run all with `docker compose run --rm http-tests`
  - `TEST_FILES=tests/admin/gates.http` can be prefixed to run only specific tests
- In these files, every new request starts with ### 
- Env file: `tests/http-client.env.json` (local/docker environments)
- Assertions: `> {% client.test("name", () => { client.assert(...) }) %}`
- Health check: `GET /efti/api/v1/test/baasikontoroll` (public, returns DB status)

## Branching

- When creating a new branch, its name must describe what the branch sets out to achieve — the
  change or outcome intended, not a ticket id or random slug. Prefer `type/short-goal`, e.g.
  `fix/xroad-dataset-forward-missing-post-handler` or `feat/authority-audit-log`.
- Set a one-line branch description spelling out the goal:
  `git branch --edit-description` (or `git config branch.<name>.description "<goal>"`).

## CI/CD

- `.github/workflows/e2e.yml` — GitHub Actions: builds the compose stack and runs the
  `tests/*/*.http` smoke suite (`docker compose run --rm http-tests`). This is the gate on PRs.
- `.gitlab-ci.yml` — kemitaws platform pipeline (mirror): `release-version` → sonar → nine
  `image-build`s (ruuter, ruuter-xroad-mock, resql, liquibase, tim, ui, edelivery, xml-mapper,
  multiplexer) → SBOM/trivy → `package:charts` trigger into the `efti` devops repo →
  `release-pin` into `environments/dev/release.yaml`. Runs on the default branch and `release/*`.
  Header comment lists the CI/CD variables and the values still to confirm against the devops repo.

## Post-change

- If anything listed in `AGENTS.md` changed - update the file
- Always run `git add` for new/changed files 

## Key gotchas

- `constants.ini` uses compose-internal URLs (`http://ruuter:8086`); `.env` uses localhost URLs
- Constants are `COPY`-ed into the image at build time with **no env substitution**, and `compose.override.yml` syncs only `DSL/` — so changing `constants.ini` or `ruuter.yaml` needs `docker compose up --build ruuter`. A `--watch` restart will not pick it up. (Before the `xroad` project was merged into the main Ruuter this also had to be kept in sync with a second `constants-xroad.ini`; that hazard is gone.)
- `X-Road-Id` must be a UUID: the adapter maps it to `x-request-id` and core hands that to typed `UUID` parameters (`MultiplexerRoutes.kt` `@PathParam searchId: UUID`, edelivery's `e.requestId.uuid`). The X-Road guard enforces the shape and returns 400 `INVALID_REQUEST_ID`.
- Ruuter `http_codes_allow_list` must include any status you return (401, 403, 204 are not default)
- `internal_requests.block_private_networks: false` in `ruuter.yaml` — auth DSLs call TIM/ReSQL by compose service name
- edelivery test mode uses a hardcoded PKCS#12 keystore (see `KeyManager.kt`); production reads from `certs/own.p12`
- `PartyId` equality is case-insensitive (`.equals(ignoreCase = true)`)
- user does not have role related fields. if user exist then they are admin. that's it.