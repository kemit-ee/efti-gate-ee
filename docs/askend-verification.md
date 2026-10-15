# Security posture self-assessment

## Status: no formal security audit has been conducted

This ticket (EFTI-30) was originally scoped as "map every CRITICAL/HIGH Askend finding to its
resolving issue." No such finding list exists: the only Askend review on file
(`docs/planning/ASKEND-FEEDBACK-EXECUTIVE-SUMMARY.md`, 2026-04-22) was a **specification-completeness**
review at the start of the rewrite (missing OpenAPI/schema/diagrams), not a security pentest, and no
security-specific audit has happened since. Writing a verification document that claims to confirm
specific "Askend findings" are resolved would therefore be fabricating evidence against findings that
were never recorded.

What follows instead is an honest self-assessment of the current security posture, organized the way a
real audit would be, so that (a) anyone reading this knows exactly what's been verified vs. assumed,
and (b) this document is a reasonable starting point for an actual external security review, whenever
one is commissioned.

## Authentication & identity

| Area | Status | Evidence |
|---|---|---|
| Admin UI login | TARA (national eID) via TIM, standard JWT bearer tokens | PRs #100, #103, #136; `docker/tara-mock/`, `docker/tim/` |
| Basic Auth / username-password | Does not exist | Replaced outright, not merely disabled (PR #100/#108) |
| Platform credential | Plaintext `X-Api-Key`, compared verbatim | `platforms/.guard.yml`; **known accepted risk**, see below |
| X-Road authority identity | Security Server client cert + `X-Road-Client` member code, resolved against `registry/authorities/` | `xroad/.guard.yml`, ADR-006 |
| Dev-only auth bypass | `dev-login` returns 404 unless `DEV_LOGIN_ENABLED=true`; production Docker build default is `false` | AGENTS.md "Permissions" section |

## Authorization & access control

| Area | Status |
|---|---|
| Admin users | Flat model — any authenticated user is an admin; no role hierarchy exists to bypass |
| X-Road authorities | Per-authority `subsets` entitlement enforced in `xroad/.guard.yml` / `dataset.yml`; deny is the fall-through branch, every accept path is an explicit positive condition |
| Platforms | Each platform can only write/read its own consignments (ownership check in `platforms/*.yml`) |
| Internal gate-to-gate surface (`efti/api/v1`) | Gated by `X-Internal-Service-Token`, unreachable from outside the compose network by design |

## Transport & message security

| Area | Status |
|---|---|
| Gate-to-gate (AS4) | Inbound signature + digest verification (PR #158, `SignatureValidator.kt`, standard `javax.xml.crypto` XMLDSig, exclusive C14N) |
| Admin UI / browser traffic | Same-origin via nginx reverse proxy (no CORS surface to misconfigure) |
| mTLS for internal service-to-service calls | **Not implemented** — internal routes use a shared bearer token (`X-Internal-Service-Token`), not mTLS. Accepted for now; see risks below. |

## Secrets management

| Area | Status |
|---|---|
| AS4 keystore | Loaded at runtime from a mounted path, never baked into any image (PR #296, `code/.dockerignore`) |
| Application secrets (`INTERNAL_SERVICE_TOKEN`, `ARCHIVE_OPS_TOKEN`, DB passwords, etc.) | Environment variables, the native way Kubernetes Secrets reach a container |
| Platform API keys | Plaintext in the `registry` git repo / baked image (ADR-011 §5) — a deliberate, documented tradeoff: Ruuter's expression engine has no hash function, so the registry is treated as a secret store with no published port |

## Audit & compliance (GDPR Art. 30)

| Area | Status |
|---|---|
| Admin action logging | `audit_log` table, append-only, covers admin CRUD/reads/login (PR #163) |
| Authorization denial logging | Kotlin services (edelivery, xml-mapper) log denials with reason at WARN via klite's `RequestLogger`; Ruuter DSL guards (admin/platforms/xroad/efti-internal/ops) write to `audit_log` on every 401/403 (today's work, EFTI-94) |
| Data retention | Consignments archived to cold storage after `olderThanDays` (default 2), purged after `keepDays` (default 2555); audit log itself has no automatic purge configured |

## Input validation

| Area | Status |
|---|---|
| DSL route contracts | `declaration.allowlist` + `validate_input`/`check_input`, enforced by Ruuter (400 on missing/wrong-typed fields); audited by `dsl-lint --audit` in CI |
| X-Road-Id / X-Road-Client format | Validated as hex UUID / typed member-code before any lookup (`xroad/.guard.yml`) |
| XML schema validation | CI validates every generated/sample FTI message against its XSD (`xml-mapper:test`'s `FTIMessagesTest.kt`, `tests/mock/dataset.py`) |

## Known, accepted risks (not resolved, documented on purpose)

- **Platform API keys are plaintext**, both at rest (registry git files) and in transit to the guard
  comparison (ADR-011 §5, amending ADR-004's original SHA-256 plan). Accepted because the DSL engine
  cannot hash, and the registry service has no published port as a compensating control.
- **No mTLS** for the internal gate-to-gate or gate-internal surfaces — a shared bearer token instead.
- **No rate limiting** at the reverse-proxy or application level (SEC-9, tracked separately, still open).
- **No CertificateProvider/SecretProvider abstraction class** — certs/secrets are consumed directly via
  mounted files and environment variables. Functionally equivalent for a single-image deployment model,
  but there's no pluggable swap-in point if that model changes.
- **Sonar quality gate** was only just enabled (EFTI-52) — static-analysis findings accumulated before
  that point haven't necessarily been triaged yet.

## Recommendation

This document is **not** a substitute for an external audit — treat it as the briefing pack for one.
If Askend (or anyone else) is engaged for an actual security review, hand them this file plus
`docs/specs/errors.json`, the ADRs under `docs/architecture/decisions/`, and
`docs/specs/deploy/resource-and-secrets-requirements.md`, and expect the "accepted risks" section above
to be exactly where they focus first.
