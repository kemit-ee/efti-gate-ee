# EPIC 14 — Security

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Security and Compliance](README.md). Architecture: [security-and-compliance/README.md](../../architecture/security-and-compliance/README.md) (theme-wide rules) + [security-and-compliance/security.md](../../architecture/security-and-compliance/security.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** security auditor<br>
**I WANT** the gate to meet production security requirements<br>
**SO THAT** the system passes a security audit and complies with e-government standards.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **Pinned protocols / algorithms** | TLS, AES-128-GCM, RSA-OAEP, XML Signature SHA-256, RS256: [`non-functional.md`](../../specs/non-functional.md) §4 |
| **Access-check rules** | Role + Party ID + subset enforcement: [`permissions-matrix.md`](../../specs/permissions-matrix.md) |
| **Error format** | RFC 7807 Problem Details, `requestId` correlation: [`openapi.yaml`](../../specs/openapi.yaml), [`errors.json`](../../specs/errors.json) |
| **Environment** | `EU_TRUST_LIST_URL`, `OCSP_TIMEOUT_MS`, `CRL_REFRESH_HOURS`, `EU_PLATFORM_REGISTRY_URL`, `EU_PLATFORM_REGISTRY_REFRESH_MINUTES`, `RATE_LIMIT_PER_MINUTE`: [`non-functional.md`](../../specs/non-functional.md) §4.1 |
| **Reverse-proxy contract** | TLS / mTLS termination, OCSP/CRL fail-closed, EU Trust Service trust list, `X-Client-Cert-Subject` / `X-Client-Cert-Serial` forwarding: [`non-functional.md`](../../specs/non-functional.md) §3 |
| **Architecture** | [RA §8.1 Security Layers](../../architecture/eFTI-Gate-Reference-Architecture.md#81-security-layers) |
| **Architecture** | [../../architecture/security-and-compliance/README.md](../../architecture/security-and-compliance/README.md) (theme rules) + [../../architecture/security-and-compliance/security.md](../../architecture/security-and-compliance/security.md) (sub-architecture) |

## Acceptance Criteria

### Secrets management

**Business rules:**
- [ ] **No** secret (password, API key, private key, TLS cert) is stored in a configuration file, repository, or build artefact.
- [ ] All secrets are loaded **at runtime** from an external store (K8s Secret / vault / equivalent). Environment-variable injection is the standard delivery.
- [ ] Multiple backends supported (development vs production) without code changes.
- [ ] Certificate **rotation** is possible without restarting the application.
- [ ] Demo / test certificates (`*.p12`, `*.pem`, `*.crt`) **must not** exist in any production-runnable build artefact; only generation instructions ship.
- [ ] System-generated secrets (admin passwords, API tokens) are shown to the user **only once** at creation ("Show Once"). Thereafter only the hash / minimal reference is stored.
- [ ] API Bearer tokens are revocable without deleting the user (see Epic 1).

**Denial scenarios:**
- [ ] Secrets store unavailable at startup → application refuses to start; logs ERROR carrying the missing secret **name** (never the value).

### Certificate validity (Art 5(4) Reg 2024/1942)

**Business rules:**
- [ ] Outgoing eDelivery / fast-protocol connections verify the destination certificate's revocation status (OCSP or CRL) **before** sending. Trust chain is checked against the EU Trust Service trust list.
- [ ] OCSP / CRL checks **fail closed** — if the revocation lookup fails or times out (`OCSP_TIMEOUT_MS`), the cert is treated as invalid.
- [ ] Inbound AS4 messages with revoked signing certs are rejected; logged WARN with the sender Party ID.

### EU platform registry (Art 7, Art 12 Reg 2020/1056)

**Business rules:**
- [ ] The gate verifies that any communicating eFTI platform is listed as active in the EU central platform registry (refreshed per `EU_PLATFORM_REGISTRY_REFRESH_MINUTES`).
- [ ] If a platform is **removed** from the EU registry: requests from it are logged and answered with a warning; the gate does **not** immediately hard-block (Reg 2020/1056 requires graceful handling of de-registration).

### Rate limiting

**Business rules:**
- [ ] Rate limiting applies at the reverse-proxy layer (the gate process is not the rate-limit enforcer).
- [ ] `/v1/...` endpoints: 100 req/min per source IP by default (`RATE_LIMIT_PER_MINUTE`).
- [ ] Limit exceeded → RFC 7807 `429 Too Many Requests`.

### Error format and response hygiene

**Business rules:**
- [ ] Every REST error response follows RFC 7807: `{type, title, status, detail, instance, requestId}` — JSON for REST, XML for `/services/*`.
- [ ] Error responses **never** leak internal stack traces, framework internals, or filesystem paths to the caller. Full traces are server-side only.
- [ ] An unhandled exception → `500 Internal Server Error` with a **generic** detail; the server-side log carries the trace; the response carries `requestId` for incident correlation.
- [ ] `robots.txt` ships at the root and disallows indexing of all endpoints.

<!-- issue-body:end -->
