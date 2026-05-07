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

Steady-state estimates for a single national gate handling Estonia's freight volume. Derived from EU Statistical Office road-freight figures (~30 M tonnes/year cross-border road, ~6 M consignments/year as a rough divisor) and the EU eFTI Sounding Board's projected member-state load.

| Dimension | Steady state | Peak (4× steady, e.g. month-end) | Notes |
|---|---|---|---|
| Identifier registrations | 2 / sec | 8 / sec | Platform-driven; each = 1 INSERT into `consignments` + N inserts into `identifiers`. |
| Authority searches | 0.3 / sec | 1.2 / sec | Includes both local-only and broadcast paths. |
| Dataset retrievals | 0.05 / sec | 0.2 / sec | Each forwards to a platform/peer-gate. |
| Follow-up messages | 0.01 / sec | 0.04 / sec | |
| G2G AS4 inbound | 0.5 / sec | 2 / sec | EU-wide aggregate from peer gates. |
| DB row growth (`consignments`) | ~170 K / day | — | At 2 reg/sec × 86 400 s. ~62 M rows/year before status='inactive' aging. |
| DB row growth (`change_history`) | ~10 K / day | — | One row per UPDATE on registry tables (mostly gates pings, status flips). |
| DB row growth (`audit_log`) | ~30 K / day | — | One row per Authority action + admin mutation. |
| DB total size after 3 y | ~50 GB | — | With partitioning / retention; without, ~200 GB. |
| JVM heap | 1 GB | — | `-Xmx1g`; alarm at 80 % per logging-spec.md §2.3. |
| Connection pool | 10 | — | HikariCP default; alarm when < 2 available. |

## 3. Deployment topology assumptions

- **Two nodes minimum** in production (active/active behind a Layer-7 load balancer). One node alone leaves zero error budget for rolling upgrade or single-host failure.
- **One PostgreSQL primary** plus a streaming-replica standby for DR. PostgreSQL 14+; same major version on primary and standby.
- **`pg_notify`** for in-cluster registry sync (gate-list refresh on Admin write). Documented in `arch-01-multi-node-deployment.mmd`.
- **Reverse proxy** (e.g. Caddy / Traefik / nginx) terminates TLS; gate processes do not handle TLS directly.
- **eDelivery AS4 access point**: the gate currently embeds its own AS4 implementation (Askend baseline). Domibus is an alternative for member states that already operate one — both are supported by the protocol; the choice is operator-level.

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
| Logging | Logback + `net.logstash.logback:logstash-logback-encoder:7.4` | Per logging-spec.md Appendix B. |
| Schema migrations | Liquibase | Per `schema.sql` migration-policy header note. |
| AS4 implementation | Custom (Askend baseline) **or** Domibus | Operator's choice; both protocol-compatible. |
| UI (optional) | Svelte 4 (no runes) | Admin/authority H2M UIs; out of scope for the core gate. |

## 5. Compliance targets

| Topic | Requirement | Anchor |
|---|---|---|
| Audit retention | 7 years for `audit_log`, `change_history`, `follow_up_log` | GDPR Art 30; Reg 2024/1942 Art 6 |
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
