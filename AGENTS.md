# AGENTS.md — eFTI Gate (EE)

## What this is

Estonian national eFTI Gate (EU Regulation 2020/1056). Mediates dataset retrieval between certified eFTI Platforms and competent authorities; bridges to peer national gates over eDelivery AS4.

## Architecture at a glance

21 Docker Compose services (20 long-running/one-shot jobs + the `http-tests` runner; `turvis-trust` serves the generated Turvis trust view and `turvis-mock` is the dev/test Turvis stand-in, neither has a consumer yet). Three runtime layers:

| Layer | Tech | Port | Role |
|-------|------|------|------|
| **Ruuter** | Rust DSL engine | 8086 | HTTP API gateway — routes defined as YAML files. Also serves the X-Road national extension under `/xroad/` (`DSL/Ruuter/xroad/`, ADR-006) |
| **ReSql** | Rust SQL executor | 8090 | Serves SQL files as HTTP endpoints |
| **Kotlin services** | JVM (klite framework) | 8081–8082 | edelivery (AS4), xml-mapper (XML↔JSON). Each exposes a klite-native `/metrics` (OpenMetrics/Prometheus) endpoint — `code/core/src/metrics/RequestMetrics.kt` |

Supporting: PostgreSQL 18 (54321), TIM (8085, identity), TARA-mock (8888, OIDC), UI (8000, Vite/Svelte),
Prometheus (9090), Grafana (3000), Alertmanager (9093) — observability stack, see
`docs/architecture/observability/monitoring_and_alerting.md`. CronManager (`cronmanager`,
`turnerrainer/cronmanager:0.2.3-alpha`, loopback 8089 in dev only) — scheduler for `/ops/v1/*`, see
"CronManager" below.

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
```

## Directory layout

```
DSL/
  Ruuter/
    efti/               # Internal API routes (served under /efti/)
      POST/api/v1/      # G2G endpoints: dataset, follow-up, consignments-xml
      POST/internal/     # Auth helpers: check-admin-authority, check-user-authority
      GET/api/v1/        # gates/own, consignments, status, follow-up, test
    admin/              # Admin routes (served under /admin/) — READ-ONLY for the three registries
      GET/v1/           # List/get: gates, platforms, authorities, users, audit, consignments,
                        #   consignment-counts + consignment-summary (the registries are served by
                        #   the `registry` service, not by a read model — see "Registries" below)
      POST/v1/          # users, users/revoke-token, js-error (NO registry writes — see "Registries" below)
      PUT/v1/           # users
      DELETE/v1/        # users, consignments
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
  Ruuter-turvis-mock/    # Turvis mock (#306) — separate container (turvis-mock:8091, DEV/TEST ONLY), no DB /
    turvis/             #   no core, no real crypto. The four contract routes under POST/v1/ + GET/health/ready,
                        #   served under /turvis/. Contract: docs/specs/turvis/SPEC.md, run notes:
                        #   docs/specs/turvis/dsl-contracts/README.md.
  Resql/efti/POST/      # SQL endpoint files (*.sql)
  Liquibase/            # DB migrations (initial/ + changelog/)
  CronManager/          # CronManager job set (efti-ops.yaml) — baked into the cronmanager image
registry/               # SOURCE OF TRUTH for gates/platforms/authorities (ADR-011 §1 sources,
  gates/<id>.yml        #   §3 build-time delivery). One YAML per entity, file name = id, certs inline PEM
  platforms/<id>.yml    #   (literal blocks). Converted + validated at image build time into JSON and
  authorities/<id>.yml  #   served statically by nginx; NOT in the database. A SECRET STORE: platform
                        #   entries carry a plaintext apiKey. See registry/README.md.
code/
  edelivery/            # AS4 messaging service
  xml-mapper/           # XML↔JSON conversion (FTI004/009/010/019/021/025/029/030)
  core/                 # Shared: ResqlClient, Party types, XSD schemas
tests/                  # IntelliJ HTTP Client test files (*.http) with assertions
docs/confluence/        # Confluence (wiki.kemit.ee, space EFTI) pages are GENERATED from repo Markdown
                        #   (deployment guide, user guide) by scripts/confluence-sync.py — edit the
                        #   Markdown and re-run it; never edit those pages in Confluence. See its README.
```

## Registries (ADR-011) — a static file server, not a table and not an API

The gates/platforms/authorities registries have **one store**: the git folder `registry/`. There is no
database representation — `gates`, `platforms`, `authorities`, their read models and the
`gate_status`/`authority_status` types were **dropped** (`20261009-drop-registry-tables.sql`).

- **The sources are YAML**: `registry/gates|platforms|authorities/<id>.yml` (14 files), file name must
  equal `id`, certificates as YAML literal blocks (`|`), and a platform's credential as **plaintext
  `apiKey`**. Format + per-field tables: `registry/README.md`. Rationale and section numbers:
  `docs/architecture/decisions/011-registries-as-signed-config.md` — §1 sources (files, not tables),
  §3 the delivery (YAML → build-time JSON → static nginx), §4 no database tables, §5 plaintext
  credential, §7 no tombstone. Its §2 (release-time signing) and §8 (the manifest hash in
  `constants.ini` for drift detection) are stated requirements that are **not implemented yet**.
- **`scripts/registry-to-json.py` (PyYAML + `cryptography`) is converter *and* validator**, and it runs at **image build
  time**: `docker/registry/Dockerfile` is two stages (`python:3.13-alpine` + PyYAML/cryptography → `nginx:stable-alpine`).
  It checks required keys, that every scalar is a **string** (an unquoted `registryCode: 70000099` or
  `countryCode: NO` would become an int/bool that the DSL's `===` never matches), `id` vs file name,
  the `status` enum, `countryCode`, absolute http(s) URLs, PEM shape, `subsets` codes (no duplicates),
  `headers` as string→string, unknown keys, case-insensitive duplicate ids, a platform `apiKey` shared
  by several files (the guard would 403 both) and stray non-`.yml` files, and any failure fails the build.
  `tests/registry/test_registry_to_json.py` (unittest) covers these rules and runs in both CI jobs. CI runs the same converter
  with no `--out` (both `.github/workflows/e2e.yml` and `.gitlab-ci.yml`), so a bad registry fails CI
  without a Docker build. **Fail-closed therefore moved from container start to build time.**
- **Absent and `null` optional keys are omitted** from the generated JSON rather than emitted as `null`.
  Empty collections are *not* absent: `subsets: []` ("entitled to nothing") and `headers: {}` are kept.
- **There is no application server and no runtime mount.** `docker/registry/serve.py` is deleted; stock
  nginx serves `/<type>.json` (the whole registry as one array), `/<type>/<id>.json` and `health.json`
  from `/usr/share/nginx/html` on port **8080** (`[#REGISTRY_URL]` unchanged), with
  `Cache-Control: no-store`, no autoindex and a JSON 404 body. `health.json` + the nginx healthcheck
  keep `depends_on: registry: service_healthy` working for `ruuter` and `edelivery`.
- **No mount means the registry is part of the image**, so every deployment with its own gates,
  platforms and authorities must **build its own image** (`registry/` is replaced before `docker build`).
  Platform API keys therefore live in **image layers** (hence in image storage, the SBOM and trivy
  scans); there is no mount and no Secret that could hold the keys instead (ADR-011 §3/§5). Footgun: an image built
  from this repo serves the **DEV fixtures** (`EU-EE`, `EU-MOCK`, `mock` with the plaintext
  `mock-secret-key`, and the `auth-*` test authorities), so a production deployment must not use it
  unmodified.
- **Per-entity files are named with the lower-cased id** — a static server is case-sensitive, but ids
  used to be `CITEXT`. The DSL lower-cases the id before every by-id request (7 sites), so `EU-EE`,
  `eu-ee` and `Eu-Ee` all still resolve; only a *direct* manual fetch must be lower-case
  (`GET /gates/EU-EE.json` is a 404).
- **...and it is a secret store.** ADR-011 §5 amends ADR-004: Ruuter's expression engine has no hash
  function at all (verified: no `crypto`/`crypto.subtle`/`TextEncoder`/`atob`), so the platform key
  cannot be SHA-256'd in the DSL. The key is therefore plaintext in the file and compared verbatim.
  Consequences that must not regress: the `registry` image has **no published port** (same posture as
  resql) and must never be proxied or ingressed; the admin platform routes **strip `apiKey`** and return
  a derived `hasApiKey`; `platforms/.guard.yml` strips `apiKey` from the `${platform}` it passes to
  handlers.
- **It does no filtering, no auth and no lookups.** The `status` checks, the `X-Api-Key` comparison and
  the authority `registryCode` ambiguity rule live in the Ruuter DSL. The whole-registry documents exist
  because **Ruuter has no loops** — a list or a by-code lookup cannot be assembled from N file fetches.
- **Turvis trust view (A9; ADR-011 amendment "Täiendus: Turvise usaldusvaade", #316; Turvis SPEC
  §7.2–7.4 in `docs/specs/turvis/SPEC.md`).** `registry/` stays the only hand-edited source of peer
  identity/status/certificate; Turvis never reads the `registry` service (it serves plaintext platform
  `apiKey`s, §5) and gets only a **generated, allowlist-filtered `turvis-trust.json`** built from the
  same revision as a separate artifact. Implemented by #315: the converter builds the view on **every**
  run (so plain validation covers it), `docker/turvis-trust/Dockerfile` + compose `turvis-trust` serve it
  (internal only, no consumer yet), the `registry` image's `health.json` carries the same `generation` as
  `trustViewGeneration`, and `SOURCE_REVISION` (build arg) must be passed identically to both images (GitHub Actions does; the
  GitLab `builds:` pass none yet, so it reads `unknown` there). The `turvis-trust` build stage copies only
  `registry/gates` and `registry/trust-anchors` (no platform `apiKey` in its layers) and the final image
  serves only `turvis-trust.json` + `health.json`. Trust anchors must be CA certificates; two gates sharing a
  certificate (the dev `EU-EE`/`EU-MOCK`) only print a converter WARNING.
  Fields come from an allowlist; `verified_identity` = the gate `id`; optional `registry/trust-anchors/<alias>.pem`
  and per-gate `allowedChainAnchors` (view-only, stripped from the registry JSON). Status is
  `ONLINE`/`DISABLED` with **deny wins** across edelivery's registry view and Turvis's view; pinning is
  the per-party default, chain of trust is per-party data (`allowed_chain_anchors`). Phase-2 integrity
  (release signing §2/§8 vs an A3 signed manifest) is **open**; Phase 1 is only a `generation` hash check,
  not a signature. `eDeliveryCert` stays optional: a certless gate is left out of the view (as of the
  AS4 party map), an existing unparseable/expired/not-yet-valid one fails the build. `.pem` is accepted only under
  `registry/trust-anchors/` (elsewhere still a build failure). Generations may differ temporarily
  (accepted, 5-minute alert, each side decides on its own view). A registry edit needs both images
  rebuilt (`docker compose up --build registry turvis-trust`). The dev gates `EU-EE`/`EU-MOCK` share one
  certificate valid until 2027-08-25: after that the converter (and CI) fail until it is replaced.
- **A registry edit needs `docker compose up --build registry`**: the JSON is generated and validated at
  build time, so `restart` alone applies nothing and `compose.override.yml` has no `develop.watch` entry
  for it any more (there is no mount to sync).
- **There is no HTTP write path.** The admin `POST`/`PUT`/`DELETE` routes for these three registries —
  including `ping` and `api-key` — do not exist, and neither do their ReSql endpoints. Only `GET`
  remains, and the Admin UI is read-only for them. `users` and `consignments` keep their admin writes.
- `status` is registry-owned: gates/platforms are `ONLINE` or `DISABLED`. `DISABLED` is a full stop
  everywhere: not in the search fan-out, cannot authenticate (`platforms/.guard.yml`),
  `dataset-local`/`follow-up-local` answer 404 as for an unknown id, and `edelivery`'s
  `RegistryClient` leaves it (and a gate without `eDeliveryCert`) out of the AS4 party map. **Authorities have no
  status at all** — a file's existence *is* the authority being active. There is no `DELETED` value and
  no tombstone: deleting the file removes the entity, so lookups 404 and guards deny, and the id (and an
  authority's `registryCode`) become free again. Git history is the only record.
- No ping job: the admin ping routes, the CronManager job definition, `edelivery`'s internal
  `POST /api/v1/ping/:partyId` and `gates.last_ping_at` are all gone.
- Detected at build time, not at startup, not by tests: a bad field name/typo in a registry file fails
  the converter (which names the file and the field, because the loader rejects unknown keys) and with
  it the image build — nothing is validated at runtime, because there is no runtime code.

## Nginx proxy (UI)

The UI container (`docker/ui/nginx.conf`) proxies browser requests to backend services:

| Location | Backend | Purpose |
|----------|---------|---------|
| `/admin/` | `http://ruuter:8086` | Admin API — read-only registries (gates, platforms, authorities) + users/consignments |
| `/auth/` | `http://ruuter:8086` | Authentication (user, callback, logout, dev-login) |
| `/tim/` | `http://tim:8085/` | Token & Identity Manager |
| `/tara/` | `https://tara-mock:8080/` | TARA OIDC mock |
| `/` | static files | SPA fallback to `index.html` |

The UI API client (`code/ui/src/api/api.ts`) uses `/admin/v1/` as the default prefix. Paths starting with `/` (like `/auth/callback`) are used as-is, routing to the `auth` project.

## DSL conventions (Ruuter)

- Routes map 1:1 to file paths: `GET /admin/v1/gates` → `DSL/Ruuter/admin/GET/v1/gates.yml`
- Auth routes: `POST /auth/callback` → `DSL/Ruuter/auth/POST/callback.yml`
- Internal routes: `POST /efti/internal/check-admin-authority` → `DSL/Ruuter/efti/POST/internal/check-admin-authority.yml`
- Constants from `constants.ini` referenced as `[#VARIABLE]` (e.g., `[#OWN_GATE_ID]`, `[#EDELIVERY_URL]`)
- Request data: `incoming.body`, `incoming.headers`, `incoming.params.pathParams`
- `body` and `headers` are never null in Ruuter, no need to check for these
- `allowed_body: [xml]` — wraps raw XML body as `incoming.body.xml`
- **Input contract** (`declaration:` + `validate_input`, checked by `scripts/validate-dsl.py`; Ruuter's own `dsl-lint` runs alongside it). Every route in the six projects declares its inputs and enforces the required ones. On `ruuter:0.12.1-rc` (issue turnerrainer/Ruuter#75 landed in 0.9.12-rc):
  - `declaration.description` — documents every expected body field / header / query param and which are optional.
  - `declaration.allowlist.body` structured entries — `required: true` → missing field is a **400** (`{"error": "Field missing: X"}`, before the first DSL step); `required: false` / unset → optional. Body `type:` is wire-enforced (`400` on mismatch). `additive: true` keeps undeclared fields visible; `strict: true` rejects them. Legacy flat `allowed_body: [x]` = every listed field required.
  - `allowlist.headers` / `.params` — filter + OpenAPI only (presence not wire-enforced on a terminal DSL). The guard chain runs on the **raw** request, so a route allowlist can no longer strip a header its guard reads — but a *handler* that reads several headers still needs each one listed or it's stripped from its view, so we still keep header docs in prose, not `allowlist.headers`.
  - `allowlist.required_one_of` (per section) — OR-of-alternatives; enforced (→ 400) on both routes **and guards**.
  - `validate_input:` / `check_input:` — still the way to return a **domain** error code (`MISSING_SUBSET`, `FORBIDDEN_SUBSET`, `BAD_REQUEST_GENERAL` + a helpful `detail`) instead of the engine's generic `{"error": "Field missing: X"}`, and for cross-field / non-empty checks. Every body-reading route has one **or** an `allowed_body` / `allowlist.body`.
  - `.guard.yml` — **prose `declaration.description` only, no `allowlist`.** A guard's `required: true` / `required_one_of` is enforced by the engine as a **400 before the guard's own steps** — but a missing credential must be **401** (`000-auth-guards.http`), which only the guard's `switch` steps return. The guard's steps are its contract.
- **Audit-clean declarations** (`dsl-lint --audit` reports 0 warnings, enforced as errors in CI): every route sets `internal: false` (explicit, same as the framework default), declares `returns:` (`[]` for empty/XML/plain-text bodies), and lists every `${incoming.body|params|headers.*}` it reads in `allowlist.*` with `type:` on body fields. Routes whose allowlist is only documentation add `additive: true` so nothing is stripped (`pathParams` is declared under `allowlist.params` for the same reason). Body `type:` is wire-enforced, so a wrong type is the engine's generic 400, not a domain error code.
- `wrapper: false` — always return raw response (not JSON-wrapped)
- `next:` step declaration is optional if it should advance to the next step in the file; otherwise, `next:` is required to call a specific step; `next: end` stops execution
- `template: api/v1/foo` — call another DSL file as subroutine, works only in the same top-level Ruuter project. Since Ruuter 0.9.11-rc it **runs the target's guards** against the child context — forward the credential explicitly (`headers: {x-internal-service-token: "[#INTERNAL_SERVICE_TOKEN]"}` on the template step), as the G2G `-xml` wrappers do.
- **Each top-level dir under the DSL mount is a Ruuter project** (`auth/`, `admin/`, `efti/`, `platforms/`, `mock-platform/`, `xroad/`). `dsl.project:` in `ruuter.yaml` does not gate loading.
- Ruuter runs `turnerrainer/ruuter:0.12.1-rc` (`docker/ruuter/Dockerfile`, `docker/ruuter-xroad-mock/Dockerfile`). 0.9.12-rc resolved the `declaration.allowlist` contract (issue turnerrainer/Ruuter#75): guards run before allowlist stripping, `required: false` honoured, missing-required → 400, body `type:` enforced, `allowlist.required_one_of`, guards can carry enforced declarations. 0.9.13-rc fixed **issue turnerrainer/Ruuter#79** (reporter: @sviljus) — a `guard → template: → same-guard` chain recursed forever and aborted the process; now a per-request guard stack skips an already-running guard and `MAX_GUARD_DEPTH = 32` caps exotic cycles. 0.9.14-rc ships `dsl-lint` / `dsl-test` inside the runtime image (`/usr/local/bin/`, issue #83) and drops the `"null"`-string template header (#85). 0.9.15-rc fixed **issue turnerrainer/Ruuter#89** — `http.*` transport failures (connection refused, DNS, TLS handshake, read/write timeout) are surfaced in-band as `result.response.status == 0` with `result.response.error` in `{timeout, connect, request, body, decode, unknown}` instead of aborting to a generic 500, so a `check_*` switch can return a semantic 502; policy-level pre-flight rejections (SSRF, host-allowlist, malformed URL, size cap) still raise. `/_/openapi.json` is admin-gated (`RUUTER_ADMIN_ENABLED`, unset here). 0.10.1-rc adds graceful SIGTERM/SIGINT shutdown (in-flight requests finish before exit) and a multipart part-count/per-part-size cap (`multipart_max_parts` default 100, `multipart_max_part_size` default 4 MiB, both `null`-able) that returns `413` — moot here since no DSL route accepts multipart bodies (AS4 multipart is terminated by the Kotlin `edelivery` service, not Ruuter). 0.11.0-rc adds three additive DSL primitives: `parallel_http` step (bounded concurrent fan-out with `collect_ok`/`collect_all`/`first_n` aggregation), `detach` step (continue work after the response is sent) and `declaration.proxy` (streaming byte-identical pass-through proxy for `multipart/related`, bypasses the global 16 MiB preflight in favour of its own `max_body_bytes`). `parallel_http` + `detach` are now used by `efti/POST/api/v1/authority/search.yml` for the K4 cross-gate fan-out (ADR-013); `declaration.proxy` is still unused. 0.12.0-rc adds `declaration.internal: true|false` (a DSL resolving to internal returns **404** on external HTTP, not 403; `template:` sub-calls and self-call-shortcircuited `http.*` bypass the gate) with a three-level fallback (per-DSL field → `declarations.default_internal` in `ruuter.yaml` → framework-default `false`), plus `declarations.missing_internal_policy` (`silent`/`warn`/`error`) and `dsl-lint --require-internal-explicit` — all additive, no wire change on upgrade unless we opt in; not yet used by our DSLs. 0.12.1-rc adds the admin-gated `GET /_/audit/dsl` self-audit endpoint + `dsl-lint --audit` (same engine, declaration-completeness / allowlist-drift / security-posture findings) and promotes two parse-time errors: `declaration.strict: true` with no allowlist, and `allowlist.required_one_of` naming a field absent from this DSL's own allowlist.
- Guard files (Ruuter ≥ 0.9.7-rc) — every `.guard.yml` walking up from the route's directory runs, outermost-first, all must pass:
  - `<project>/.guard.yml` (**project-level**, Ruuter #39) — one file for every method in the project. Used for `admin/`, `platforms/`, and `xroad/` where the whole surface has one auth posture.
  - `<dir>/.guard.yml` (**directory-level**) — applies to every route at/under that dir. Used where posture varies by method/subtree (`efti/`).
  - `declaration.override_ancestors: true` on a nested guard **replaces** all ancestor guards (incl. project-level) for its subtree — used by `xroad/GET/health/.guard.yml` so the health probe is not forced to send `X-Road-Client`.
  - A guard may `assign` vars the handler then reads (`${caller}`, `${authority}`) — the same execution context flows through. Prefer this over a handler re-calling `check-*-authority` just to get the caller row.
  - Per-route sibling guards (`<route>.guard.yml` next to `<route>.yml`) — not used here; behaviour is version-specific (broken in 0.9.4-rc, fixed in 0.9.6-rc #41).
  - `template:` calls invoke the target handler as an engine subroutine and, since Ruuter 0.9.11-rc, **run the target's guards** against the child context (pre-0.9.11 they bypassed guards). The G2G `-xml`/`-local` wrappers forward `x-internal-service-token` on the template step so the callee's `efti/POST/api/v1/.guard.yml` passes — this stays load-bearing after #79 (0.9.13-rc): #79 only skips a guard **already on the execution stack**, i.e. a `template:` *inside a guard*. Our `template:` steps sit in route bodies, where the entry guards have already popped, so the child guard runs fresh.
- Guard map (see `docs/specs/permissions-matrix.md`):
  - `admin/` GET/POST/PUT/DELETE = authenticated (`check-admin-authority`) — one `admin/.guard.yml` covers all methods. For gates/platforms/authorities only GET exists ([ADR-011](docs/architecture/decisions/011-registries-as-signed-config.md) §4/§6 removed the write routes and the tables), so the method list is wider than the actual registry surface by design.
  - `auth/` POST = public; `auth/` GET = any authenticated user (`check-user-authority`). `dev-login` returns 404 unless `DEV_LOGIN_ENABLED=true`; the Docker build default is false, only `compose.override.yml` opts in for local development/CI.
  - `efti/api/v1/**` (all of it — GET, POST, and `authority/`) = **gate-internal only**, matching `X-Internal-Service-Token` (ADR-006). No TARA/JWT path anywhere under `efti/api/v1/`, not even as a fallback — this surface is reached only by other gate components (the X-Road adapter today; edelivery for the G2G-inbound `-xml`/`-local`/`ping`/`search-xml` routes; G2G inbound proper is earmarked) over the internal network, never directly by a human. `efti/GET/api/v1/test/.guard.yml` overrides back to public for the diagnostic endpoints (`baasikontoroll`, `lubatud`, `piiratud`). The token is a generic internal-service credential — `core` stays X-Road-unaware; the X-Road adapter resolves the organisation from `X-Road-Client` and enforces the authority's `subsets` (read from the registry) before forwarding. Deny is the fall-through: an absent or empty header can never match, even if the constant were unset. The Kotlin services (`InternalServiceTokenAuth`) likewise fail closed on an empty token; only `compose.override.yml` (local development/CI) defaults `INTERNAL_SERVICE_TOKEN` to `dev-internal-service-token-change-me` for the Kotlin services that take it (edelivery, xml-mapper), so `compose.yml` alone (production-like) never ships a known token.
  - `platforms/` = platform `X-Api-Key`, compared in plaintext against `registry/platforms/*.yml` (served as `/platforms.json`; ADR-004 as amended by ADR-011 §5: Ruuter has no hash function, so the SHA-256 rule was dropped). Only `ONLINE` authenticates; the guard strips `apiKey` from the `${platform}` it hands to handlers. Internal eDelivery calls require a non-empty service token plus `X-Platform-Id` (the original inbound sender, response-key `receiverId`). Both upload forms check the mapped UIL against the resolved platform and `OWN_GATE_ID`. The XML wrapper forwards the incoming credentials and owner rather than replacing an API key with the service token. Guards require actual arrays and exactly one identity; a non-array ReSql body cannot fail open.
  - `xroad/` = `x-road-client` member code resolves to exactly one authority in `registry/authorities/` by `registryCode` (ADR-006/ADR-011; authorities have no status). One project-level `xroad/.guard.yml` for both methods; it `assign`s `${authority}` for handlers. **Deny is the fall-through branch** and each accept path an explicit positive condition, so a non-array ReSql body cannot fail open. `xroad/GET/health/.guard.yml` uses `override_ancestors` to stay public (the `efti` probes have no ancestor guard and need none). **`/xroad/**` shares port 8086 with the public gate API — the ingress MUST NOT expose it; only the Security Server may reach it.**
  - do not leave comments in DSL files/code that belong to commit messages

## SQL conventions (ReSql)

- Runtime: `turnerrainer/resql:0.4.3-alpha`, distroless UID 65532. Use `/app/resql health --url http://127.0.0.1:8090/health`; no shell/curl is available. Config/SQL files must be readable by UID 65532. 0.4.3-alpha is additive only (new `Traceparent` response header, truncated error messages for oversized URL paths, graceful DB-pool close on SIGTERM) — no config or wire changes needed on our side.
- ReSQL is trusted only on the internal Compose network (`security.trust_network: true`); its local published port binds to 127.0.0.1. Non-2xx query responses carry `[]` plus `X-Resql-Error-Code` / `X-Resql-Error-Message`; check status before empty-row semantics.
- TIM uses `0.4.1-alpha` with required introspection client auth. Provision `TIM_INTROSPECT_RUUTER_SECRET` outside local development. Existing `/jwt/userinfo` calls use the compatibility gate's default-off posture. **0.4.1-alpha's container is distroless** (`gcr.io/distroless/cc-debian12:nonroot`, UID 65532 — matches ReSQL's own posture above) — `docker/tim/Dockerfile` no longer runs an entrypoint script or `apt-get`; a separate `tim-init` service/container (`docker/tim-init/`) does the CA-trust wait/import (TARA-Mock's self-signed cert is written to a bundle at `/tim-ca-bundle/ca-bundle.pem`, and TIM is pointed at it via `SSL_CERT_FILE`, since there's no OS trust store to run `update-ca-certificates` against) and RSA JWT-key generation into shared volumes *before* the `tim` service starts (`depends_on: tim-init: condition: service_completed_successfully`); the healthcheck uses the `tim healthcheck` subcommand instead of `curl`.
- Ruuter 0.10.0 defaults inbound requests to 30 seconds; `ruuter.yaml` raises this to 90 seconds for the 65–70-second G2G timeout ladder. Upstream response bodies default to a 16 MiB cap.

- Files in `DSL/Resql/efti/POST/` are served as `POST /efti/<filename_without_ext>`
- YAML header comment declares `description` and `params`
- Reads resolve "latest row per logical id" either with `SELECT DISTINCT ON (id) … ORDER BY id, created_at DESC` (fine when a `WHERE` already narrows to one id / a small set) or, on the search hot path, by filtering the base table first and then a self-correlated `NOT EXISTS` "no newer row" anti-join (ADR-009, `get_consignments.sql`). A bare `DISTINCT ON` over the whole table before any filter materialises the entire latest-per-id set every call — see `docs/performance/askend_perf_verification/`.
- The `app` role has only `SELECT, INSERT` — no UPDATE, no DELETE
- Cross-gate search state (`search_results`, K4/ADR-013) is append-only and ephemeral: `insert_search_pending` / `insert_search_complete` write rows (`pending` → `complete` with a JSONB `ConsignmentRow[]`), `get_search_result` reads the latest per `search_id`, and `delete_expired_search_results` (db_archiver role, via `POST /ops/v1/purge-search-results`) purges rows older than `keepMinutes` (default 10). It is not `async_responses`, which is a 1:1 claim/drain hand-off for AS4 replies.
- Resolve latest rows before filtering mutable credentials, status, registry code or identifiers. An identity change must not make historical credentials current again. User rename preserves `secret_hash`, `is_active`, `token_revoked_at`; changing `tara_sub` sets a revocation cutoff.
- Latest ordering is `created_at DESC, revision DESC`, including the search anti-join. `20260914-latest-row-order.sql` adds identity revisions and serializes registry appends with advisory transaction locks, preserving inactive users/revocation markers, deleted registry entities and newer API keys; `20261008` narrowed the `DELETED`-reactivation guard to `users`, and `20261009-drop-registry-tables.sql` (ADR-011 §4) then dropped `gates`/`platforms`/`authorities` and their read models entirely, leaving `protect_registry_append()` covering `users` only. `DSL/Liquibase/init.sql` is the consolidated empty-database schema; keep it synchronized with DDL migrations. Existing Liquibase installs use the unchanged master history (no checksum rewrites). Both CI jobs also run `python3 tests/sql/regression.py --init`.
- Equipment EQ uses GIN-compatible `array @> ARRAY[value]`; NE means not contained, with NULL arrays treated as empty. The existence check (`check_transport_means_registered.sql`) materialises index-filtered candidate keys and resolves each latest version via a self-table LATERAL lookup; the identifier projection (`get_consignments_by_transport_means.sql`) sorts the index matches in a subquery and applies the ADR-009 "no newer row" anti-join over that ordered stream up to its 50-row cut. Neither sorts the whole table; both are allowed under the no-cross-table-JOIN rule.
- `idx_consignments_created_latest (created_at DESC, revision DESC)` lets a broad `get_consignments` search walk newest-first and stop at LIMIT. GIN `@>` estimates a rare element at 0.5%, so an array EQ criterion would make the planner walk that index for an id that occurs once: `get_consignments` turns its sort key into an expression when any array criterion is EQ (the CASE folds at plan time, ReSql binds values), and `select_archivable_consignments` always orders by an expression. `python3 tests/sql/regression.py --init` and the E2E suite cover both.
- Every caller-controlled page is capped at 1000; negative limits/offsets clamp to zero. Registry pages explicitly order by id; log pages order by time and row_id. Consignment versions use `(platform_id, dataset_id)`; verification includes the owner, and admin deletion requires `platformId` and `gateId` query parameters.

## Database rules

1. **Append-only everywhere.** Every operational table is INSERT-only. "Updates" insert a new row with the same logical id; latest `created_at` wins.
2. **No JOINs on hot path.** Search columns are denormalised onto `consignments` directly — the rule targets *cross-table* joins (historically `consignments` → `gates`/`platforms`; those tables no longer exist). A self-correlated anti-join against `consignments` itself for append-only latest-row semantics (ADR-009) is allowed — the planner serves it as an Index Only Scan on `idx_consignments_dataset_latest`, not a materialised join.
3. **Archival by CronManager.** Non-latest rows moved by external Quartz scheduler — through **Ruuter + ReSql**, never a DB function or a foreign-data wrapper. Cold storage for `consignments` is a **separate PostgreSQL instance** (`archive-database` / DB `efti_archive`, table in `DSL/Liquibase/archive-init.sql` — plain types, no CHECK/FK, `row_id` PK). ReSql has a second datasource `archive`; SQL under `DSL/Resql/archive/` runs against it (`resql.yaml` `project_datasource_map`). CronManager calls `POST /ops/v1/archive-consignments` (`Authorization: Bearer ARCHIVE_OPS_TOKEN`, guard `DSL/Ruuter/ops/.guard.yml`; one batch per call, re-invoke until `candidates` is 0). The route carries rows between the datasources: `efti/select_archivable_consignments.sql` (superseded rows whose newer sibling is older than `olderThanDays`, default 2 — the current row incl. a `DELETED` tombstone never moves) → `archive/insert_archived_consignments.sql` (idempotent, `ON CONFLICT (row_id) DO NOTHING`) → `archive/verify_archived_consignments.sql` (read back from the archive DB) → **only on 100 % confirmation** → `efti/delete_consignments.sql`, a real DELETE guarded by `EXISTS (newer sibling)`. A mismatch logs `FAILED` and deletes nothing. `POST /ops/v1/purge-archive` → `archive/purge_archived_consignments.sql` (retention, `keepDays` default 2555). Full history: `GET /admin/v1/consignment-history?datasetId=…[&limit=&offset=]` — metadata only (no `xml`), each side (live, archived) paginated and capped at 1000, because an uploader controls how many versions a dataset has.
4. **Read models are INSERT-only generation snapshots (ADR-012).** Derivative tables `rm_*` carry a `generation`; `read_model_pointer` (INSERT-only, highest `revision` per `model` wins) names the current one. One SQL statement (`efti/refresh_consignment_counts.sql`) writes the snapshot and the pointer row atomically under an advisory xact lock, so readers see the old or the complete new generation. `POST /ops/v1/refresh-read-models` (CronManager, `ARCHIVE_OPS_TOKEN`) refreshes and then runs `efti/purge_read_model_generations.sql` (DELETE of generations older than `keepGenerations`, default 2). Reads (`efti/get_consignment_counts.sql`, served by `GET /admin/v1/consignment-counts`) select `generation = (latest pointer)` by primary key. Never route security-critical or verify-after-write reads through a read model; registry data is not a read model — it is baked into the `registry` image, so an edit applies only after `docker compose up --build registry` (and `edelivery` picks it up within `REGISTRY_REFRESH_SECONDS`). Authority consignment summary (`GET /admin/v1/consignment-summary?authorityId=&from=&to=`, manual inclusive day range over the registration day) reads `efti/get_consignment_summary.sql` from `rm_consignment_summary` (refreshed by `efti/refresh_consignment_summary.sql` via the cron route): the route resolves the authority and its `subsets` from the `registry` service (`GET /authorities/<id>.json`) and the query returns only the ungated total plus the dimensions of those subsets (EU02 dangerous goods, EU03 loading/unloading country, EU04 transport mode/type/registration country). Only `consignments` has read models now: `rm_consignment_counts`/`rm_consignment_summary` (the registry read models `rm_gates`/`rm_platforms`/`rm_authorities` were dropped with ADR-011 §4, and `POST /ops/v1/refresh-read-models` no longer refreshes any registry). Admin registry lists (`GET /admin/v1/{gates,platforms,authorities}` without an id) page the `registry` service's whole-registry document in the DSL. DENORM-1 border-check stays query-level (0.4 ms at 1M rows, `python3 tests/sql/regression.py --performance --rows 1000000`).

## Kotlin services

- eDelivery renders String responses as raw XML (including the empty poll response), not JSON-encoded strings. Ruuter ≥ 0.10.0 decodes upstream bodies by their declared Content-Type.

- Framework: klite (lightweight, annotation-based)
- Build: Gradle multi-project under `code/`; `./gradlew <project>:test` for unit tests
- Source layout: `src/` for main, `test/` for tests (not standard `src/main/kotlin`)
- JVM 25, Kotlin 2.4.20
- Tests: JUnit 5, MockK, Atrium assertions
- Test JVM args: `-DENV=test -DOWN_GATE_ID=TEST`
- Logging: SLF4J via `klite-slf4j`; `LOGGER_CLASS` picks the implementation (`.env`, default
  `klite.slf4j.EcsJsonLogger` — ECS-shaped JSON lines, `trace.id` = the per-request virtual thread name
  klite already sets, so request-id correlation needs no manual MDC put/clear). `LOGGER_SERVICE_NAME` is
  set per service in `compose.yml`. Set `LOGGER_CLASS=klite.slf4j.StackTraceOptimizingLogger` for plain
  text instead.
- Metrics: `code/core/src/metrics/RequestMetrics.kt` is a klite `Decorator` registered in each service's
  DI registry *before* constructing `Server` (decorators are snapshotted once at construction); each
  Launcher also calls `metrics()` to expose `/metrics`. Scraped by the `prometheus` compose service.

## eDelivery / mock gate

- Gate-to-gate communication uses AS4 messaging via edelivery service
- edelivery party registry loads the **`ONLINE`** gates + platforms from the `registry` service (`RegistryClient`), refreshes every 60 s; a `DISABLED` party or a gate without `eDeliveryCert` is not an AS4 party
- Mock gate: register a second gate (e.g., EU-MOCK) with same `eDeliveryUrl` + `eDeliveryCert` as own gate; messages loop back to self
- No message bus: cross-node AS4 replies go through `async_responses` (`DbAsyncResponseProvider`: insert when no local waiter, atomic claim by `RequestKey`; `POST /ops/v1/purge-async-responses` via CronManager). `edelivery` reloads gates/platforms from the `registry` service every `REGISTRY_REFRESH_SECONDS` (default 60); admin DSLs do not notify.
- Cross-gate identifier search is Ruuter-owned (K4, ADR-013; the Klite `multiplexer` is retired): `efti/POST/api/v1/authority/search.yml` registers a `pending` row in `search_results`, `detach`es a `parallel_http collect_all` fan-out to every ONLINE peer via `edelivery /api/v1/send/{gateId}`, converts each FTI021 reply with `xml-mapper /search/response-to-json`, and writes the flattened `ConsignmentRow[]` as a `complete` JSONB row. A poll (`X-Request-Id` + `X-Poll: true`) reads the latest row, waiting ~30s while `pending`. Rows are purged by `POST /ops/v1/purge-search-results` via CronManager.
- Handler routing: `EftiMessageHandlers` checks `receiverId == ownPartyId` to decide local vs remote processing
- `-local` DSL endpoints override `gateId` to `OWN_GATE_ID` before calling templates (prevents infinite forwarding loop)

## CronManager

- `turnerrainer/cronmanager:0.2.3-alpha` (`docker/cronmanager/Dockerfile`): the job set
  `DSL/CronManager/efti-ops.yaml` and `docker/cronmanager/cronmanager.yaml` are `COPY`-ed in, so a job
  change needs `docker compose up --build cronmanager`. Group `efti-ops`; six `POST` jobs to
  `http://ruuter:8086/ops/v1/{archive-consignments,purge-archive,purge-async-responses,purge-search-results,refresh-read-models,expire-identifiers}`.
- The job schema is `deny_unknown_fields`: there is **no** `body`, `timeout`, `retry: {...}` or `${ENV}`
  substitution in `url` (the old Buerostack-style YAML had all four and would not load). Retries are
  `retryCount`/`retryDelay` (ms); the outbound timeout is `limits.request_timeout_secs` (120). Every
  job POSTs an empty body, so the routes' body defaults apply.
- Credentials: `headerEnvs: {Authorization: OPS_AUTHORIZATION}` — the env var holds the **whole**
  header, `Bearer <ARCHIVE_OPS_TOKEN>` (same value as `constants.ini`). Unset/empty → the file is
  skipped at load, no job runs. `CRONMANAGER_ADMIN_TOKEN` gates `/execute|/stop|/reload` and
  `/jobs|/running`; unset → CronManager refuses to start. `compose.yml` passes both through with no
  default; the dev values come from `.env` (like `INTERNAL_SERVICE_TOKEN`), which CI's
  `docker compose -f compose.yml up` also reads — the override file does not reach CI.
- `security.block_private_networks: false` is required (Ruuter resolves to a private address; there is
  no host allow-list). With `APP_ENV`/`ENVIRONMENT`/`DEPLOY_ENV` = production/staging/test CronManager
  refuses to boot with it — an open upstream gap, see `docs/specs/deploy/README.md`.
- No `database:` block — the gate's `jobs_execution_log` is the run record. Fire a job by hand:
  `curl -X POST -H "Authorization: Bearer $CRONMANAGER_ADMIN_TOKEN" http://127.0.0.1:8089/execute/efti-ops/<job>`.
- Cabotage expiry (Reg 2024/1942 Art 11(4)), hourly at :45: `POST /ops/v1/expire-identifiers` →
  `efti/expire_road_consignments.sql`, one statement that appends an `INACTIVE` copy of every current
  `ACTIVE` road row (`transport_mode = '3'`) whose `acceptance_date` is more than 14 days ago — at most
  1000 per call (`more: true` = full batch). There is no `expires_at` column; the window is computed
  (partial index `idx_consignments_road_expiry`). Optional body `{dryRun: true}` only counts and logs
  nothing; otherwise `jobs_execution_log` gets `COMPLETED`/`FAILED`, and a ReSql error is a 502.
- ReSql returns column aliases camel-cased (`purged_count` → `purgedCount`); a DSL reading
  `body[0].purged_count` gets `null`.

## Dev seed data (context:dev)

- Users: Super Admin (60001019906) — still a Liquibase `context:dev` changeset (ADR-011 §9 keeps users on the API).
- **Gates, platforms and authorities are not seeded anywhere** — not by Liquibase (no tables exist) and
  not by a sync step. They are the committed YAML files under `registry/`, which the `registry` image
  serves as generated JSON: `registry/gates/{EU-EE,EU-MOCK}.yml`,
  `registry/platforms/{mock,mock-edelivery}.yml` (`mock` → `http://ruuter:8086/mock-platform`, headers
  `X-Api-Key: mock-secret-key`, `apiKey: mock-secret-key`), and 10 `registry/authorities/auth-*.yml`
  fixtures used by `tests/authority/*` and `tests/admin/consignment-summary.http`. An image built from
  this repo therefore carries these dev credentials.
- TARA identities: `docker/tara-mock/identities.json`

## Operations

- `docs/runbooks/` — one file per common failure scenario (symptoms/diagnosis/resolution/escalation
  template in `docs/runbooks/README.md`): database connectivity loss, container/pod crash loop, high
  error rate, disk full, certificate expiry, eDelivery communication failure, TARA authentication
  outage, X-Road service unavailable.
- Observability stack (`prometheus`, `grafana`, `alertmanager` compose services): Grafana dashboard at
  `http://localhost:3000` (admin / `$GRAFANA_ADMIN_PASSWORD`, default `admin`), Prometheus at
  `http://localhost:9090`, Alertmanager at `http://localhost:9093` (loopback-only, like resql/registry —
  `compose.override.yml`). Config lives in `docker/prometheus/`, `docker/grafana/`,
  `docker/alertmanager/`. Only `edelivery`/`xml-mapper` expose `/metrics` today; see
  `docs/architecture/observability/monitoring_and_alerting.md` for what's covered and what isn't
  (notably: no restart-loop/DB/disk alerts — no cAdvisor/postgres_exporter/node_exporter yet — and no
  real Alertmanager notification receiver).

## Testing

- `tests/*/*.http` — IntelliJ HTTP Client format; run all with `docker compose run --rm http-tests`
  - `TEST_FILES=tests/admin/gates.http` can be prefixed to run only specific tests
  - `tests/admin/{gates,platforms,authorities}.http` are **read-only** registry tests (ADR-011 §6): they
    assert the `registry/**` fixtures through the admin GET surface and assert that a write method on
    the registry paths is rejected with **405** (the path is still routed — for GET — so Ruuter answers
    "method not allowed" and never runs a guard, meaning no 401/403). Anything they need must be a
    committed registry file, not a runtime create.
  - `tests/authority/*.http` likewise read their authority prerequisites from `registry/authorities/`.
    There is no `DELETED`/tombstone authority fixture any more: an absent authority and a revoked one are
    the same thing, so the "must not authenticate" case is covered by an unknown `registryCode`.
- In these files, every new request starts with ### 
- Env file: `tests/http-client.env.json` (local/docker environments)
- Assertions: `> {% client.test("name", () => { client.assert(...) }) %}`
- Health check: `GET /efti/api/v1/test/baasikontoroll` (public, returns DB status)
- `DSL-tests/**/*.test.yml` — Ruuter `dsl-test` scenarios (`mode: inprocess` — HTTP through the
  in-process router, no compose). Use for anything reachable **before an upstream `call:`**:
  guard rejects, `validate_input` 400s. `mode: mock-http` can stand in for ReSql/xml-mapper.
  Run via `docker run --rm -v "$PWD:/workdir" -w /workdir turnerrainer/ruuter:0.12.1-rc dsl-test --dsl DSL/Ruuter --tests DSL-tests --constants constants.ini` (the binaries ship in the runtime image since 0.9.14-rc).
  A scenario `request` with a `body` must also send `content-type: "application/json"` in `headers`:
  without it the body is not parsed, every `incoming.body.*` reads `undefined`, and the scenario still
  passes while exercising nothing.
- `DSL-turvis-mock-tests/turvis.test.yml` (84+ `dsl-test` scenarios, `constants-turvis-mock.ini`) covers the Turvis mock (`DSL/Ruuter-turvis-mock`, issue #306): per route happy path, projection by **exact body** (`X-Mock-Audit-Ref` fixes `audit_ref`), every SPEC §5 reason code via `X-Mock-Verdict`, content types (`application/problem+json` on rejects), plaintext/encrypted profile, deterministic routing across decrypt and verify, receipt metadata incl. `cid:message`. The mock has **no real cryptography** (an accept proves nothing about a real message; no finding is closed by it) but enforces what the real Turvis would: `return_fields` projection and `UNKNOWN_RETURN_FIELD`, `key_handle` = `OWN_GATE_ID`, routing/sender extracted from the envelope, signed-subtree policy when `<ds:Reference>` elements exist. `scripts/validate-turvis-contract.py` (+ `tests/turvis/test_validate_turvis_contract.py`) fails CI when SPEC §4/§5 and the DSL drift. Ruuter engine gotchas found here: a `content_type:` key on a return step is **ignored** (use `headers:`), values assigned in one `assign:` block do not see each other, and an odd number of `"` inside a `${...}` expression leaves it unevaluated as a literal string. Compose: loopback `127.0.0.1:${EFTI_TURVIS_MOCK_PORT:-8091}`; the mock is not built by `.gitlab-ci.yml` and has no devops chart, and nothing depends on it until #305.
- `DSL-mock-tests/*.test.yml` verifies standalone mock UUID and dataset permission contracts against `DSL/Ruuter-xroad-mock` and `constants-xroad-mock.ini`. Rich synthetic dataset UIL is `EU-EE/mock/550e8400-e29b-41d4-a716-446655440002` (every FTI010 element, meaningful distinct values from an enrichment pass over the XSD walk; two items and two carried containers show repeats; field catalogue `docs/developer/fti010-field-catalogue.md`, written by `scripts/sync-mock-datasets.py`); `...0011`–`...0013` are the `MOCK-PLATE-3` shipments with meaningful values; other UILs get the minimal `MOCK-PLATE-1` response. XML is not subset-filtered.
- **The developer mock and the live gate share one contract (1:1 means request/response shape, not data):** both answer
  `dataset` as `{uil, subsets, xml, consignment}`: the live `xroad/POST/v1/dataset.yml` adds `uil`/`subsets`
  to core's `{xml, consignment}` (xml-mapper `/api/v1/dataset/response-to-json`). The mock's dataset route
  and the live `mock-platform/GET/v1/dataset.yml` are **generated** — never hand-edit them; change
  `scripts/generate-rich-mock-dataset.py` and run `python3 scripts/sync-mock-datasets.py` (`--check` only
  verifies). The mock's `xml`/`consignment` are precomputed with xml-mapper's rules (`served_xml`,
  `consignment` in the generator: root loses `rsm:`; keys lower-camel with every case-insensitive `id` →
  `Id`, e.g. `wIdthMeasure`; attributes as `name@attr`; repeats → arrays). The mock's canned data is **not**
  seeded into the gate's database: the developer mock has no database, and the gate need not find what
  the mock returns. In the full-field `...0002` fixture every free-text/number/identifier leaf needs an
  explicit value rule (`RULES` in the generator — a missing or unused rule fails generation), so no
  `MOCK-<Element>` placeholder can return; deliberately fictional ids are listed in `MOCK_IDENTIFIERS`.
  `python3 tests/mock/dataset.py` checks all of this plus the FTI010 XSD and that the field catalogue is
  current (requires PyYAML and xmllint); with `MOCK_TEST_URL` it also runs against a running developer mock.
- **`search` shares the contract too.** Both answer the curated transport-means projection (`uil`
  nested, no `xml`) with the same `x-poll-more` semantics. The live `xroad/POST/v1/search.yml` passes
  core's answer (raw `get_consignments` rows on a local hit, xml-mapper `ConsignmentRow`s on a poll)
  through xml-mapper `/api/v1/transport-means/normalize`; core's `efti/api/v1/authority/search` itself
  still returns raw rows (transport-means `allgates` normalizes them the same way). The mock's
  `developer/POST/v1/search.yml` filters its four canned rows with `get_consignments.sql`'s criteria
  semantics (AND, EQ/NE, date ranges, `transportMeansOrEquipmentId`) in its order (`created_at DESC,
  revision DESC`); a miss answers `[]` + `x-poll-more: true`, a poll `[]` + `false`.
- `python3 scripts/registry-to-json.py` (PyYAML + `cryptography`) validates `registry/**/*.yml` and builds
  the Turvis trust view — the same converter the `registry` and `turvis-trust` image builds run, only without
  `--out`/`--trust-out` (nothing is written). Both CI jobs run it (they `pip install pyyaml cryptography`), so a
  malformed registry fails CI without a Docker build (ADR-011 §3). Its rules, including the trust-view ones
  (allowlist, no credential/URL, deterministic `generation`, expired/not-yet-valid/unparseable certificate, missing anchor
  alias, `.pem` only under `trust-anchors/`), are unit-tested by `python3 tests/registry/test_registry_to_json.py`.
- `python3 tests/sql/regression.py` prepares all ReSQL queries under `app` and checks append-only, credential and search semantics in its own disposable PostgreSQL 18 container (no host ports, no persistent volume); the registry is no longer part of it (ADR-011 §4 — nothing about the registries is in SQL any more). Both DSL CI jobs run it. `--performance` additionally compares custom/generic plans with origin/dev on 100,000 synthetic records after VACUUM/ANALYZE.

## Branching

- When creating a new branch, its name must describe what the branch sets out to achieve — the
  change or outcome intended, not a ticket id or random slug. Prefer `type/short-goal`, e.g.
  `fix/xroad-dataset-forward-missing-post-handler` or `feat/authority-audit-log`.
- Set a one-line branch description spelling out the goal:
  `git branch --edit-description` (or `git config branch.<name>.description "<goal>"`).

## CI/CD

- `.github/workflows/issue-prerequisites.yml` publishes the `Issue prerequisites` commit status from trusted default-branch code. PR `Depends-On: #N` declarations require issues closed as completed; `legacy-removal` or closing #312 additionally requires #313. Issue events and scheduled/manual refresh revoke stale results. Administrator activation as a required branch check is separate; see `docs/developer/issue-prerequisites.md`. Unit tests run via `node --test .github/scripts/issue-prerequisites.test.cjs` and the separate read-only test workflow.

- `.github/workflows/e2e.yml` — GitHub Actions:
  - `dsl-validate` — runs Ruuter's `dsl-lint --audit` + `dsl-test` straight from the runtime image
    (`turnerrainer/ruuter:0.12.1-rc`, which ships both binaries since #83) on both DSL roots +
    `DSL-tests/*.test.yml` (in-process scenarios) + `scripts/validate-dsl.py` (the efti-only
    input-contract convention) + `scripts/registry-to-json.py` (validates `registry/**/*.yml` without
    building an image — the converter is the validator, ADR-011 §3) + `tests/registry/test_registry_to_json.py`
    + the Turvis mock (`dsl-lint --audit` and `dsl-test` on `DSL/Ruuter-turvis-mock` /
    `DSL-turvis-mock-tests`, plus `scripts/validate-turvis-contract.py` and its unit test).
  - `e2e` — builds the compose stack, runs the `tests/*/*.http` smoke suite via
    `docker compose run --rm http-tests`. This is the gate on PRs. `dsl-test` is the fast
    in-process check; `tests/*.http` is the full-stack integration gate.
  - `backend` — `code/`'s `./gradlew test` (blocking, mirrors `.gitlab-ci.yml`'s `backend:test`)
    + `./gradlew jacocoTestCoverageVerification` (80% per-module line threshold,
    `continue-on-error: true`, mirrors `backend:coverage-gate` — reports, doesn't gate PRs yet
    while edelivery is still under 80%; `core` cleared it). JUnit + jacoco
    reports uploaded as an artifact.
  - `frontend` — `code/ui`'s `npm run check` (svelte-check) + `npm run test:coverage` (vitest,
    80% threshold, `code/ui/vite.config.js`), coverage report uploaded as an artifact. Mirrors
    `.gitlab-ci.yml`'s `frontend:check`/`frontend:test`. `continue-on-error: true` on both steps —
    same temporary onboarding state as the GitLab jobs: reports failures, doesn't gate PRs yet
    while per-module coverage baselines are still being raised (see
    `docker/ui/Dockerfile`, which only runs `npm run build`, never tests).
- `.gitlab-ci.yml` — kemitaws platform pipeline (mirror): `secret_detection` + `validate:dsl`
  (same `dsl-lint --audit` / `dsl-test` / `validate-dsl.py` as above) + sonar → eleven
  `image-build`s (ruuter, ruuter-xroad-mock, resql, liquibase, tim, ui, edelivery, xml-mapper,
  registry, turvis-trust, cronmanager — turvis-trust (no consumer yet) and cronmanager are build-only until
  their devops charts exist, so they are not in
  `components:`) → SBOM/trivy → `package:charts` trigger into the `efti` devops repo →
  `release-pin` into `environments/dev/release.yaml`. Runs on the default branch and `release/*`.
  The latest chart versions (kemitchart base chart, the `dev` release pin) are recorded, dated, in
  `docs/specs/deploy/README.md` "Helm chart versions" — refresh it when they change.
  Header comment lists the CI/CD variables and the values still to confirm against the devops repo.
  **`registry` needs a matching wrapper chart component in `services/efti/devops`** — the
  `components:` list is mirrored from there, and the chart must **not** mount `registry/` (there is no
  runtime mount since ADR-011 §3; the registry is baked into the image at build time, which is also where
  it is validated) and must not expose the service port. A deployment with its own gates, platforms and
  authorities replaces `registry/` in the build context **before** the image build, because the image
  built from this repo serves the dev fixtures. Without the component the deployment never serves a
  registry at all, so every guard denies and the stack is effectively dead — this is a required
  cross-repo change, not an optional one. Likewise the **`edelivery` component must mount the AS4
  keystore Secret** at `/app/certs/own.p12`: the image no longer carries `code/certs` (procedure:
  `docs/specs/deploy/resource-and-secrets-requirements.md` §3.5).

## Post-change

- If anything listed in `AGENTS.md` changed - update the file
- Always run `git add` for new/changed files 

## Key gotchas

- `constants.ini` uses compose-internal URLs (`http://ruuter:8086`); `.env` uses localhost URLs
- `constants.ini` is **not** in the ruuter image: compose mounts it read-only at `/app/constants.ini`, and Ruuter has **no env substitution**, so it is read once at start — changing it needs `docker compose restart ruuter` (a `--watch` DSL sync does not reload it). It holds `TIM_ADMIN_TOKEN`, `INTERNAL_SERVICE_TOKEN` and `ARCHIVE_OPS_TOKEN`, so production mounts the whole file from a Secret (`docs/specs/deploy/resource-and-secrets-requirements.md` §3.2). `ruuter.yaml` *is* `COPY`-ed, so changing it needs `docker compose up --build ruuter`. (Before the `xroad` project was merged into the main Ruuter this also had to be kept in sync with a second `constants-xroad.ini`; that hazard is gone.)
- `X-Road-Id` must be hexadecimal 8-4-4-4-12 UUID text: both real and mock guards use a tested RegExp and return 400 `INVALID_REQUEST_ID` for non-hex values. The adapter maps it to typed downstream UUID parameters.
- Ruuter `http_codes_allow_list` must include any status you return (401, 403, 204 are not default)
- `internal_requests.block_private_networks: false` in `ruuter.yaml` — auth DSLs call TIM/ReSQL by compose service name
- edelivery test mode uses a hardcoded PKCS#12 keystore (see `KeyManager.kt`); otherwise the AS4 keystore is read from `$KEYSTORE_DIR/own.p12` (default `certs`, password `$KEYSTORE_PASSWORD`). It is **not** baked into any image: `code/certs` is in both `code/.dockerignore` (compose, `docker/code/Dockerfile`) and the repo-root `.dockerignore` (GitLab's `docker/edelivery|xml-mapper/Dockerfile`), and neither Dockerfile copies it. Compose mounts `./code/certs:/app/certs:ro` into `edelivery`; **the devops chart must mount the per-environment keystore Secret at `/app/certs/own.p12`** (plus `KEYSTORE_PASSWORD`) or `edelivery` fails to start. xml-mapper needs no keystore. How to generate it (alias = `OWN_GATE_ID`, key password = store password), where it goes (SSM → ExternalSecret → mount; public cert into `registry/gates/<OWN_GATE_ID>.yml` and to every peer operator) and how to rotate it: `docs/specs/deploy/resource-and-secrets-requirements.md` §3.5
- `PartyId` equality is case-insensitive (`.equals(ignoreCase = true)`)
- Caller-controlled values are never interpolated raw into an outbound `url:`. Percent-encode path segments / query values with `encodeURIComponent(...)` (`uil.datasetId`/`subsets`/`gateId`, `requestId`, admin get-by-id path params), so `..`, `/`, `?` or `&` cannot alter the target. The engine's Boa/QuickJS contexts are full ECMAScript, so the global is available even though it is not in the documented expression subset.
- user does not have role related fields. if user exist then they are admin. that's it.
