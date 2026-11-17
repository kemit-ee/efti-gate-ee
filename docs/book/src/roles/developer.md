# Gate developer

> **Goal:** you can run the gate and its tests, find where a behaviour lives, change it in the
> repository's idiom, and keep the checks green.

## Path

1. **Run it.** [Prerequisites](../getting-started/prerequisites.md), then
   [Run two gates locally](../getting-started/run-locally.md), [sample data](../getting-started/sample-data.md)
   and [the Postman checks](../getting-started/newman.md).
2. **See the design.** [Technical architecture](../architecture/index.md), starting with the
   [Reference architecture](../architecture/reference-architecture.md) and the
   [decision records](../architecture/decisions/index.md).
3. **Find the code for an endpoint.** [Endpoint map](../api/index.md) lists every route with its
   source file. [Gate internals](../api/internal/index.md) covers the routes only other components
   call.
4. **Read the conventions.** [`AGENTS.md`](https://github.com/kemit-ee/efti-gate-ee/blob/dev/AGENTS.md)
   at the repository root is the working agreement for DSL, SQL, Kotlin and tests.
5. **Check [Known issues](../reference/known-issues.md)** before you "fix" something that is
   documented behaviour.

## Where things live

| Layer | Technology | What it holds | Source |
|---|---|---|---|
| Ruuter | Rust DSL engine | HTTP routes as YAML. The path is the file path: `GET /admin/v1/gates` is `DSL/Ruuter/admin/GET/v1/gates.yml` | `DSL/Ruuter/`, `DSL/Ruuter-xroad/`, mocks |
| ReSql | Rust SQL executor | One SQL file is one `POST /efti/<name>` endpoint | `DSL/Resql/` |
| Kotlin services | klite on the JVM | `edelivery` (AS4), `xml-mapper` (XML to JSON), shared `core` | `code/` |
| Database | PostgreSQL 18 | Append-only tables, migrations by Liquibase | `DSL/Liquibase/` |
| Registry | YAML to JSON to nginx | Gates, platforms, authorities | `registry/`, `scripts/registry-to-json.py` |
| UI | Svelte, Vite | Admin UI | `code/ui` |
| Local network | Docker Compose, one env file per gate | Two or more gates talking AS4 | `local/` |

Each top-level directory under a DSL root is its own Ruuter project with its own guards, in the
main Ruuter (`auth`, `admin`, `efti`, `platforms`, `mock-platform`, `ops`) or in the separate
`ruuter-xroad` (the X-Road surface under `/xroad/`).

## Rules that surprise newcomers

- **Append-only.** Operational tables are `INSERT`-only; an "update" inserts a new row with the same
  logical id and the latest row wins. The application role has no `UPDATE` or `DELETE`. Archival moves
  superseded rows to a separate database through Ruuter and ReSql.
- **No cross-table joins on the hot path.** Search columns are denormalised onto `consignments`.
- **The registries are files, not tables.** There is no write route for gates, platforms or
  authorities. A change is a YAML edit and a `registry-build`.
- **The `/efti/api/v1/**` surface is gate-internal.** It requires `X-Internal-Service-Token`. Humans
  and partners use `/admin`, `/auth`, `/platforms` or the X-Road routes.
- **Every route declares its inputs** (`declaration`, `validate_input`) and the audit tooling treats a
  missing declaration as an error.
- **Do not log message bodies or personal data.** A checker fails the build on it.
- **Do not edit applied Liquibase changesets.** Add a new one.
- **Generated files are not edited by hand**, for example the X-Road OpenAPI route (edit
  `docs/developer/x_road_openapi.yaml`, then run its sync script).

## Test and check

Commands, from the repository root unless noted:

```bash
docker compose up --build -d                  # the full stack with E2E tests
docker compose run --rm http-tests            # IntelliJ HTTP Client E2E, tests/http/*.http
cd code && ./gradlew test                     # Kotlin unit tests
cd code && ./gradlew edelivery:test           # one subproject
cd local && docker compose --env-file gates/g1.env run --rm newman    # two-gate Postman run
python3 scripts/validate-dsl.py               # DSL contract checks
python3 scripts/registry-to-json.py           # validate the registry (add --out to write it)
```

The CI pipeline (`.github/workflows/e2e.yml`) also runs Ruuter's `dsl-lint --audit` and `dsl-test`
on every DSL root, the SQL regression (`tests/sql/regression.py`), the registry and PII-logging
checks, and the Turvis contract and XML-surface validators. Match them locally before you open a PR.

## Change one thing: the usual routes

| You want to | Start at |
|---|---|
| Add or change an HTTP route | The route's YAML file; update its `declaration`; add a case in `tests/http/` and, for error paths, `DSL-tests/` |
| Change what is stored | A new Liquibase changeset, and keep `DSL/Liquibase/init.sql` in step |
| Change a peer-facing AS4 behaviour | `code/edelivery`, then the [AS4 pages](../api/gates/index.md) |
| Change the X-Road contract | `docs/developer/x_road_openapi.yaml`, then regenerate; update [the authority pages](../api/authorities/index.md) |
| Add a registry field | `registry/README.md`, `scripts/registry-to-json.py` and its tests, then the consumers |
| Change a documented behaviour | **This book**, in the same change: every example here was run on the local stack |

## Updating this book

- Every example is real output from the two-gate network. When you change a route, re-run the
  example and replace the block. [The Postman collection](../reference/collection.md) lists its
  requests; keep it and the JSON in step.
- Build locally: `mdbook build docs/book` (with `mdbook-mermaid` installed). The CI workflow
  `docs-book.yml` builds it on every pull request and publishes on merge.
- Where the code and an older document disagree, the book describes the code and says so. Record
  genuine defects in [Known issues](../reference/known-issues.md).
