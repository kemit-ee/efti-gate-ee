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

**Definition of "success" across all SLIs.** A request counts as a **success** when the gate returned a 2xx OR a client-side 4xx (the gate behaved correctly; the caller's input was rejected as designed). Requests that returned a 5xx, timed out, or surfaced as 502/504 (upstream platform/peer-gate failure) count as **failures**. This `2xx + 4xx-client` rule applies to every "success rate" SLO above, not only the one row that names it.

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
| DB row growth (`async_responses` + `request_id_cache`) | ~50 K / day combined | — | Receive INSERT + consume INSERT per async response; correlation-id cache entries (TTL 10 min per `schema.sql`, then archived). |
| DB row growth (`audit_log`) | ~30 K / day | — | One row per Authority action + admin mutation; **not archived** (retained on the live DB for ≥ 7 years per §5; operator may extend indefinitely). |
| Live DB size after 3 y | ~80 GB | — | Live DB stays **bounded** because CronManager (Epic 26) sweeps non-latest rows of every operational table nightly into archival storage. The figure assumes the sweep keeps up with steady-state growth; if archival is paused, the live DB grows at ~150 GB/year. |
| Cold archive size after 3 y | ~500 GB | — | Monotonically growing JSON-Lines on the archival destination (S3-compatible store, secondary Postgres, or append-only file system). 7-year minimum retention for auditable tables. |
| Process heap | 1 GB | — | Alarm at 80 % per logging-spec.md §2.3. Configure via the runtime's idiomatic heap-ceiling setting. |
| DB connection pool | 10 | — | Alarm when < 2 available. Pool implementation is the implementer's choice. |

## 3. Deployment topology assumptions

- **Two nodes minimum** in production (active/active behind a Layer-7 load balancer). One node alone leaves zero error budget for rolling upgrade or single-host failure.
- **Two pods minimum**, scheduled across at least two Kubernetes nodes (topology spread); a single-host failure must not kill all gate replicas. "Two nodes" in shorthand means "two pods on distinct hosts" — not two pods on the same host.
- **One PostgreSQL primary** plus a streaming-replica standby for DR. PostgreSQL 14–17 (see the version-floor table in §4 for why 18 is excluded); same major version on primary and standby.
- **Layer-7 load balancer** (operator's choice — nginx, HAProxy, AWS ALB, etc.) using **least-connections** distribution. Health check: `GET /health/ready`, 5 s interval, 2 consecutive failures = unhealthy, 1 success = healthy. **No sticky sessions** (the JWT is stateless).
- **LISTEN/NOTIFY** (PostgreSQL's built-in transactional pub/sub mechanism — writers publish with `NOTIFY channel, 'payload'`, subscribers receive after writer commit via `LISTEN channel`) for in-cluster registry sync. The application emits the `NOTIFY` from the same transaction that INSERTs the registry row — no DB-side trigger; subscribers receive it on transaction commit. **Channel naming**: `registry_change_gates`, `registry_change_platforms`, `registry_change_authorities` — one channel per registry table, payload is the affected logical id. Documented in `arch-01-multi-node-deployment.mmd` and `seq-15-gate-registry-sync.mmd`.
- **Reverse proxy** (e.g. Caddy / Traefik / nginx) terminates both TLS and inbound mTLS for the Platform API; the proxy validates the platform cert chain against the EU Trust Service trust list and forwards `X-Client-Cert-Subject` / `X-Client-Cert-Serial` headers. Gate processes do not handle TLS or mTLS directly. Trust-list refresh: every 24 h from `EU_TRUST_LIST_URL`; OCSP / CRL checks **fail closed** (cert is treated as invalid if the revocation lookup fails or times out — see §4.1 `OCSP_TIMEOUT_MS`, `CRL_REFRESH_HOURS`).
- **eDelivery AS4 access point**: protocol-compatible with the **EU eDelivery AS4 1.15 conformance profile** (4-corner topology; SOAP 1.2 over HTTPS; WS-Security 1.1 with XML Signature SHA-256 and XML Encryption AES-128-GCM; `eb:Service` URN-namespaced per [https://api.efti.ee/services/](https://api.efti.ee/services/); `eb:Action` literals: `identifierQuery`, `identifierResponse`, `uilQuery`, `uilResponse`, `postFollowUpRequest`, `followUpResponse`). The gate may use the embedded AS4 implementation (Askend baseline) or Domibus — operator's choice; both must satisfy the conformance profile.
- **Concurrency on CronManager admin endpoints**: PostgreSQL **advisory locks** (application-level locks keyed by a 64-bit integer, held in PostgreSQL's lock manager, independent of any table or row). Each admin endpoint acquires a distinct numeric lock id at handler entry; `409 Conflict` is returned on contention; the lock is released automatically when the handler's connection drops or the transaction ends. The advisory-lock approach is pinned because PostgreSQL is already mandatory and avoids adding Redis/ZooKeeper to the deployment.
- **[CronManager](https://github.com/Buerostack/CronManager)** is a strict requirement, not optional. Deployed as a sibling container/Pod alongside the gate, with its own Postgres for Quartz state. CronManager owns every scheduled task — including the **append-only archival sweep** (Epic 26) that moves non-latest rows of every operational table to archival storage on a configurable cron schedule. The gate's runtime never schedules its own jobs; it only exposes the admin endpoints that CronManager calls. See `docs/specs/deploy/cronmanager-archive.yaml` for the canonical job definition.
- **Archival destination** (separate from live PostgreSQL) is operator-configurable but must satisfy environment parity: same software in dev / test / stage / prod. Acceptable: an S3-compatible object store (real S3 in prod; MinIO/LocalStack in dev as long as the wire protocol is the same), a secondary PostgreSQL on a different cluster, or an append-only file storage. **Per-row JSON shape**: each archived line is one JSON object whose keys are the live-DB column names (snake_case) and values are the JSONB-natural mapping of the column type (TIMESTAMPTZ → ISO 8601 string, BYTEA → base64, JSONB → embedded object, all others → JSON primitive). The archive file naming pattern is `{table}/{year}/{month}/{batch_id}.jsonl`. **Retention floor**: 7 years; operator may extend.

### 3.1 Horizontal scaling (HPA)

The gate runtime is **stateless** — no in-memory request state, no sticky sessions, no node-local files, no in-process job scheduling. Horizontal Pod Autoscaler is **mandatory** and must be configured to scale instantly under load:

| Concern | Pinned contract | Default |
|---|---|---|
| Replica floor | `minReplicas` | **2** (matches §3 topology floor; satisfies the SLO error budget under rolling upgrade or single-host failure). |
| Replica ceiling | `maxReplicas` | operator-configurable per cluster capacity; **10** is the v2 default — covers §2 peak load (~12 req/sec across surfaces) with headroom for traffic spikes and rolling deploys. |
| Primary scale trigger | CPU utilisation | target **70 %** of `requests.cpu`. |
| Secondary scale trigger | Memory utilisation | target **75 %** of `requests.memory`. |
| Scale-up behaviour | aggressive — "instant" | `behavior.scaleUp.stabilizationWindowSeconds: 0`; `policies` allow **doubling** the replica count every **15 s** until the ceiling. The gate must keep up with demand spikes (e.g. EU-wide enforcement campaigns, post-incident peer-gate replay storms) without manual intervention. |
| Scale-down behaviour | gradual | `behavior.scaleDown.stabilizationWindowSeconds: 300`; remove **at most 1 replica per minute**. Prevents flapping under bursty load. |
| Readiness probe | shared with the L7 load balancer | `GET /health/ready`, 5 s interval, 2 consecutive failures → unready; HPA's "ready replica" count drives the scaling decision (so a draining pod stops counting toward capacity automatically). |
| Custom-metric scaling | optional — request rate | not required by the spec; operator may add a request-rate trigger (e.g. via KEDA or a custom-metrics adapter) if CPU lags real load on I/O-heavy paths such as SSE search streaming. |

**Resource requests / limits.** `requests.cpu` and `requests.memory` must be set on every pod (HPA targets the request, not the limit, when computing utilisation %). Suggested starting point: `requests.cpu: 500m`, `requests.memory: 1Gi`; tune from real load. Limits should be set to avoid noisy-neighbour effects but should not be tight enough to cause throttling under the §1 SLO targets.

**Capacity-plan tie-in.** The §2 capacity model assumes the gate scales horizontally — the per-pod numbers in §2 are *per-replica* steady-state targets, and the cluster as a whole serves §2 peak × N replicas. If the §1 SLO is breached and CPU is the bottleneck, raise `maxReplicas` before raising per-pod `requests.cpu`.

**Out of scope for this row but worth noting:** PostgreSQL is **not** auto-scaled — primary + standby is a fixed pair per §3. If DB-side capacity becomes the bottleneck, scaling that is a separate operations decision (vertical resize / connection-pool tuning / read-replica routing for the SELECT-heavy authority paths), not an HPA concern.

## 4. Pinned protocols and version floors

**The v2 spec leaves the implementation stack open.** Language, build tool, HTTP framework, ORM / JDBC layer, JWT library, logging library, UI framework — all the implementer's call. What is pinned is the behavioural contract below: protocols the gate must speak on the wire, version floors of the external dependencies it must talk to, algorithms and cost factors that calibrate the security / SLO trade-offs.

| Concern | Pinned contract | Notes |
|---|---|---|
| **PostgreSQL** | 14 – **17** (tested 14.10 / 15.5 / 16.1 / 17) | Required extensions: `uuid-ossp`, `citext`, `pg_trgm`, `btree_gin`. Append-only role grants per `db/README.md`. **Upper bound is real:** ReSQL `v1.3.4` and TIM `pre-apha-2.7.1` both bundle the PostgreSQL JDBC driver 42.3.9, which officially supports servers to 15; 17 is a tested calculated risk and **18 must not be used**. Lift the ceiling only once both images ship JDBC ≥ 42.6 — see `docs/planning/known-issues.md` KI-001. |
| **XML processing** | Must validate every inbound eFTI payload against the XSDs in `docs/efti-analysis/xsd/`; must emit AS4 SOAP envelopes per the eDelivery 1.15 conformance profile. Streaming preferred (10 MB body limit, §6). | Library is the implementer's choice. |
| **Cryptography (eDelivery)** | AES-128-GCM for symmetric encryption; RSA-OAEP for key transport; XML Signature SHA-256. | Per the EU eDelivery AS4 1.15 conformance profile. |
| **JWT validation** | RS256 only. JWKS fetched from `taraJwt` discovery and cached per `TARA_JWKS_CACHE_SECONDS`; on `kid` cache-miss the validator force-refreshes JWKS once before failing. **Clock-skew tolerance: ±60 s** for `exp`, `iat`, `nbf`. | Any RS256-capable JWT library satisfies the contract. |
| **Bcrypt** | Break-glass local-admin password only. **Cost factor 12** (`$2a$12$…`); ~240 ms per verify on the reference hardware — sized to keep break-glass login under the 5 min recovery SLO while still costing brute-force attackers. | |
| **Logging output** | JSON, single-line, ECS 8.x dotted-field taxonomy, `efti.*` namespace for custom fields. | Full field-by-field spec in `logging-spec.md`. |
| **Schema migrations** | Declarative, versioned; idempotent on re-apply; `schema.sql` is the snapshot generated from migration history, not the source of truth. | Per `db/README.md` migration-policy header. Migration tool is the implementer's choice. |
| **AS4 implementation** | EU eDelivery AS4 1.15 conformance profile. Custom AP **or** Domibus — operator's choice; both must satisfy the conformance profile. | |

### 4.1 Required environment variables

| Variable | Purpose | Default |
|---|---|---|
| `TARA_OIDC_DISCOVERY_URL` | Where the gate fetches JWKS, `iss`, supported algorithms. | `https://tara.ria.ee/.well-known/openid-configuration` (test issuer for non-prod) |
| `TARA_CLIENT_ID` | The gate's TARA `aud` claim. | required, no default |
| `TARA_CLIENT_SECRET` | Consumed by **TIM**, which performs the OIDC code exchange. The gate's REST API never handles OIDC codes and never holds this secret. | required by TIM, no default |
| `TARA_JWKS_CACHE_SECONDS` | TTL for the JWKS cache. Applies to TIM, which is the component that validates TARA ID tokens. | 3600 |
| `TIM_KEYSTORE_PASSWORD` | Password for the keystore holding TIM's JWT signing key (`JWT_INTEGRATION_SIGNATURE_KEY_STORE_PASSWORD`). Provisioned via Kubernetes Secret. | required, no default |
| `TIM_DB_PASSWORD` | Password for TIM's own PostgreSQL instance (sessions and token blacklist). Not the eFTI database. | required, no default |
| `JWT_TTL_MINUTES` | Session-token lifetime, applied by TIM as `legacy-portal-integration.sessionTimeoutMinutes`. Bounds how long an unrevoked session lasts; it is **not** the revocation-latency knob, since revocation is immediate (`permissions-matrix.md` §6). | 30 (TIM's own default) |
| `SECURITY_ALLOWLIST_JWT` | Comma-separated hostnames/IPs TIM will answer `/jwt/*` for. Must include `ruuter`. Hostname-based, so it is only meaningful on a non-routable internal network. | required, no default |
| `ARCHIVE_OPS_TOKEN` | The static Bearer secret accepted on `/api/v1/admin/archive`, `/expire-identifiers`, `/ping-gates`. 256-bit random; provisioned via Kubernetes Secret. | required, no default |
| `LOCAL_ADMIN_FALLBACK_ENABLED` | If `true`, `POST /api/v1/auth/local-token` returns 200 with a gate-signed JWT instead of 503. | `false` |
| `BREAK_GLASS_JWT_SIGNING_KEY` | PEM-encoded RSA private key the gate uses to sign break-glass JWTs (only consulted when `LOCAL_ADMIN_FALLBACK_ENABLED=true`). | optional |
| `BREAK_GLASS_JWT_TTL_SECONDS` | Hardcoded ceiling 600. Operator may shorten further. | 600 |
| `MTLS_HEADER_SUBJECT` / `MTLS_HEADER_SERIAL` | Which trusted-proxy headers carry the platform cert subject DN and serial. | `X-Client-Cert-Subject` / `X-Client-Cert-Serial` |
| `GATE_ID` / `COUNTRY_CODE` | Identity of this gate (`iss` for break-glass JWTs and the configured `gateId` for follow-up validation). | required, no default |
| `EU_PLATFORM_REGISTRY_URL` / `EU_PLATFORM_REGISTRY_REFRESH_MINUTES` | EU central registry of certified platforms (Reg 2020/1056 Art 7+12). | required, no default / 60 |
| `EU_TRUST_LIST_URL` / `EU_TRUST_LIST_REFRESH_HOURS` | EU Trust Service trust-list bundle URL the reverse proxy validates inbound mTLS against. Refresh on the listed cadence; on fetch failure, keep the last-known-good list (do not fail closed on the trust list itself — only on individual cert revocation lookups). | required, no default / 24 |
| `OCSP_TIMEOUT_MS` / `CRL_REFRESH_HOURS` | Cert-revocation lookup timeout and CRL refresh interval at the reverse proxy. **Fail closed**: a soft-fail timeout treats the cert as invalid. | 5000 / 6 |
| `RATE_LIMIT_PER_MINUTE` | Per-source rate limit at the reverse-proxy layer (subject definition in §4.2). | 100 |
| `GATE_BROADCAST_TIMEOUT_MS` | Per-gate timeout on Authority broadcast searches. Surfaces as `504 GATE_TIMEOUT` for that one peer; the broadcast continues for the remaining peers. | 8000 |
| `PLATFORM_TIMEOUT_MS` | Per-platform timeout on dataset retrieval. Surfaces as `504 PLATFORM_TIMEOUT`. | 30000 |
| `PING_TIMEOUT_SECONDS` | Per-gate timeout on the CronManager-driven `/admin/ping-gates` probe. A timeout flips `gates.status` to `OFFLINE` for that peer; does not fail the sweep. | 10 |
| `AUTHORITY_QUERY_AUDIT` | When `enabled` (default), every authority access produces a 7-year-retained `audit_log` row per `logging-spec.md` §5. When `disabled`, audit rows are skipped — operationally permitted only in non-production environments to control the live-DB growth rate. Disabling in production violates GDPR Art 30 retention. | enabled |
| `TARA_OIDC_DISCOVERY_REFRESH_HOURS` | The OIDC discovery document (`/.well-known/openid-configuration`) is re-fetched at this cadence. The JWKS cache TTL (`TARA_JWKS_CACHE_SECONDS`) is independent and shorter. | 24 |

**Expiry ownership.** An earlier draft stated that TARA owns expiry policy on the primary
auth path and that no expiry variable exists. That is not the case: the session token is
minted by **TIM**, so TIM owns its lifetime, via `JWT_TTL_MINUTES` above (TIM property
`legacy-portal-integration.sessionTimeoutMinutes`, applied in `JwtUtils.createSignedJwt()`
whenever the caller supplies no explicit expiry — which is every OIDC login). TARA's own ID
token is short-lived and is consumed once, by TIM, at code exchange; it never reaches the
gate's REST surface. The break-glass path's TTL remains hardcoded to 600 s and
operator-shortenable via `BREAK_GLASS_JWT_TTL_SECONDS`.

### 4.2 Pinned implementation choices (load-bearing decisions)

These are decisions where two competent implementations might pick differently and produce subtly different gates. Pinned here so the spec doesn't fork:

- **Rate limiting** — enforced at the **reverse-proxy layer** per source-IP using a **token-bucket** algorithm; default `RATE_LIMIT_PER_MINUTE=100` (see §4.1). Authority/Admin: per source IP. Platform mTLS: per cert-subject DN (the proxy reads it from the cert before passing to the gate). G2G AS4: per source-gate id (resolved from the inbound cert). On limit breach the proxy returns `429 RATE_LIMIT_EXCEEDED` with a `Retry-After` header (integer seconds — HTTP-date form is non-conformant).
- **Archival batch sizing** — admin/archive `batch_size` default **1000 rows per commit**; lower to 100 if any single-row size > 100 KB; higher than 5000 only after benchmarking against the live DB's IOPS budget. The `max_runtime_seconds` budget is checked *between* batches, never mid-batch — the in-flight batch always commits or rolls back atomically before the function returns. When the budget expires the response carries `partial=true` and `next_archivable_count_estimate` populated so the next CronManager invocation continues where this one stopped.
- **Pagination ordering** — every paginated list endpoint (`GET /api/v1/{gates,platforms,authorities,users,consignments,audit}`) returns the **latest row per logical id** ordered by `(created_at DESC, row_id ASC)`. The `row_id` ASC tiebreaker resolves the case where two latest-rows share a `created_at` value. Clients paginating with `(limit, offset)` get a stable window; new rows landing during pagination shift the head, so cursors that re-issue from the start may see a row twice.
- **Pinned crypto suites** — XML Signature: RSA-SHA256 over Inclusive Canonicalization (XML-C14N); XML Encryption: AES-128-GCM with RSA-OAEP key transport; TLS: TLS 1.2+ with ECDHE key exchange. Anything weaker is rejected at the proxy.
- **Retry policy from the gate to platforms / peer gates** — the gate does **not** retry by itself. A platform 5xx surfaces directly to the authority caller as `502 GATEWAY_UNAVAILABLE` or `504 PLATFORM_TIMEOUT`; the catalogue's `retryable: true|false` is a *hint to clients*, never written to the wire (`ProblemDetails` carries no retryable field).

## 5. Compliance targets

| Topic | Requirement | Anchor |
|---|---|---|
| Audit retention | **`audit_log` is never automatically purged.** It is held on the live DB indefinitely (the `db_archiver` PostgreSQL role has only `SELECT` on this table — no automated DELETE path exists, by design). The 7-year regulatory floor (GDPR Art 30; Reg 2024/1942 Art 6) is satisfied by inaction. Other operational tables: 7 years on the live DB **plus archive**. | GDPR Art 30; Reg 2024/1942 Art 6 |
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
