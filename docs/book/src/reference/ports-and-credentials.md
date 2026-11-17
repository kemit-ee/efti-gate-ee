# Ports and credentials

## Ports

Every port is bound to `127.0.0.1` only. A gate's ports are its `PORT_PREFIX` (from
`local/gates/<gate>.env`) followed by a fixed suffix: G1 has prefix `27`, G2 `28`.

| Service | Paths you call | Suffix | G1 | G2 | Inside the gate |
|---|---|---|---|---|---|
| ruuter | core `/efti/**`, platforms `/platforms/v1/**`, admin `/admin/v1/**`, login `/auth/**`, ops `/ops/v1/**`, mock platform `/mock-platform/v1/**`, AS4 edge `/services/msh` | `086` | 27086 | 28086 | `ruuter:8086` |
| ruuter-xroad | X-Road provider `/xroad/provide/**`, `/xroad/health/ready` | `087` | 27087 | 28087 | `ruuter-xroad:8087` |
| ruuter-xroad-mock | developer mock `/developer/**` | `088` | 27088 | 28088 | `ruuter-xroad-mock:8088` |
| edelivery | send API `/api/v1/send/{party}`, MSH `/services/msh`, `/health`, `/metrics` | `081` | 27081 | 28081 | `edelivery:8081`; on `efti-g2g`: `<GATE_SLUG>-edelivery` |
| xml-mapper | FTI XML ↔ JSON | `082` | 27082 | 28082 | `xml-mapper:8082` |
| resql | SQL endpoints `/efti/*` | `090` | 27090 | 28090 | `resql:8090` |
| registry | `/gates.json`, `/gates/<id>.json`, `/platforms…`, `/authorities…` (ids lower-case) | `080` | 27080 | 28080 | `registry:8080` |
| postgres | database `efti` | `032` | 27032 | 28032 | `database:5432` |
| test-scheduler | CronManager admin API (only with profile `test-schedule`) | `089` | 27089 | 28089 | `test-scheduler:8080` |
| tim, tim-database | login for the admin API; no host port (reached through ruuter) | – | – | – | `tim:8085` |

To change a gate's ports, change `PORT_PREFIX` in its env file, run `up -d --wait` again, and update
the base URLs in its Postman environment (`local/postman/<GATE_ID>.postman_environment.json`).

## Credentials

These are development values that are already public in the repository. **Never** use them outside
your machine.

| What | Value | Used by |
|---|---|---|
| Internal service token | `X-Internal-Service-Token: dev-internal-service-token-change-me` | gate-internal APIs: edelivery send, `/efti/api/v1/**` |
| Platform API key | `X-Api-Key: mock-secret-key` (platform `mock`; `MOCK_PLATFORM_API_KEY` in `constants.ini`, a dev value, not in any image) | `/platforms/v1/**`, `/mock-platform/v1/**` |
| X-Road client, EU01 | `X-Road-Client: DEV/GOV/70000099/testSubsystem` → `auth-xroad-test` | X-Road provider API |
| X-Road client, EU01 + EU02 | `X-Road-Client: DEV/GOV/70000093/ants` → `auth-xroad-tm` | X-Road provider API, transport means |
| Ops token | `Authorization: Bearer dev-archive-ops-token-change-me` | `/ops/v1/**` |
| ReSql token | `Authorization: Bearer dev-resql-internal-token-change-me` | resql `/efti/*` |
| Admin JWT | `POST /auth/dev-login` with `{"personalCode":"60001019906","firstName":"Super","lastName":"Admin"}` → `{"token":"eyJ…"}`; send `Authorization: Bearer <token>` (valid 60 min) | `/admin/v1/**`, `/auth/user` |
| Test scheduler | `Authorization: Bearer dev-test-scheduler-token` | the test-scheduler's CronManager admin API |
| PostgreSQL | user `efti`, password `01234`, database `efti` | `psql -h 127.0.0.1 -p 27032 -U efti efti` |
| Liquibase | `LIQUIBASE_COMMAND_URL` / `_USERNAME` / `_PASSWORD` (`efti` / `01234` in Compose); `liquibase.properties` holds none | the `liquibase` container |
| DB roles `app`, `db_archiver` | `NOLOGIN` group roles without a password | granted to the login users; nobody connects as them |
| AS4 keystores | `own.p12`, password `changeit`, alias = gate id | edelivery ([certificates](../getting-started/certificates.md)) |

The tokens Ruuter reads come from the repository's `constants.ini` and `constants-xroad.ini`; the
same values are the defaults in `local/compose.yaml`.

## Gates

| | G1 | G2 |
|---|---|---|
| Env file | `local/gates/g1.env` | `local/gates/g2.env` |
| Compose project | `efti-g1` | `efti-g2` |
| Containers, volumes | `efti-g1-*`, `efti-g1_*` | `efti-g2-*`, `efti-g2_*` |
| Port prefix | `27` | `28` |
| Keystore folder | `local/certs/g1/` | `local/certs/g2/` |
| Registry entry | `local/network/G1.yml` | `local/network/G2.yml` |
| Seed changelog | `test/db.changelog-local-g1.yaml` | `test/db.changelog-local-g2.yaml` |
| Postman environment | `local/postman/G1.postman_environment.json` | `local/postman/G2.postman_environment.json` |
| Alias on `efti-g2g` | `g1-edelivery` | `g2-edelivery` |
