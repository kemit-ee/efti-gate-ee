# Improvement Proposals

| | |
|---|---|
| **Author** | Sten Viljus |
| **Company** | Askend Estonia OÜ |
| **Contact** | sten.viljus@askend.com |

Consolidated from all analysis documents: [Code Review](eFTI_codereview_en.md), [Scalability Analysis](eFTI_scalability_en.md), [Deployment Guide](eFTI_deployment_en.md), [Error Handling and Logging Specification](eFTI_errors_logging_en.md), [Rights and Access Control Document](eFTI_rights_and_permissions_en.md).

---

## 1. Security

| # | Proposal | Priority | Effort | Source |
|---|----------|----------|--------|--------|
| 1.1 | **TARA authentication** — Replace Admin UI Basic Auth with TARA (national authentication service) authentication | HIGH | ~5-8 days | code review ch. 5 |
| 1.2 | **Disable username login** — In production allow only TARA, disable Basic Auth with password | HIGH | ~1 day (with 1.1) | code review ch. 5 |
| 1.3 | **Fast adapter security** — Replace `X-API-Key` with proper authentication (mTLS or signed tokens) | HIGH | ~3-5 days | code review ch. 5 |
| 1.4 | **Secrets management** — From .env files to secure vault (Kubernetes Secrets / AWS Secrets Manager) | HIGH | ~2-3 days | code review ch. 5, scalability analysis |
| 1.5 | **Bearer Auth standardization** — Replace current `base64(id:password)` format with JWT tokens or opaque API keys. Non-standard format may cause issues with third-party integrations | HIGH | ~3-5 days | code review |
| 1.6 | **Certificates out of image** — Load at runtime (mounted volumes, Secrets Manager), not built into image | MEDIUM | ~1-2 days | code review ch. 8 |
| 1.7 | **Rate limiting** — Implement at reverse proxy / ingress level (Caddy `rate_limit`, nginx-ingress annotations) | MEDIUM | ~0.5 days | code review ch. 5 |
| 1.8 | **Unified error format** — REST API errors are currently returned as plain text, missing request ID and error code in response. Standard JSON error format (`{status, error, message, requestId, timestamp}`) simplifies client-side error handling. Additionally: XML API needs to decide between plain text vs XML error format (TODO in code: `EftiService.checkGateAvailable()`) | MEDIUM | ~1-2 days | error and logging spec., TODO in code |
| 1.9 | **Audit logging** — Log successful logins, admin actions, data access (GDPR) | LOW | ~1 day | logging analysis |
| 1.10 | **checkWriteAccess role type check** — `User.checkWriteAccess()` only checks Party ID presence in `roles.values.flatten()`, but does not check role type. Theoretically a GATE role user could write to a Platform resource if the Party ID happens to match. There is a TODO note in the code | HIGH | ~0.5 days | rights and access control doc. |
| 1.11 | **Bearer token expiration** — Current Bearer Auth lacks token expiration and revoke mechanism. A compromised token is valid forever until the password is changed | HIGH | ~1-2 days | rights and access control doc. |

---

## 2. Logging and Observability

| # | Proposal | Priority | Effort | Source |
|---|----------|----------|--------|--------|
| 2.1 | **GateClient outgoing request logging** — Log gate ID, protocol (Fast/eDelivery), destination, result, duration. Methods: `sendAndReceive()`, `getIdentifiers()`, `getDataset()`, `postFollowUp()`, `ping()` | HIGH | ~1-2 days | logging analysis |
| 2.2 | **EDeliveryClient outgoing request logging** — Log destination, receiver Party ID, request ID, response status code, duration. Methods: `send()`, `sendAndReceive()`, `ping()` | HIGH | ~1 day | logging analysis |
| 2.3 | **Request ID propagation** — SLF4J MDC mechanism so all log messages are correlatable within a single request | HIGH | ~1-2 days | logging analysis |
| 2.4 | **EftiService business logic logging** — Log routing decisions (local vs remote), broadcast start/result, saved identifier count, follow-up routing | MEDIUM | ~1-2 days | logging analysis |
| 2.5 | **Structured logging (JSON + ECS)** — Add `logback-classic` + `logstash-logback-encoder`, JSON format in ECS standard for production (switchable via env variable). Mandatory fields: `@timestamp`, `log.level`, `trace.id` (requestId), `service.name`, `user.id`, `user.roles`, `url.path`, `client.ip`, `http.response.status_code`, `event.duration`. See [Error Handling and Logging Specification](eFTI_errors_logging_en.md) ch. 4.1b | HIGH | ~2-3 days | logging analysis, KeMIT MFN |
| 2.6 | **PlatformClient eDelivery variant logging** — Currently only logs REST variant, eDelivery delegated without logging | MEDIUM | ~0.5 days | logging analysis |
| 2.7 | **Authorization denial logging** — `AccessChecker` and `User.checkWriteAccess()` must log denials before throwing `ForbiddenException`. Currently denials are not visible in logs — security incidents remain unnoticed | MEDIUM | ~0.5 days | error and logging spec. |

---

## 3. Scalability

Only **software (code) changes** are consolidated here. Infrastructure and platform changes (AWS ECS/RDS/ElastiCache, Kubernetes operators, load balancers, CDN, etc.) are intentionally excluded — they depend on the chosen platform variant and are described in detail in the [Scalability Analysis](eFTI_scalability_en.md).

| # | Proposal | Priority | Effort | Source |
|---|----------|----------|--------|--------|
| 3.1 | **Registry synchronization** — PostgreSQL `LISTEN/NOTIFY` mechanism so in-memory registry changes reach all nodes. Currently `save()` and `delete()` only update the local `ConcurrentHashMap` | CRITICAL | ~3-5 days | scalability analysis stage 1.1, code review ch. 6 |
| 3.2 | **Request ID cache to Redis** — Duplicate control in shared cache, not in node-local `Cache` | CRITICAL | ~2-3 days | scalability analysis stage 1.2 |
| 3.3 | **Admin auth state sharing** — IP-based state to Redis or DB | CRITICAL | ~1-2 days | scalability analysis stage 1.3 |
| 3.4 | **Leader election for background jobs** — `GatePingJob` and `IdentifierExpirationJob` on only one node | MEDIUM | ~2-3 days | scalability analysis stage 2.1 |
| 3.5 | **Migration lock** — DB migration race condition with multiple nodes at startup | MEDIUM | ~1 day | scalability analysis stage 2.2 |
| 3.6 | **Secrets management (code side)** — Abstraction layer to support env vars, Secrets Manager, and K8s Secrets | HIGH | ~2-3 days | scalability analysis stage 3.1 |
| 3.7 | **Certificates (code side)** — Load from multiple sources (K8s Secret mount, Secrets Manager) instead of filesystem | HIGH | ~2-3 days | scalability analysis stage 3.2 |
| 3.8 | **Health checks** — Extend `/health` endpoint (DB connection, certificate validity, free memory) | MEDIUM | ~1-2 days | scalability analysis stage 4.3 |

Detailed plan (incl. AWS and Kubernetes infrastructure variants) see [Scalability Analysis](eFTI_scalability_en.md).

---

## 4. Interfaces and Integrations

| # | Proposal | Priority | Effort | Source |
|---|----------|----------|--------|--------|
| 4.1 | **X-Road interfaces** — Implement X-Road interfaces for communication with authorities and platforms (Estonian national data exchange layer) | HIGH | ~10-15 days | code review ch. 5 |

---

## 5. CI/CD and Deployment

| # | Proposal | Priority | Effort | Source |
|---|----------|----------|--------|--------|
| 5.1 | **Container Registry** — Use ghcr.io or AWS ECR. Tag images with Git commit hash | HIGH | ~1-2 days | code review ch. 8 |
| 5.2 | **Automatic deploy** — GitHub Actions workflow: test → build → push → deploy | HIGH | ~2-3 days | code review ch. 8 |
| 5.3 | **Rollback mechanism** — Previous version restoration (via tags from registry) | MEDIUM | ~1 day | code review ch. 8 |
| 5.4 | **Staging environment** — Separate VPS/namespace with the same compose files | MEDIUM | ~1-2 days | code review ch. 8 |
| 5.5 | **Zero-downtime deploy** — Difficult with Docker Compose, native in Kubernetes (rolling update) | MEDIUM | ~1-2 days | code review ch. 8 |
| 5.6 | **Versioning** — Semantic versioning + changelog | LOW | ~0.5 days | code review ch. 8 |

---

## 6. Performance and Code Quality

| # | Proposal | Priority | Effort | Source |
|---|----------|----------|--------|--------|
| 6.1 | **XSD versioning** — Formal versioning strategy for XSD files to ensure smooth transition during eFTI common dataset model updates | HIGH | ~1-2 days | code review |
| 6.2 | **DOM → StAX** — eDelivery message parsing without loading entire document into memory | MEDIUM | ~2-3 days | code review ch. 6 |
| 6.3 | **JAXB optimization** — Unmarshaller pooling or StAX-based parsing for high load | MEDIUM | ~1-2 days | code review ch. 6 |
| 6.4 | **Regex caching** — Move `Regex(...)` in `handleSaveIdentifiersRequest` to companion object field | LOW | ~0.5h | code review ch. 6 |
| 6.5 | **Expiration SQL** — `IdentifierExpirationJob` filtering in DB, not in Kotlin code | LOW | ~0.5 days | code review ch. 6 |
| 6.6 | **StringBuilder for XML** — Use StringBuilder when building XML with large result sets | LOW | ~0.5 days | code review ch. 6 |
| 6.7 | **XML canonicalization (C14N)** — `Xml.kt` regex-based `canonicalXml` is actually a whitespace normalizer for its own string templates, not standard C14N. During signing it is used only for cleaning the SOAP envelope template — digests are calculated on specific blocks separately. Standard C14N would be formally more correct, but practical risk is low | LOW | ~2-3 days | code review |
| 6.8 | **XSD validation in CI** — Automatic XML sample file validation against XSD schemas in CI pipeline | LOW | ~0.5 days | code review |
| 6.9 | **eDelivery code documentation** — Inline documentation for custom eDelivery implementation to facilitate future maintenance | LOW | ~1-2 days | code review |
| 6.10 | **Identifier cache** — Caffeine or similar caching layer for frequently queried identifiers to reduce DB load | LOW | ~1-2 days | code review |
| 6.11 | **Unknown rootTag error handling** — `GateMessageHandler` and `PlatformMessageHandler` silently ignore unknown message types. Should return error message to sender (TODO in code in both files) | MEDIUM | ~0.5-1 days | TODO in code |
| 6.12 | **eDelivery CompressionType check** — `EDeliveryRoutes.decryptPayload()` always assumes GZIP compression, but should check the message's CompressionType field (TODO in code) | LOW | ~0.5 days | TODO in code |
| 6.13 | **Multi-platform user support** — `PlatformRoutes` does not allow users with multiple Platform roles to send identifiers. Should enable platformId specification via request parameter (TODO in code) | MEDIUM | ~1-2 days | TODO in code |

---

## 7. Testing

| # | Proposal | Priority | Effort | Source |
|---|----------|----------|--------|--------|
| 7.1 | **EftiService unit tests** — Parallel broadcast, local vs remote routing, error handling | HIGH | ~2-3 days | code review ch. 10 |
| 7.2 | **PlatformClient unit tests** — eDelivery vs REST selection, subsetting, timeout handling | MEDIUM | ~1-2 days | code review ch. 10 |
| 7.3 | **E2E gate-to-gate test** — Communication between two instances (currently started but not tested) | MEDIUM | ~2-3 days | code review ch. 10 |
| 7.4 | **Error handling tests** — Timeouts, DB connection loss, invalid XML | MEDIUM | ~1-2 days | code review ch. 10 |
| 7.5 | **Follow-up tests** — Extend follow-up business logic tests | LOW | ~1 day | code review ch. 10 |

---

## 9. Frontend (UI)

| # | Proposal | Priority | Effort | Source |
|---|----------|----------|--------|--------|
| 9.1 | **UI validation improvement** — `UserForm.svelte` and `PlatformForm.svelte` real-time validation before form submission | MEDIUM | ~1-2 days | code review |
| 9.2 | **Svelte 5 migration** — Plan migration to Runes API (performance and developer experience improvement) | LOW | ~3-5 days | code review |

---

## 8. Monitoring

| # | Proposal | Priority | Effort | Source |
|---|----------|----------|--------|--------|
| 8.1 | **Centralized logging** — Log collection system (CloudWatch, Loki + Grafana, ELK) | MEDIUM | ~2-4 days | scalability analysis |
| 8.2 | **Metrics and dashboards** — Prometheus + Grafana or CloudWatch (CPU, memory, DB connections, eDelivery messages) | MEDIUM | ~3-4 days | scalability analysis |
| 8.3 | **Alerting** — Notifications for critical events (gate offline, DB connection loss, high error rate) | LOW | ~1-2 days | scalability analysis |

---

## Summary

### By Priority

| Priority | Count | Estimated Effort |
|----------|-------|------------------|
| **CRITICAL** | 3 | ~6-10 days |
| **HIGH** | 17 | ~32-51 days |
| **MEDIUM** | 23 | ~21-36 days |
| **LOW** | 13 | ~12-19 days |
| **Total** | **56** | **~71-116 days** |

### By Topic

| Topic | Count | Estimated Effort |
|-------|-------|------------------|
| Security | 11 | ~19-30 days |
| Logging | 7 | ~6-10 days |
| Scalability | 8 | ~15-22 days |
| Interfaces | 1 | ~10-15 days |
| CI/CD | 6 | ~6-10 days |
| Performance and code quality | 13 | ~12-20 days |
| Testing | 5 | ~7-11 days |
| Monitoring | 3 | ~6-10 days |
| Frontend | 2 | ~4-7 days |

### Recommended Order

**First phase (production readiness):**
1. TARA authentication + disable username login (1.1, 1.2)
2. checkWriteAccess type check (1.10)
3. Secrets management (1.4, 1.5)
4. Bearer token expiration (1.11)
5. X-Road interfaces (4.1)
6. Rate limiting (1.6)
7. Fast adapter security (1.3)
8. Registry synchronization (3.1) — if multiple instances are planned

**Second phase (quality and observability):**
9. Outgoing request logging (2.1, 2.2, 2.6)
10. Request ID propagation (2.3)
11. EftiService tests (7.1)
12. Container Registry + automatic deploy (5.1, 5.2)

**Third phase (scaling and monitoring):**
13. Request ID cache + admin auth state (3.2, 3.3)
14. Structured logging (2.5)
15. Centralized logging and metrics (8.1, 8.2)
16. Remaining scalability changes (3.4–3.8)

---

## 10. KeMIT MFN Compliance

Proposals arising from the KeMIT non-functional requirements (version 2026 v1.2.0) analysis. Analysis see `eFTI_codereview_en.md` chapter 12.

### 10.1 API and Documentation

| # | Proposal | Priority | Effort | MFN Requirement |
|---|----------|----------|--------|-----------------|
| 10.1.1 | **OpenAPI 3.0+ specification** — Generate OpenAPI spec file for all REST endpoints. Add Swagger UI or Redoc automatic documentation | HIGH | ~2-3 days | API: OpenAPI 3.0+, automatic doc. |
| 10.1.2 | **API versioning** — Add URL prefix `/api/v1/`, define version deprecation policy (min 6 months old version support) | HIGH | ~1-2 days | API: versioning in URL |
| 10.1.3 | **RFC 7807 Problem Details error format** — Replace plain text error messages with standard JSON structure `{type, title, status, detail, instance}` | HIGH | ~1-2 days | API: RFC 7807 error messages |
| 10.1.4 | **CORS policy** — Configure explicit CORS policy (allowed origins, methods, headers) | MEDIUM | ~0.5 days | API: CORS |
| 10.1.5 | **Pagination** — Add pagination to identifier search (RFC 5988 Link header, offset/limit, meta info) | MEDIUM | ~1-2 days | API: pagination, RFC 5988 |

### 10.2 Authentication and Session Management

| # | Proposal | Priority | Effort | MFN Requirement |
|---|----------|----------|--------|-----------------|
| 10.2.1 | **JWT authentication (RFC 7519, RFC 9068)** — Implement JWT-based authentication with TARA. Access token + refresh token, token expiration, signing | HIGH | ~5-8 days | Sec.: JWT + TARA, K8s: session mgmt |
| 10.2.2 | **Session expiration mechanism** — Configurable session duration, automatic expiration, user notification before expiration | HIGH | ~1-2 days | Sec.: session expiration |
| 10.2.3 | **Logout** — Implement secure logout (token revocation, session termination, one-click) | HIGH | ~1 day | Sec.: logout |
| 10.2.4 | **Failed login attempt limiting** — Configurable count and time window, lockout, notification | MEDIUM | ~1 day | Sec.: rate limiting |
| 10.2.5 | **OAuth2 between applications** — Inter-application authentication with OAuth2 client credentials flow | MEDIUM | ~2-3 days | Conf.: OAuth2 |

### 10.3 Security and Compliance

| # | Proposal | Priority | Effort | MFN Requirement |
|---|----------|----------|--------|-----------------|
| 10.3.1 | **SonarQube integration** — Add SonarQube analysis to CI/CD pipeline, ensure 0 high/critical issues | HIGH | ~1-2 days | Sec.: SonarQube |
| 10.3.2 | **Dependency Track / SBOM** — Generate SBOM (CycloneDX), integrate with KeMIT Dependency Track service | HIGH | ~1-2 days | Sec.: Dependency Track |
| 10.3.3 | **Container image scanning** — Add Trivy or Grype to CI/CD pipeline, block MEDIUM+ vulnerabilities | HIGH | ~0.5-1 days | Cont.: vulnerability scanning |
| 10.3.4 | **robots.txt** — Add robots.txt file that denies search engine access | LOW | ~0.5h | Sec.: robots.txt |
| 10.3.5 | **WCAG 2.2 AA finishing** — Baseline exists (label-input associations, focus rings, ARIA roles, semantic HTML). Fix: `aria-label` on icon-only buttons, `aria-labelledby` on modal, skip navigation link, `.text-muted` color contrast (gray-400 → gray-500+), `aria-sort` on `SortableTable` | MEDIUM | ~1-2 days | Gen.: WCAG 2.2 AA |

### 10.4 Logging and Monitoring

| # | Proposal | Priority | Effort | MFN Requirement |
|---|----------|----------|--------|-----------------|
| 10.4.1 | **ECS JSON log format** — Implement Elastic Common Schema (ECS) format JSON logging (logback + logstash-logback-encoder or analog). Mandatory fields see proposal 2.5 and `eFTI_errors_logging_en.md` ch. 4.1b. Overlaps with proposal 2.5 — must be implemented together | HIGH | ~1-2 days | Log.: ECS standard |
| 10.4.2 | **Prometheus metrics** — Add metrics endpoint (Micrometer or Klite-customized solution), expose JVM, HTTP, and business metrics | HIGH | ~2-3 days | Log.: Prometheus |
| 10.4.3 | **Audit log** — Log all data views, creations, modifications, and deletions associated with user identity and role. Separate audit log from application working database | HIGH | ~3-5 days | Log.: audit log, person and role association |
| 10.4.4 | **Repeated error message optimization** — Exponential backoff logic for repeated error messages in logs | LOW | ~0.5-1 days | Log.: exponential logging |

### 10.5 Versioning

| # | Proposal | Priority | Effort | MFN Requirement |
|---|----------|----------|--------|-----------------|
| 10.5.1 | **Semantic versioning (SemVer)** — Establish MAJOR.MINOR.PATCH versioning process | HIGH | ~0.5 days | Ver.: SemVer |
| 10.5.2 | **CHANGELOG.md** — Create CHANGELOG.md per Keep a Changelog 1.1.0 standard | HIGH | ~0.5 days | Ver.: CHANGELOG.md |
| 10.5.3 | **Git tags** — Mark each release with Git tag in format vX.Y.Z | HIGH | ~0.5h | Ver.: Git tags |

### 10.6 Database

| # | Proposal | Priority | Effort | MFN Requirement |
|---|----------|----------|--------|-----------------|
| 10.6.1 | **Database object comments** — Add COMMENT to all tables and fields (in English) via Flyway migrations | MEDIUM | ~1 day | DB: commented tables |
| 10.6.2 | **Data record versioning** — Implement audit trail / temporal tables for data change tracking | MEDIUM | ~3-5 days | DB: data record versioning |
| 10.6.3 | **Foreign key indexing** — Check and add missing indexes on all FK fields | LOW | ~0.5 days | DB: FK indexing |

### 10.7 Containers and Kubernetes

| # | Proposal | Priority | Effort | MFN Requirement |
|---|----------|----------|--------|-----------------|
| 10.7.1 | **Kubernetes manifests** — Create K8s Deployment, Service, HPA, ConfigMap, Secret manifests | HIGH | ~3-5 days | K8s: HPA, ConfigMap, Secret, resource limits |
| 10.7.2 | **Liveness/readiness checks** — Separate `/health/live` and `/health/ready` endpoints, check DB connection, certificate validity | HIGH | ~1 day | K8s: liveness/readiness checks |
| 10.7.3 | **Graceful shutdown** — Implement explicit SIGTERM handling, complete in-flight requests (30s timeout) | MEDIUM | ~1 day | K8s: graceful shutdown |
| 10.7.4 | **Minimalist base image** — Switch JVM image to distroless/Alpine variant | LOW | ~1 day | Cont.: minimalist base image |
| 10.7.5 | **Container image signing (Cosign)** — Add image signing to CI/CD pipeline | LOW | ~0.5-1 days | Cont.: image signing |

### 10.8 User Interface

| # | Proposal | Priority | Effort | MFN Requirement |
|---|----------|----------|--------|-----------------|
| 10.8.1 | **TEDI design system component adoption** — Replace custom Svelte components with TEDI components (https://tedi.tehik.ee/) | HIGH | ~5-10 days | UI: TEDI design system |
| 10.8.2 | **Estonian language user interface** — Translate entire UI to Estonian (menus, forms, error messages, help texts). Implement i18n support | HIGH | ~3-5 days | UI: Estonian language |
| 10.8.3 | **Role selection** — If user has multiple roles, show role selection at login | MEDIUM | ~1-2 days | UI: role selection |
| 10.8.4 | **Form state persistence** — Periodic draft saving so user can resume activity | LOW | ~2-3 days | UI: resume activity |

### 10.9 Source Code and Repository

| # | Proposal | Priority | Effort | MFN Requirement |
|---|----------|----------|--------|-----------------|
| 10.9.1 | ~~**Migration to KeMIT code repository**~~ — Code is already in KeMIT-controlled GitHub repo. **Compliant** | — | — | SC: KeMIT code repository |
| 10.9.2 | **Remove demo certificates** — Remove demo certificates from repo, add generation instructions | MEDIUM | ~0.5 days | SC: secrets out of code |
| 10.9.3 | **Code documentation improvement** — Add KDoc/Javadoc for critical classes and methods | LOW | ~2-3 days | SC: commented code |

---

## KeMIT MFN Summary

### By Priority (chapter 10 only)

| Priority | Count | Estimated Effort |
|----------|-------|------------------|
| **HIGH** | 19 | ~30-52 days |
| **MEDIUM** | 11 | ~15-28 days |
| **LOW** | 7 | ~7-11 days |
| **Total** | **37** | **~52-91 days** |

### Recommended Order (KeMIT MFN)

**First phase (mandatory security measures):**
1. JWT authentication + TARA (10.2.1)
2. Session expiration mechanism + logout (10.2.2, 10.2.3)
3. SonarQube + Dependency Track (10.3.1, 10.3.2)
4. Container image scanning (10.3.3)

**Second phase (API and documentation):**
5. OpenAPI specification (10.1.1)
6. API versioning (10.1.2)
7. RFC 7807 error format (10.1.3)
8. SemVer + CHANGELOG.md + Git tags (10.5.1, 10.5.2, 10.5.3)

**Third phase (logging and monitoring):**
9. ECS JSON log format (10.4.1)
10. Prometheus metrics (10.4.2)
11. Audit log (10.4.3)

**Fourth phase (infrastructure and UI):**
12. Kubernetes manifests + liveness/readiness (10.7.1, 10.7.2)
13. Migration to KeMIT code repository (10.9.1)
14. TEDI design system + Estonian language UI (10.8.1, 10.8.2)

> **NB:** Many KeMIT MFN proposals partially overlap with earlier chapter proposals (e.g. 1.1 TARA authentication, 2.5 structured logging, 5.6 versioning). During actual planning these must be combined to avoid duplicate work.
