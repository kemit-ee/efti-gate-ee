# eFTI Gate v2 — Non-functional contracts

Consolidated SLOs, SLIs, availability targets, capacity assumptions, and pinned dependencies. Without this table any vendor bid is guessing; the numbers are intentionally specific.

> **Status:** First-pass numbers. Some are inherited from the PoC performance report (Hetzner CPX31, 8 vCPU / 16 GB / single instance, Test Fest 3); others are operator targets for the v2 production deployment. Re-validate at first staging load test; revise as Test Fest 4+ data arrives.

## 1. Service-level objectives (per surface)

| Surface | Operation | SLI | Target | Window | Error budget |
|---|---|---|---|---|---|
| Platform API | `POST /v1/identifiers/{datasetId}` | p95 latency | < 200 ms | 30 d rolling | 0.1 % over |
| Platform API | `POST /v1/identifiers/{datasetId}` | success rate (2xx + 4xx-client) | ≥ 99.9 % | 30 d | 43 min/month |
| Authority API | `GET /v1/identifiers/{identifier}` (local hit) | p95 latency | < 50 ms | 30 d | 0.1 % over |
| Authority API | `GET /v1/identifiers/{identifier}` (broadcast) | p95 latency | < 8 s | 30 d | 1 % over |
| Authority API | `GET /v1/dataset/{gateId}/{platformId}/{datasetId}` (local) | p95 latency | < 5 s | 30 d | 1 % over |
| Authority API | `GET /v1/dataset/...` (remote AS4) | p95 latency | < 30 s | 30 d | 5 % over |
| Authority API | `POST /v1/follow-up/...` | p95 latency | < 2 s | 30 d | 1 % over |
| Admin API | All endpoints (cumulative) | p95 latency | < 500 ms | 30 d | — |
| G2G AS4 inbound | `POST /services/msh` | p95 latency | < 10 s | 30 d | — |
| G2G fast (mTLS) | `POST /services/fast` | p95 latency | < 1 s | 30 d | — |
| Health | `/health/ready` | response time | < 100 ms | always | — |

**Aggregate availability target:** 99.9 % (≈ 8h 45m downtime / year). Below this the EU regulatory clock starts ticking — Reg 2020/1056 fully applies from 2027-07-09 and member states must operate gates without sustained outage.

## 2. Capacity model

Steady-state estimates for a single national gate handling Estonia's outbound freight volume. Two reference points framed the original numbers:

- **Estonia-only baseline** — Estonian Statistical Office road freight (~30 M tonnes/year cross-border road; rough divisor ~6 M consignments/year ⇒ ~0.2 reg/sec steady-state, ~0.8 reg/sec peak).
- **EU-wide-traffic-passing-through scenario** — eFTI Sounding Board projection assuming Estonia's gate also brokers transit traffic crossing through the country. ~63 M consignments/year ⇒ ~2 reg/sec steady-state, ~8 reg/sec peak.

The numbers below adopt the **EU-wide-passthrough scenario** because (a) it dimensions safely for Test Fest 4+ load and (b) the gate process is the same regardless of caller volume — over-provisioning at SLO-design time is cheap. Operators handling only Estonian-origin traffic can divide every per-second / per-day row in this table by 10 to get their actual load.

| Dimension | Steady state (EU-passthrough) | Peak (4× steady) | Notes |
|---|---|---|---|
| Identifier registrations | 2 / sec | 8 / sec | Platform-driven; each = 1 INSERT into `consignments` + N INSERTs into `identifiers`. |
| Authority searches | 0.3 / sec | 1.2 / sec | Includes both local-only and broadcast paths. |
| Dataset retrievals | 0.05 / sec | 0.2 / sec | Each forwards to a platform/peer-gate. |
| Follow-up messages | 0.01 / sec | 0.04 / sec | |
| G2G AS4 inbound | 0.5 / sec | 2 / sec | EU-wide aggregate from peer gates. |
| DB row growth (`consignments`) | ~250 K / day | — | First-INSERT at 2 reg/sec × 86 400 s ≈ 170 K, plus ~80 K/day of state-transition rows (expiration → `inactive`, re-uploads, status flips). Append-only, so each transition is its own row. |
| DB row growth (`identifiers`) | ~300 K / day | — | ~1.5 identifiers per consignment on average; both initial registration and re-upload INSERT new rows. |
| DB row growth (`gates`) | ~290 rows/day **per gate** | — | Ping cadence is one INSERT every 5 min ⇒ 288 rows/day per gate. Across all peer gates whose pings this gate stores in its local registry copy, the total is `peer_gate_count × 288`. For ~30 EU gates that is ~8 700 rows/day in this table; if peer-ping rows are not replicated locally, only the ~290 self-ping rows remain. |
| DB row growth (`sessions`) | ~5 K / day | — | Append-only: one INSERT on login, one INSERT on logout / token revocation. |
| DB row growth (`async_responses` + `request_id_cache`) | ~50 K / day combined | — | Receive INSERT + consume INSERT per async response; correlation-id cache entries (TTL 24 h, then archived). |
| DB row growth (`audit_log`) | ~30 K / day | — | One row per Authority action + admin mutation; **not archived** (retained on the live DB for ≥ 7 years per §5; operator may extend indefinitely). |
| Live DB size after 3 y | ~80 GB | — | Live DB stays **bounded** because CronManager (Epic 26) sweeps non-latest rows of every operational table nightly into archival storage. The figure assumes the sweep keeps up with steady-state growth; if archival is paused, the live DB grows at ~150 GB/year. |
| Cold archive size after 3 y | ~500 GB | — | Monotonically growing JSON-Lines on the archival destination (S3-compatible store, secondary Postgres, or append-only file system). 7-year minimum retention for auditable tables. |
| JVM heap | 1 GB | — | `-Xmx1g`; alarm at 80 % per logging-spec.md §2.3. |
| Connection pool | 10 | — | HikariCP default; alarm when < 2 available. |

## 3. Deployment topology assumptions

- **Two nodes minimum** in production (active/active behind a Layer-7 load balancer). One node alone leaves zero error budget for rolling upgrade or single-host failure.
- **One PostgreSQL primary** plus a streaming-replica standby for DR. PostgreSQL 14+; same major version on primary and standby.
- **`pg_notify`** for in-cluster registry sync (gate-list refresh on Admin write). The application emits `pg_notify('registry_change', '<gate-id>')` from the same transaction that INSERTs the new `gates` row — no DB-side trigger; trigger-driven NOTIFY would not commit until the surrounding transaction commits anyway, so app-level emission is functionally equivalent and avoids a hidden side-effect at the schema layer. Other nodes LISTEN on `registry_change` and reload the affected row from `gates` on receipt. Documented in `arch-01-multi-node-deployment.mmd` and `seq-15-gate-registry-sync.mmd`.
- **Reverse proxy** (e.g. Caddy / Traefik / nginx) terminates TLS; gate processes do not handle TLS directly.
- **eDelivery AS4 access point**: the gate currently embeds its own AS4 implementation (Askend baseline). Domibus is an alternative for member states that already operate one — both are supported by the protocol; the choice is operator-level.
- **[CronManager](https://github.com/Buerostack/CronManager)** is a strict requirement, not optional. Deployed as a sibling container/Pod alongside the gate, with its own Postgres for Quartz state. CronManager owns every scheduled task — including the **append-only archival sweep** (Epic 26) that moves non-latest rows of every operational table to archival storage on a configurable cron schedule. The gate's runtime never schedules its own jobs; it only exposes the admin endpoints that CronManager calls. See `docs/specs/deploy/cronmanager-archive.yaml` for the canonical job definition.
- **Archival destination** (separate from live PostgreSQL) is operator-configurable but must satisfy environment parity: same software in dev / test / stage / prod. Acceptable: an S3-compatible object store (real S3 in prod; MinIO/LocalStack in dev as long as the wire protocol is the same), a secondary PostgreSQL on a different cluster, or an append-only file storage. JSON-Lines partitioned by `(table, year, month)`; 7-year minimum retention.

## 4. Pinned dependency versions

The reference implementation will pin these exactly; alternative implementations should match the *behaviour* even if the libraries differ.

| Component | Version | Notes |
|---|---|---|
| JVM | Eclipse Temurin 21 LTS | Virtual threads (Project Loom) used by the Klite HTTP server. |
| Build | Gradle 8.x with Kotlin DSL | |
| Kotlin | 2.0+ | |
| HTTP framework | Klite ≥ 1.6 | Lightweight; uses JVM built-in HTTP server. No Tomcat/Netty/Ktor. |
| PostgreSQL | 14+ (tested 14.10 / 15.5 / 16.1) | Required extensions: `uuid-ossp`, `citext`, `pg_trgm`, `btree_gin`. |
| JDBC pool | HikariCP via `klite-jdbc` | |
| XML | JAXB (`jakarta.xml.bind` 4.x) | Used for both eFTI consignment XML and AS4 SOAP envelopes. |
| Cryptography | JCA (AES-GCM, RSA-OAEP) | For eDelivery message encryption. |
| **JWT validation** | JJWT (`io.jsonwebtoken:jjwt-jackson` ≥ 0.12) **or** Nimbus JOSE+JWT | RS256, JWKS via `taraJwt` discovery. |
| **Bcrypt** | `at.favre.lib:bcrypt` (or equivalent) | Break-glass local-admin password only. |
| Logging | Logback + `net.logstash.logback:logstash-logback-encoder:7.4` | Per logging-spec.md Appendix B. |
| Schema migrations | Liquibase | Per `schema.sql` migration-policy header note. |
| AS4 implementation | Custom (Askend baseline) **or** Domibus | Operator's choice; both protocol-compatible. |
| UI (optional) | Svelte 4 (no runes) | Admin/authority H2M UIs; out of scope for the core gate. |

### 4.1 Required environment variables

| Variable | Purpose | Default |
|---|---|---|
| `TARA_OIDC_DISCOVERY_URL` | Where the gate fetches JWKS, `iss`, supported algorithms. | `https://tara.ria.ee/.well-known/openid-configuration` (test issuer for non-prod) |
| `TARA_CLIENT_ID` | The gate's TARA `aud` claim. | required, no default |
| `TARA_CLIENT_SECRET` | Required only for the OIDC code-exchange flow consumed by the admin/authority browser UI. The gate's REST API itself only validates JWTs; it does not exchange codes. | optional |
| `TARA_JWKS_CACHE_SECONDS` | TTL for the JWKS cache. | 3600 |
| `ARCHIVE_OPS_TOKEN` | The static Bearer secret accepted on `/api/v1/admin/archive`, `/expire-identifiers`, `/ping-gates`. 256-bit random; provisioned via Kubernetes Secret. | required, no default |
| `LOCAL_ADMIN_FALLBACK_ENABLED` | If `true`, `POST /api/v1/auth/local-token` returns 200 with a gate-signed JWT instead of 503. | `false` |
| `BREAK_GLASS_JWT_SIGNING_KEY` | PEM-encoded RSA private key the gate uses to sign break-glass JWTs (only consulted when `LOCAL_ADMIN_FALLBACK_ENABLED=true`). | optional |
| `BREAK_GLASS_JWT_TTL_SECONDS` | Hardcoded ceiling 600. Operator may shorten further. | 600 |
| `MTLS_HEADER_SUBJECT` / `MTLS_HEADER_SERIAL` | Which trusted-proxy headers carry the platform cert subject DN and serial. | `X-Client-Cert-Subject` / `X-Client-Cert-Serial` |
| `GATE_ID` / `COUNTRY_CODE` | Identity of this gate (`iss` for break-glass JWTs and `Config.gateId` for follow-up validation). | required, no default |
| `EU_PLATFORM_REGISTRY_URL` / `EU_PLATFORM_REGISTRY_REFRESH_MINUTES` | EU central registry of certified platforms (Reg 2020/1056 Art 7+12). | required, no default / 60 |

There is **no** `JWT_EXPIRY_SECONDS` env var on the primary auth path — TARA owns expiry policy. The earlier draft of the spec referenced these variables; they are removed. The break-glass path's TTL is hardcoded to 600 s and operator-shortenable via `BREAK_GLASS_JWT_TTL_SECONDS`.

## 5. Compliance targets

| Topic | Requirement | Anchor |
|---|---|---|
| Audit retention | **Minimum 7 years on the live DB** for `audit_log` (operator may extend indefinitely — `db_archiver` has no DELETE on this table); 7 years on the live DB **plus archive** for `follow_up_log` and every other operational table. | GDPR Art 30; Reg 2024/1942 Art 6 |
| Cabotage retention | Road consignments held for 14 days post-transport_date in `inactive` status | Reg 2024/1942 Art 11(4) |
| Personal-data redaction | `users.secret_hash`, `Authorization` headers, partial vehicle plates in audit contexts | logging-spec.md §6 |
| Cross-border interoperability | Any EU eFTI gate may query any other gate over AS4 | Reg 2020/1056 |
| EU subset codes | `EU01..EU07` (no other vocabulary) | Reg 2024/2024 |

## 6. What this document does NOT yet specify

These are the explicit Phase-2 gaps a vendor will need to design alongside KeMIT:

- **Threat model** — STRIDE table per surface; out of scope for this spec corpus (operator-supplied; informed by `permissions-matrix.md` and Reg 2025/2243).
- **Helm chart / Kubernetes manifests / docker-compose** — the deployment artefacts themselves; this doc describes the topology shape, not the YAML. See `docs/specs/deploy/README.md`.
- **On-call runbook** — alert thresholds, escalation tree, recovery procedures. Owner: ops team.
- **Specific load-test plan** — k6/JMeter scenarios that validate the SLO targets above.
- **Capacity-plan revisions** — re-derive the §2 numbers from each Test Fest after Test Fest 3.
