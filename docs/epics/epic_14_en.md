# EPIC 14 — Security

> Part of [Theme 6](theme_6_en.md)

**AS A** security auditor  
**I WANT** the gate to meet production security requirements  
**SO THAT** the system passes a security audit and complies with e-government standards

**References:**
- [Permissions Matrix](../specs/permissions-matrix.md) — Role-based access and write-access control
- [Error formats](../specs/errors.json) — RFC 7807 error catalogue
- [RA §8.1 Security Layers](../architecture/eFTI-Gate-Reference-Architecture.md#81-security-layers) — Full security layer stack: secrets, mTLS, rate limiting, error formats

#### Acceptance Criteria

##### Secrets management

**Happy path:**
- [ ] No secret (password, API key, private key) stored in configuration file or build artefact
- [ ] Secrets loaded at runtime from external secrets store (K8s Secret / vault); environment variable injection supported
- [ ] Secrets manager supports multiple backends (development vs production) without code changes
- [ ] TLS certificates loaded from mounted volume or secrets store — not embedded in build artefact
- [ ] Certificate rotation possible without application restart
- [ ] Demo/test certificates absent from production-runnable code; repository provides only certificate generation instructions
- [ ] System-generated passwords and API tokens shown to user **only once** at creation ("Show Once") — thereafter only hash stored
- [ ] API Bearer tokens revocable without deleting user; new token issued as replacement

**Edge cases:**
- [ ] Secrets store unavailable on startup → application refuses to start; logs ERROR with missing secret name (not value)

**Technical constraints:**
- [ ] Demo certificates (`*.p12`, `*.pem`, `*.crt` test files) MUST NOT exist in production build path
- [ ] Rationale: Askend security audit finding

##### Certificate validity checks (Art 5(4) 2024/1942)

**Happy path:**
- [ ] Outgoing eDelivery connections verify destination certificate status (OCSP or CRL) before sending
- [ ] Revoked/expired/non-compliant certificate → connection aborted; event logged with peer identity

**Edge cases:**
- [ ] OCSP responder unreachable → fail closed (connection refused), not fail open; event logged WARN
- [ ] Incoming AS4 message with revoked signing certificate → rejected; event logged WARN with sender Party ID

**Rationale:** Art 5(4) Reg 2024/1942 requires certificate validity verification for all inter-gate communication.

##### Platform compliance check (Art 7 + Art 12 Reg 2020/1056)

**Happy path:**
- [ ] eFTI Gate verifies communicating platform is listed as active in EU central registry of eFTI platforms
- [ ] Configuration includes EU registry query URL and refresh schedule

**Edge cases:**
- [ ] eFTI platform removed from EU registry → requests logged and answered with warning; not immediately blocked

**Technical constraints:**
- [ ] EU registry URL configurable via `EU_PLATFORM_REGISTRY_URL`; refresh interval via `EU_PLATFORM_REGISTRY_REFRESH_MINUTES`

##### Fast protocol (fast adapter)

**Happy path:**
- [ ] `/services/fast` endpoint uses mTLS — `X-API-Key` removed
- [ ] eFTI Gate identity verified by TLS certificate

##### Rate limiting

**Happy path:**
- [ ] Rate limiting configured at reverse proxy level
- [ ] `/v1/` endpoints: max 100 req/min per IP (configurable via `RATE_LIMIT_PER_MINUTE`)
- [ ] Rate limit exceeded → `429 Too Many Requests` RFC 7807 format

**Edge cases:**
- [ ] Burst of 101 requests in 1 minute from same IP → 101st returns `429`; first 100 processed normally

##### Error formats

**Happy path:**
- [ ] All REST API errors in RFC 7807 JSON: `{type, title, status, detail, instance, requestId}`
- [ ] Error messages do not expose internal stack traces or system information
- [ ] XML API errors (`/services/`) returned in XML format
- [ ] `robots.txt` present and disallows search engine access to all endpoints

**Edge cases:**
- [ ] Unhandled exception → `500 Internal Server Error` with generic message; full stack trace logged server-side only; `requestId` present in response for incident correlation
