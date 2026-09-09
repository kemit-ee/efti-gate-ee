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
- **Input contract** (`declaration:` + `validate_input`, checked by `scripts/validate-dsl.py`; Ruuter's own `dsl-lint` runs alongside it). Every route in the six projects declares its inputs and enforces the required ones. On `ruuter:0.9.14-rc` (issue turnerrainer/Ruuter#75 landed in 0.9.12-rc):
  - `declaration.description` — documents every expected body field / header / query param and which are optional.
  - `declaration.allowlist.body` structured entries — `required: true` → missing field is a **400** (`{"error": "Field missing: X"}`, before the first DSL step); `required: false` / unset → optional. Body `type:` is wire-enforced (`400` on mismatch). `additive: true` keeps undeclared fields visible; `strict: true` rejects them. Legacy flat `allowed_body: [x]` = every listed field required.
  - `allowlist.headers` / `.params` — filter + OpenAPI only (presence not wire-enforced on a terminal DSL). The guard chain runs on the **raw** request, so a route allowlist can no longer strip a header its guard reads — but a *handler* that reads several headers still needs each one listed or it's stripped from its view, so we still keep header docs in prose, not `allowlist.headers`.
  - `allowlist.required_one_of` (per section) — OR-of-alternatives; enforced (→ 400) on both routes **and guards**.
  - `validate_input:` / `check_input:` — still the way to return a **domain** error code (`MISSING_SUBSET`, `FORBIDDEN_SUBSET`, `BAD_REQUEST_GENERAL` + a helpful `detail`) instead of the engine's generic `{"error": "Field missing: X"}`, and for cross-field / non-empty checks. Every body-reading route has one **or** an `allowed_body` / `allowlist.body`.
  - `.guard.yml` — **prose `declaration.description` only, no `allowlist`.** A guard's `required: true` / `required_one_of` is enforced by the engine as a **400 before the guard's own steps** — but a missing credential must be **401** (`000-auth-guards.http`), which only the guard's `switch` steps return. The guard's steps are its contract.
- `wrapper: false` — always return raw response (not JSON-wrapped)
- `next:` step declaration is optional if it should advance to the next step in the file; otherwise, `next:` is required to call a specific step; `next: end` stops execution
- `template: api/v1/foo` — call another DSL file as subroutine, works only in the same top-level Ruuter project. Since Ruuter 0.9.11-rc it **runs the target's guards** against the child context — forward the credential explicitly (`headers: {x-internal-service-token: "[#INTERNAL_SERVICE_TOKEN]"}` on the template step), as the G2G `-xml` wrappers do.
- **Each top-level dir under the DSL mount is a Ruuter project** (`auth/`, `admin/`, `efti/`, `platforms/`, `mock-platform/`, `xroad/`). `dsl.project:` in `ruuter.yaml` does not gate loading.
- Ruuter runs `turnerrainer/ruuter:0.9.14-rc` (`docker/ruuter/Dockerfile`, `docker/ruuter-xroad-mock/Dockerfile`). 0.9.12-rc resolved the `declaration.allowlist` contract (issue turnerrainer/Ruuter#75): guards run before allowlist stripping, `required: false` honoured, missing-required → 400, body `type:` enforced, `allowlist.required_one_of`, guards can carry enforced declarations. 0.9.13-rc fixed **issue turnerrainer/Ruuter#79** (reporter: @sviljus) — a `guard → template: → same-guard` chain recursed forever and aborted the process; now a per-request guard stack skips an already-running guard and `MAX_GUARD_DEPTH = 32` caps exotic cycles. 0.9.14-rc ships `dsl-lint` / `dsl-test` inside the runtime image (`/usr/local/bin/`, issue #83) and drops the `"null"`-string template header (#85). `/_/openapi.json` is admin-gated (`RUUTER_ADMIN_ENABLED`, unset here).
- Guard files (Ruuter ≥ 0.9.7-rc) — every `.guard.yml` walking up from the route's directory runs, outermost-first, all must pass:
  - `<project>/.guard.yml` (**project-level**, Ruuter #39) — one file for every method in the project. Used for `admin/`, `platforms/`, and `xroad/` where the whole surface has one auth posture.
  - `<dir>/.guard.yml` (**directory-level**) — applies to every route at/under that dir. Used where posture varies by method/subtree (`efti/`).
  - `declaration.override_ancestors: true` on a nested guard **replaces** all ancestor guards (incl. project-level) for its subtree — used by `xroad/GET/health/.guard.yml` so the health probe is not forced to send `X-Road-Client`.
  - A guard may `assign` vars the handler then reads (`${caller}`, `${authority}`) — the same execution context flows through. Prefer this over a handler re-calling `check-*-authority` just to get the caller row.
  - Per-route sibling guards (`<route>.guard.yml` next to `<route>.yml`) — not used here; behaviour is version-specific (broken in 0.9.4-rc, fixed in 0.9.6-rc #41).
  - `template:` calls invoke the target handler as an engine subroutine and, since Ruuter 0.9.11-rc, **run the target's guards** against the child context (pre-0.9.11 they bypassed guards). The G2G `-xml`/`-local` wrappers forward `x-internal-service-token` on the template step so the callee's `efti/POST/api/v1/.guard.yml` passes — this stays load-bearing after #79 (0.9.13-rc): #79 only skips a guard **already on the execution stack**, i.e. a `template:` *inside a guard*. Our `template:` steps sit in route bodies, where the entry guards have already popped, so the child guard runs fresh.
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
- Reads resolve "latest row per logical id" either with `SELECT DISTINCT ON (id) … ORDER BY id, created_at DESC` (fine when a `WHERE` already narrows to one id / a small set) or, on the search hot path, by filtering the base table first and then a self-correlated `NOT EXISTS` "no newer row" anti-join (ADR-009, `get_consignments.sql`). A bare `DISTINCT ON` over the whole table before any filter materialises the entire latest-per-id set every call — see `docs/askend_performance/`.
- The `app` role has only `SELECT, INSERT` — no UPDATE, no DELETE

## Database rules

1. **Append-only everywhere.** Every operational table is INSERT-only. "Updates" insert a new row with the same logical id; latest `created_at` wins.
2. **No JOINs on hot path.** Search columns are denormalised onto `consignments` directly — the rule targets *cross-table* joins (`consignments` → `gates`/`platforms`/…). A self-correlated anti-join against `consignments` itself for append-only latest-row semantics (ADR-009) is allowed — the planner serves it as an Index Only Scan on `idx_consignments_dataset_latest`, not a materialised join.
3. **Archival by CronManager.** Non-latest rows moved by external Quartz scheduler — and it all runs through Ruuter + ReSql, never a DB function. For `consignments`: cold storage is a **separate PostgreSQL instance** (`archive-database` / DB `efti_archive`, table shape in `DSL/Liquibase/archive-init.sql` — plain types, no CHECK/FK, `row_id` PK), reached from the live DB only via `postgres_fdw` (schema `archive`, migration `DSL/Liquibase/changelog/20260910-consignments-archive.sql`). CronManager calls `POST /ops/v1/archive-consignments` (`Authorization: Bearer ARCHIVE_OPS_TOKEN`, guard `DSL/Ruuter/ops/.guard.yml`), which orchestrates three ReSql steps: `archive_consignments.sql` (copy superseded rows whose newer sibling is older than `olderThanDays`, default 30, so the current row incl. a `DELETED` tombstone never moves), `verify_archived_consignments.sql` (read them back from the archive DB), then — only on 100 % confirmation — `delete_archived_consignments.sql`, a **real DELETE** guarded by `USING archive.consignments` so a row leaves the live DB only if it is provably in cold storage. Cold-storage reads: `get_archived_consignment.sql`.

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
- `DSL-tests/**/*.test.yml` — Ruuter `dsl-test` scenarios (`mode: inprocess` — HTTP through the
  in-process router, no compose). Use for anything reachable **before an upstream `call:`**:
  guard rejects, `validate_input` 400s. `mode: mock-http` can stand in for ReSql/xml-mapper.
  Run via `docker run --rm -v "$PWD:/workdir" -w /workdir turnerrainer/ruuter:0.9.14-rc dsl-test --dsl DSL/Ruuter --tests DSL-tests --constants constants.ini` (the binaries ship in the runtime image since 0.9.14-rc).

## Branching

- When creating a new branch, its name must describe what the branch sets out to achieve — the
  change or outcome intended, not a ticket id or random slug. Prefer `type/short-goal`, e.g.
  `fix/xroad-dataset-forward-missing-post-handler` or `feat/authority-audit-log`.
- Set a one-line branch description spelling out the goal:
  `git branch --edit-description` (or `git config branch.<name>.description "<goal>"`).

## CI/CD

- `.github/workflows/e2e.yml` — GitHub Actions:
  - `dsl-validate` — runs Ruuter's `dsl-lint` + `dsl-test` straight from the runtime image
    (`turnerrainer/ruuter:0.9.14-rc`, which ships both binaries since #83) on both DSL roots +
    `DSL-tests/*.test.yml` (in-process scenarios) + `scripts/validate-dsl.py` (the efti-only
    input-contract convention).
  - `e2e` — builds the compose stack, runs the `tests/*/*.http` smoke suite via
    `docker compose run --rm http-tests`. This is the gate on PRs. `dsl-test` is the fast
    in-process check; `tests/*.http` is the full-stack integration gate.
- `.gitlab-ci.yml` — kemitaws platform pipeline (mirror): `secret_detection` + `validate:dsl`
  (same `dsl-lint` / `dsl-test` / `validate-dsl.py` as above) + sonar → nine
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