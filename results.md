# OWASP Top 10 Security Assessment — eFTI Gate (EE)

**Date:** 2026-09-19
**Revision audited:** `b9a49fa` (branch `dev`)
**Scope:** Full repository — Ruuter DSL (`DSL/Ruuter`, `DSL/Ruuter-xroad-mock`), ReSql SQL
(`DSL/Resql`), Liquibase schema, Kotlin services (`code/edelivery`, `code/xml-mapper`,
`code/multiplexer`, `code/core`, `code/ui`), Docker/Compose, nginx, CI, and the
committed secret material.
**Methodology:** Manual source review mapped to the OWASP Top 10 (2021), OWASP ASVS-style
control checks, secret scanning, dependency/config review. No dynamic/black-box testing was
performed; no files were modified.

> **Context caveat.** This repository is primarily the *specification corpus plus a reference
> Compose deployment*. Many hardcoded credentials are explicitly documented as dev-only
> (`docs/specs/deploy/resource-and-secrets-requirements.md`). Findings are rated for the
> production posture of the deployed gate; dev-only exposure is called out in each entry.
> The single most important structural fact is that several security boundaries depend on
> **network isolation that is not enforced by code** — those are flagged Critical/High even
> where the intended topology is safe.

---

## Executive summary

| ID | OWASP | Severity | Finding | Location | Status |
|----|-------|----------|---------|----------|--------|
| F-01 | A03 | **Critical** | XXE in inbound AS4 signature verification (pre-auth) | `code/edelivery/src/edelivery/SignatureVerifier.kt:70,86` | **Fixed** (`def7ae7`) |
| F-02 | A02 | **Critical** | Private AS4 key + PKCS#12 (`changeit`) committed; keystore previously baked into all images | `code/certs/own.key`, `own.p12` | **Fixed** (no private key baked into container, key in git for DX, not expected to be used in prod, `c568bc2`) |
| F-03 | A01/A07 | **Critical** | `INTERNAL_SERVICE_TOKEN` is a committed dev literal with no env-injection path; grants full Authority API | `constants.ini:27`, `docker/ruuter/Dockerfile:4` | Open |
| F-04 | A01 | **High** | `/xroad/**` trusts a plain header (`X-Road-Client`); shares port 8086 with the public API; protected only by ingress config | `DSL/Ruuter/xroad/.guard.yml:15-21` | Open |
| F-05 | A01/A04 | **High** | Kotlin internal endpoints (`/api/v1/send`, `/search`, `/rest`, xml-mapper) have no application-layer auth | `InternalRoutes.kt`, `MultiplexerRoutes.kt`, `xml-mapper/*` | Open |
| F-06 | A01 | **High** | ReSql runs as DB owner/superuser `efti`, not least-privilege `app` — append-only guarantee not enforced at runtime | `resql.yaml:25,39`, `compose.yml:10-12` | Open |
| F-07 | A02/A08 | **High** | AS4 signature verification: no cert expiry, revocation, or sender-subject binding | `KeyManager.kt:46-48`, `SignatureVerifier.kt:84,93` | **Partially fixed** (`2edac04`) |
| F-08 | A03/A10 | **High** | User-controlled values interpolated into outbound URLs (SSRF / path injection) | `dataset-local.yml:33`, `follow-up-local.yml:78`, `authority/dataset.yml:78` | Open |
| F-09 | A04/A05 | **High** | No inbound body-size cap; XML parsed into memory (DoS); spec's 10 MB limit unimplemented | `EDeliveryRoutes.kt:43`, klite `HttpExchange` | Open |
| F-10 | A05 | **High** | `.env` tracked in git; DB/TIM/TARA passwords hardcoded; Liquibase `dev` context | `.env`, `compose.yml`, `liquibase.properties:3,8` | Open |
| F-11 | A05 | **Medium** | JDWP debug agents bound to `0.0.0.0` (unauthenticated RCE) — dev override | `compose.override.yml:40-56` | Open |
| F-12 | A05 | **Medium** | `tim-database` uses `trust` auth and is published on `0.0.0.0:19433` — dev override | `compose.yml:208`, `compose.override.yml:72-74` | Open |
| F-13 | A05 | **Medium** | No security headers (CSP/HSTS/X-Frame-Options/…), plain HTTP in nginx | `docker/ui/nginx.conf` | Open |
| F-14 | A01/A09 | **Medium** | Public diagnostic endpoints forward an arbitrary `cookie` to TIM | `efti/GET/api/v1/test/lubatud.yml:20`, `piiratud.yml:20` | Open |
| F-15 | A01 | **Medium** | Multiplexer polling key has no ownership check (cross-authority drain) | `MultiplexerRoutes.kt:64-78`, ADR-006 | Open |
| F-16 | A02 | **Medium** | Platform API keys stored as unsalted SHA-256; break-glass bcrypt is correct | `get_platform_by_api_key.sql:13` | Open |
| F-17 | A03 | **Medium** | Unescaped string interpolation into generated XML (XML injection) | `FTIMessages.kt:167,178`, `ParameterSearchCriteria.kt` | Open |
| F-18 | A04 | **Medium** | No rate limiting implemented in-repo (delegated to proxy, absent here) | `docs/specs/non-functional.md:4.1` | Open |
| F-19 | A05 | **Medium** | Permissive CORS (`*`) in ReSql; no CORS config in nginx/ruuter | `resql.yaml:44-45` | Open |
| F-20 | A09 | **Medium** | Raw AS4 body (possibly decrypted payload) logged on error | `EDeliveryRoutes.kt:78` | Open |
| F-21 | A09/A04 | **Medium** | No audit writer for X-Road/authority channels (GDPR Art. 30 gap) | ADR-006 open questions | Open |
| F-22 | A07 | **Medium** | `mock-platform` project has no guard, hardcoded key, no input declarations | `DSL/Ruuter/mock-platform/**` | Open |
| F-23 | A02 | **Medium** | AS4 decryption accepts unknown key/data encryption algorithms (warn only) | `EDeliveryRoutes.kt:103-106` | Open |
| F-24 | A06 | **Medium** | Mutable base-image tags and unpinned CI actions; Trivy covers UI only | Dockerfiles, `.github/workflows/e2e.yml` | Open |
| F-25 | A03 | **Low** | Regex-based XML parsing susceptible to catastrophic backtracking | `Xml.kt:22-35`, klite `XmlStrings` | Open |
| F-26 | A09 | **Low** | No root `.dockerignore`; root-context builds copy `.env`/`.git` into build stage | root-context Dockerfiles | **Partially fixed** (`c568bc2`) |
| F-27 | A04 | **Low** | `FORBIDDEN_SUBSET` unenforceable on JWT path; subset vocabularies disagree | `permissions-matrix.md:§3.2,§7` | Open |

> **Status column re-verified against `57775be`.** Since the audited revision `b9a49fa`, **F-01**
> and **F-07** have been fixed / partially fixed. **F-02** is reclassified as accepted: the
> committed key is an intentional dev-only fixture (no longer baked into images, `c568bc2`) that
> production must not use. **F-26** is partially addressed by the `code/.dockerignore` added in the
> same commit. Every other finding's source files are unchanged, so they remain open as written.

**Positive controls confirmed:** no SQL injection in the 51 ReSql query files (all bound
`:params`); deny-by-default guard fall-through; AS4 trust key pinned to the registry cert (not
embedded `KeyInfo`); algorithm allowlisting on signature verification; klite XML parser has DTDs
and external entities disabled; no Java native deserialization; no `TrustAllCerts` /
`verify=false`; no automatic redirect following on outbound HTTP.

---

## A01 — Broken Access Control

### F-03 (Critical) — Committed internal service token with no injection mechanism

`constants.ini` is `COPY`-ed verbatim into the Ruuter image at build time (`docker/ruuter/Dockerfile:4`)
with **no `${ENV}` substitution**. The token is the sole credential on all of `efti/api/v1/**`
(the Authority API), which includes `consignments-search` → `get_consignments` with
caller-supplied `limit`/`offset` — a full registry dump with no TARA and no audit row.

```ini
# constants.ini
27: INTERNAL_SERVICE_TOKEN=dev-internal-service-token-change-me
33: ARCHIVE_OPS_TOKEN=dev-archive-ops-token-change-me
11: TIM_ADMIN_TOKEN=dev-tim-admin-token
```

The same literal is committed in `.env:12`. The repo's own ADR-006 documents that no production
injection mechanism exists and that the value in the public repository grants ADMIN/Authority-equivalent
access.

**Impact:** Anyone with the public repo string can impersonate any gate-internal caller to the
Authority API if the network boundary is crossed. Secret rotation requires editing a committed
file and rebuilding.

**Remediation:** Move all three tokens to runtime env vars / a secrets manager (BuildKit secret
mount or Kubernetes Secret), fail closed on startup when unset, rotate immediately, and add
`constants.ini` (or at least the secret lines) to `.gitignore` with a `.example` template.

### F-04 (High) — `/xroad/**` trusts an ordinary header; isolation is deployment-only

`DSL/Ruuter/xroad/.guard.yml` accepts `X-Road-Client` as the credential without cryptographic
validation, relying on the Security Server having authenticated the caller. The file's own
comment (lines 15-21) states that anyone reaching `/xroad/**` directly can claim any registry
code and impersonate any registered authority. The project shares **port 8086** with the public
gate API. The only controls are nginx (which does not proxy `/xroad/**`, good) and the operator's
ingress/NetworkPolicy.

**Impact:** A single ingress misconfiguration yields full authority impersonation, subset
bypass, and access to dataset/follow-up/search.

**Remediation:** Enforce the boundary in code where possible: separate listener/port for the
X-Road adapter, mTLS or a signed header from the Security Server, and an explicit startup check
refusing to bind the X-Road routes to a publicly exposed interface. Add a CI assertion that the
ingress config does not route `/xroad/**`.

### F-05 (High) — Kotlin service endpoints have no application-layer authentication

`EDeliveryRoutes` (`/msh`), `InternalRoutes` (`/api/v1/send/:partyId`, `/api/v1/ping/:partyId`),
`MultiplexerRoutes` (`/search/:searchId`, `/rest/:searchId`), and all xml-mapper routes perform
no token/header authorization. `/services/msh` is reachable through the UI nginx
(`docker/ui/nginx.conf:66-72`).

**Impact:** Any client that can reach these services (or reaches them via the proxied `/services/msh`)
can trigger arbitrary gate fan-out, send AS4 messages, poll other callers' searches, or exercise
the AS4 parser.

**Remediation:** Add the shared internal-service token check (constant-time) to every internal
Kotlin route, or bind them to an internal-only interface/network with a NetworkPolicy. Do not
proxy `/services/msh` from the public UI ingress.

### F-15 (Medium) — Multiplexer polling key is shared, no ownership check

`GET /rest/:searchId` (`MultiplexerRoutes.kt:64-78`) returns whatever responses are cached under
the caller-supplied UUID with no check that the requester started that search. `X-Road-Id` is
caller-controlled (ADR-006). Bounded by the 90 s cache TTL and metadata-only impact, but it is a
cross-authority information leak.

**Remediation:** Bind the search key to the authenticated caller identity and reject mismatches;
use an unguessable per-caller token rather than a caller-supplied id.

### F-14 (Medium) — Public diagnostics forward an arbitrary cookie to TIM

`efti/GET/api/v1/test/{lubatud,piiratud}.yml` are public via `override_ancestors` and forward
`cookie: ${incoming.headers.cookie}` to TIM `/jwt/userinfo`, returning the claims body. This is a
token-validity oracle and a potential SSRF/credential-relay surface into the identity service.

**Remediation:** Restrict these to non-production or require the internal service token; validate
and length-cap the cookie; never echo the identity provider response body.

### F-22 (Medium) — `mock-platform` unguarded

`DSL/Ruuter/mock-platform/**` has no project `.guard.yml`, compares a hardcoded
`mock-secret-key` inline, and declares no `allowlist`/`validate_input`. It is a dev/mock surface
but ships in the same DSL tree.

**Remediation:** Add a project guard and input declarations, or exclude the project from
production builds.

---

## A02 — Cryptographic Failures

### F-02 (Critical) — Private key and keystore committed to git — **ACCEPTED** (dev-only key)

> **Accepted — dev-only key** (`c568bc2`). `code/certs/` is an intentional development fixture:
> it lets local and CI runs work out of the box and is **never meant to be used in production**.
> It is no longer baked into any image (`docker/code/Dockerfile` dropped `COPY certs certs` /
> `chmod`) and is excluded from the build context by `code/.dockerignore`; `edelivery` receives
> its keystore at runtime through `KEYSTORE_DIR` (`./code/certs` locally, so a production
> deployment points that mount at a per-environment Secret/HSM and the repository key goes
> unused). `code/certs/generate.sh` now honours `KEYSTORE_PASSWORD`, so the same plumbing can
> serve a non-dev key. The residual dev defaults (`changeit`, and the base64 test key embedded in
> `KeyManager.kt`) are likewise not for production.

```text
code/certs/own.key    # unencrypted 2048-bit RSA private key
code/certs/own.p12    # PKCS#12, password "changeit"
code/certs/own.crt    # self-signed CN=EU-EE
```

`git ls-files` confirms all are tracked and, by explicit choice, `.gitignore` does not exclude
them — this is a development convenience, not a production secret. The keystore is no longer
copied into the edelivery, xml-mapper, multiplexer, or pubsub images; it is mounted into
`edelivery` at runtime. Production is required to supply its own keystore and password
externally. `KeyManager.kt:17-18` still defaults `KEYSTORE_PASSWORD` to `changeit` when unset, and
`KeyManager.kt:22-24` embeds a base64 PKCS#12 blob in source for tests.

**Impact (conditional).** If a production deployment ever resolved its keystore from this
repository, the AS4 signing/decryption key would be public — an attacker could decrypt captured
AS4 traffic, forge signatures attributed to `EU-EE`, and impersonate the gate in G2G exchanges.
Correct per-environment secret injection removes the impact; accidentally using the repo key
restores it.

**Remediation:** Ensure production never resolves the keystore from the repository — supply a
unique per-environment key via mounted Secret/HSM with a strong `KEYSTORE_PASSWORD`, and fail the
service closed when no production key is configured. Keep the repo key documented and clearly
non-production (ideally gate its use behind a dev/test flag). Rotating or purging the committed
key is only necessary if a deployed environment has actually used it.

### F-07 (High) — AS4 verification skips validity, revocation, and sender binding — **PARTIALLY FIXED** (`2edac04`)

> **Partially fixed.** `KeyManager.receiverCert` (`:47-55`) now calls `cert.checkValidity()` on
> every lookup and fails closed with a `SecurityException` for an expired receiver certificate
> (unit-tested in `KeyManagerTest.rejectsExpiredReceiverCert`). Still open: no CRL/OCSP
> revocation check, and the signing certificate's subject is still not bound to
> `header.senderId`.

`SignatureVerifier.verify` uses the registry cert for the sender (`KeyManager.kt:46-48`) and
enforces Exclusive-C14N / RSA-SHA256 / SHA-256 — all good — but never calls
`X509Certificate.checkValidity()`, performs no CRL/OCSP check, and never verifies that the
signing certificate's subject actually matches `header.senderId`. `String.toX509()` merely parses
the certificate.

**Impact:** Expired or revoked gate certificates remain trusted; a registry entry with a mismatched
cert could sign as another party.

**Remediation:** Validate validity on every verification (fail closed), implement OCSP/CRL with
the spec's fail-closed posture, and assert `senderId` equals the registry-resolved identity before
verifying the signature.

### F-16 (Medium) — API keys stored as unsalted SHA-256

`get_platform_by_api_key.sql:13` matches `api_key_hash = digest(:apiKey,'sha256')` and
`generate_platform_api_key.sql` generates 24 random bytes. Unsalted SHA-256 is acceptable here
because keys are high-entropy and random (not passwords), but the comparison is not constant-time
and the DB contains no per-key salt. The design returns plaintext exactly once — good.

**Remediation:** Prefer HMAC-SHA-256 with a server-side pepper, or a constant-time comparison in
application code; document the entropy assumption.

### F-23 (Medium) — Decryption accepts unexpected algorithms

`EDeliveryRoutes.kt:103-106` logs a warning but proceeds when `keyEncryptionAlgorithm` is not
RSA-OAEP or `dataEncryptionAlgorithm` is not AES-128-GCM. The spec pins these suites.

**Remediation:** Reject non-conforming algorithm identifiers with an AS4 fault instead of warning.

---

## A03 — Injection

### F-01 (Critical) — XXE in inbound AS4 signature verification — **FIXED** (`def7ae7`)

> **Fixed.** `SignatureVerifier.kt:71-81` now hardens the `DocumentBuilderFactory` exactly as the
> remediation prescribed (`disallow-doctype-decl`, external general/parameter entities off,
> `load-external-dtd` off, `isXIncludeAware=false`, `isExpandEntityReferences=false`,
> `ACCESS_EXTERNAL_DTD/SCHEMA=""`). Residual: the `URIDereferencer` (`:105-108`) still delegates
> non-`cid:message` references to the default dereferencer, so a crafted `<ds:Reference URI="…">`
> could still trigger an outbound fetch — lower severity than the general-entity XXE, but worth
> closing. The description below is retained for the historical record.

`SignatureVerifier.kt:70` built a `DocumentBuilderFactory` with **no XXE hardening** and parsed
the raw, attacker-controlled AS4/SOAP XML at line 86:

```kotlin
70: private val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
...
86: val document = documentBuilderFactory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
```

The parse happens **before** signature validation and is reachable on the internet-facing
`/services/msh` route (proxied by `docker/ui/nginx.conf:66-72`). JDK defaults leave DOCTYPE and
external entity resolution enabled. None of the spec-mandated controls
(`docs/specs/data-transformations.md:456-461`: `disallow-doctype-decl`, external entity features,
`ACCESS_EXTERNAL_DTD/SCHEMA`, `setXIncludeAware(false)`) are set. The custom `URIDereferencer`
(lines 94-97) also delegates non-`cid:message` references to the default dereferencer, which can
resolve external URIs declared in `<ds:Reference URI="...">`.

**Impact:** Arbitrary file disclosure from the JVM filesystem, SSRF to internal services
(ReSql/TIM/cloud metadata), and billion-laughs/exponential-entity DoS — all pre-authentication.

**Remediation:**

```kotlin
DocumentBuilderFactory.newInstance().apply {
  isNamespaceAware = true
  setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
  setFeature("http://xml.org/sax/features/external-general-entities", false)
  setFeature("http://xml.org/sax/features/external-parameter-entities", false)
  setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
  isXIncludeAware = false
  isExpandEntityReferences = false
  setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
  setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
}
```

Also restrict the `URIDereferencer` to `cid:message` and same-document references only, and
enforce the 10 MB body / depth / parse-time limits from the spec.

### F-08 (High) — User input interpolated into outbound URLs

Ruuter DSL builds URLs by string interpolation from request body/path values:

```yaml
# DSL/Ruuter/efti/POST/api/v1/dataset-local.yml:33
url: "${platform_result.response.body[0]?.baseUrl}/v1/dataset/${incoming.body.uil.datasetId}?subsets=${incoming.body.subsets.join(',')}"

# DSL/Ruuter/efti/POST/api/v1/follow-up-local.yml:78
url: "${platform_result.response.body[0]?.baseUrl}/v1/dataset/${incoming.body.uil.datasetId}/follow-up"

# DSL/Ruuter/efti/POST/api/v1/authority/dataset.yml:78 (and follow-up.yml:74)
url: "[#EDELIVERY_URL]/api/v1/send/${incoming.body.uil.gateId}"
```

`uil.datasetId`, `uil.gateId`, `subsets`, and the registered platform `baseUrl` (admin-settable,
validated for presence only) are not format-checked. With
`internal_requests.block_private_networks: false` (`ruuter.yaml:32`), a crafted value can redirect
the call to an internal host or inject path/query segments (SSRF / path traversal). The
`authority/search.yml:49,173` routes interpolate the caller-supplied `x-request-id` into the
multiplexer URL.

**Impact:** Internal network reachability, request forgery, and cross-route invocation. Mitigated
only by the internal-token guard on the authority surface.

**Remediation:** Validate `datasetId`/`gateId` as UUIDs, `subsets` against a fixed vocabulary, and
platform `baseUrl` as an `https://` URL with a host allowlist at registration time. Use structured
URL construction rather than string interpolation, and keep `block_private_networks: true` with an
explicit allowlist of required internal hosts.

### F-17 (Medium) — XML injection via unescaped interpolation

Generated XML interpolates raw values without escaping:
`code/xml-mapper/src/efti/xml/fti/FTIMessages.kt:167,178` and
`ParameterSearchCriteria.kt:53-102`; `EDeliveryMessageGenerator.soapFault` injects exception text
(`:302,309`). Only `IncludedNote.render` escapes via klite's `+content`.

**Impact:** An attacker-controlled identifier containing `<`/`&` can break the outbound document
structure or inject elements into AS4 payloads.

**Remediation:** Escape all interpolated values (`&`, `<`, `>`, `"`, `'`) or build documents with
a proper XML writer.

### F-25 (Low) — Regex-based XML parsing / backtracking

`code/edelivery/src/edelivery/Xml.kt:22-35` and klite `XmlStrings.extractXmlTag` use
`.*?` with `DOT_MATCHES_ALL` against large user XML, which can exhibit super-linear
backtracking. `DatasetRoutes.kt:39` also sniffs content with `req.xml.contains(...)`.

**Remediation:** Prefer the StAX parser for extraction; bound input size and time.

**Note (positive):** all 51 ReSql query files use bound `:name` parameters — no SQL injection
found. The only dynamic SQL (`protect_registry_append()`) uses catalog identifiers with `%I` and
bind parameters.

---

## A04 — Insecure Design

### F-09 (High) — No request-size or parse limits (DoS)

`EDeliveryRoutes.kt:43` reads the entire request stream with `e.requestStream.readBytes()` and
klite imposes no body cap. The spec requires a 10 MB body limit, max nesting depth 20, and a 5 s
parse timeout (`docs/specs/data-transformations.md:461`); none are implemented. Combined with the
XXE (F-01) and regex parsing (F-25), a single request can exhaust the JVM heap.

**Remediation:** Enforce a streaming byte cap before buffering, set parser entity-expansion and
depth limits, and add a parse timeout.

### F-18 (Medium) — Rate limiting not implemented in-repo

The spec mandates 100 req/min/IP at the reverse-proxy layer with `429` + `Retry-After`, but no
nginx `limit_req` or gateway limiter exists in this repository. It is a deployment dependency
with no in-repo enforcement or test.

**Remediation:** Implement `limit_req_zone` in nginx (or the ingress) and add an E2E assertion for
the 429 contract.

### F-21 (Medium) — No audit writer for X-Road/authority channels

ADR-006 records that nothing writes `audit_log` for the X-Road and Authority channels, and that
`X-Road-UserId` is intended for GDPR Art. 30 but unused. This is a compliance (and
non-repudiation) gap for exactly the highest-privilege data-access paths.

**Remediation:** Add audit writes for `identifier.search`, `dataset.deliver`, and `followup.send`
on the X-Road and service-token paths.

### F-27 (Low) — Subset enforcement and vocabulary gaps

`FORBIDDEN_SUBSET` is not enforceable on the JWT path (`users` has no subset/authority link), and
four incompatible subset vocabularies exist (openapi/UI `EU01..EU07`, XSD, xml-mapper
`CC*`/`full`/`identifier`, UI-local). `FORBIDDEN_MULTI_AUTHORITY` has no dedicated error code.

**Remediation:** Agree one vocabulary, enforce it in the schema and mapper, and close the JWT-path
enforcement gap or document it as out of scope.

---

## A05 — Security Misconfiguration

### F-10 (High) — Tracked secrets and dev defaults in production-capable config

- `.env` is tracked (contains `INTERNAL_SERVICE_TOKEN`); `.gitignore` only excludes `env.local`.
- `compose.yml`: `POSTGRES_PASSWORD=01234` (lines 11, 35), `RESQL_EFTI_PASSWORD=01234` (71),
  `TIM_ADMIN_TOKEN=dev-tim-admin-token` (223), `TIM_TARA_CLIENT_SECRET=efti-secret` (226),
  `AUDIT_SALT` default (58).
- `DSL/Liquibase/liquibase.properties:3` hardcodes `password: 01234`; `:8` sets
  `liquibase.contexts: dev`, which seeds the publicly-known `mock-secret-key` platform if left on.
- `DSL/Liquibase/init.sql:14-15` creates `app`/`db_archiver` with hardcoded passwords.

The repo documents these as dev-only, but `pikker-deploy.sh` ships `compose.yml` unchanged and
applies no secret injection.

**Remediation:** Externalize every credential, remove dev defaults from the production compose
path, set `liquibase.contexts` explicitly per environment, and add a pre-deploy check that fails
on known dev values.

### F-11 (Medium) — JDWP debug agents on all interfaces

`compose.override.yml:40-56` publishes `5051/5052/5053` and sets
`-agentlib:jdwp=...address=*:5051`. JDWP is unauthenticated and yields remote code execution.

**Remediation:** Bind debug ports to `127.0.0.1`, or drop JDWP from the override entirely. Never
merge the override into a reachable environment.

### F-12 (Medium) — `tim-database` trust auth exposed

`compose.yml:208` sets `POSTGRES_HOST_AUTH_METHOD=trust`; `compose.override.yml:74` publishes
`19433:5432` on all interfaces. Any reachable client becomes the `tim` superuser.

**Remediation:** Use password auth, bind the port to loopback, and treat the TIM database as
internal-only.

### F-13 (Medium) — Missing HTTP security headers

`docker/ui/nginx.conf` sets no `Content-Security-Policy`, `Strict-Transport-Security`,
`X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, or `Permissions-Policy`; it serves
plain HTTP. `/tara/` uses `proxy_ssl_verify off` (line 80). The UI stores the JWT in
`sessionStorage`, so an XSS would directly expose the bearer token.

**Remediation:** Add the header set, terminate TLS upstream and enforce HSTS, enable upstream TLS
verification outside the mock, and implement a CSP for the SPA.

### F-19 (Medium) — Permissive CORS

`resql.yaml:44-45` sets `cors.allowed_origins: "*"`; no CORS policy exists in nginx/ruuter. The
spec requires same-origin by default and never `*`.

**Remediation:** Set an explicit `ALLOWED_ORIGINS` and reject unknown origins.

### F-24 (Medium) — Supply-chain configuration

Base images use mutable tags (`golang:latest`, `node:22-alpine`, `nginx:stable-alpine`,
`postgres:18`); GitHub Actions use tags, not commit SHAs; the klite dependency is pinned to a
JitPack commit; Trivy scans only the `ui` image (SBOM covers all nine). Sonar and `npm audit` are
`allow_failure: true` / `|| true`.

**Remediation:** Pin digests and action SHAs, extend Trivy to all images, and make the security
gates blocking.

---

## A06 — Vulnerable and Outdated Components

- No dependency lockfiles or OWASP Dependency-Check in Gradle.
- `turnerrainer/ruuter:0.10.0-rc`, `resql:0.4.2-alpha`, `tim:0.4.0-alpha` are pre-release tags
  without digests.
- `docker/tara-mock/Dockerfile` builds from `golang:latest`; the `tara-mock/` submodule Dockerfile
  uses EOL `golang:1.15.1-alpine`.
- PostgreSQL JDBC `42.7.13` and JUnit 6.0.3 are current; no Jackson/BouncyCastle/OkHttp to audit.

**Remediation:** Introduce a dependency-verification task, pin image digests, and schedule
automated dependency updates. (See F-24.)

---

## A07 — Identification and Authentication Failures

- **F-03** (internal service token) and **F-04** (X-Road header trust) are the dominant
  authentication weaknesses — see A01.
- **Break-glass** local admin is bcrypt (cost 12) and default-disabled — correct.
- **Revocation** is sound in design: per-token `sessions` denylist plus per-user
  `token_revoked_at`, enforced before audit writes.
- **`jti` provenance** is correctly taken from the token payload (not signature) and only that
  segment reaches the DB.
- **Dev-login** returns 404 unless `DEV_LOGIN_ENABLED=true` (build default false;
  `compose.override.yml:17` enables it locally). Ensure the override is never deployed.
- **TARA mock** accepts any `client_id` (submodule README) — dev-only, but confirm it cannot be
  reached in production (`compose.pikker.yml` still sets `VITE_USE_PROD_TARA_URL=false`).

**Remediation:** Add constant-time comparison for all static tokens, rotate the dev tokens, and
verify production builds pin `DEV_LOGIN_ENABLED=false` and a real TARA endpoint.

---

## A08 — Software and Data Integrity Failures

- **F-07** (AS4 cert validity/revocation/sender binding) and **F-23** (algorithm downgrade) are
  the integrity findings.
- **No Java native deserialization** (`ObjectInputStream`/`Serializable`) anywhere.
- **No polymorphic JSON typing** (no Jackson `defaultTyping`, no `@JsonTypeInfo`).
- **No YAML/XML object deserialization** frameworks.
- AS4 trust key is resolved from the registry, not the embedded `KeyInfo` — a sender cannot supply
  its own verification key.
- Outbound HTTP uses Java `HttpClient` default `Redirect.NEVER` — no redirect-following.

**Remediation:** See F-07/F-23; also add an SBOM verification step that fails on unsigned/unpinned
artifacts.

---

## A09 — Security Logging and Monitoring Failures

### F-20 (Medium) — Sensitive data in logs

`EDeliveryRoutes.kt:78` logs `bodyBytes.decodeToString()` (the raw inbound AS4 body, which may
include decrypted payload material) at error level. The spec's redaction policy covers
`Authorization`, `X-API-Key`, cert subjects, JWT, and Basic credentials; raw AS4 bodies are not
covered by that policy.

**Remediation:** Remove the raw-body log, log only length/hash/conversation id, and extend the
redaction policy to AS4 bodies.

### F-21 (Medium) — Missing audit writes

See A04. The highest-value data accesses on the X-Road/authority paths currently produce no
`audit_log` row.

**Remediation:** Add the audit writer and an E2E assertion that each path emits an audit row.

**Positive:** request/response body logging is disabled in `ruuter.yaml` (lines 42-43); the audit
table is append-only with an INSERT-only grant; audit-write failure does not roll back the
triggering operation (tested).

---

## A10 — Server-Side Request Forgery (SSRF)

### F-08 (High) — User-influenced outbound URLs

Detailed in A03. The registry-driven URLs (`platforms.base_url`, `gates.e_delivery_url`) are
admin-settable with presence-only validation and are fetched by edelivery/multiplexer/DSL. A
compromised or malicious admin, or a poisoned registry row, becomes an SSRF pivot because
`internal_requests.block_private_networks` is `false` on the main Ruuter
(`ruuter.yaml:32`). The public mock correctly keeps it `true`.

**Remediation:** Validate registry URLs at write time (scheme + host allowlist), and set
`block_private_networks: true` on the main listener with explicit internal host exceptions for
TIM/ReSql. Add egress filtering at the network layer.

---

## Prioritized remediation roadmap

**Immediate (P0, before any production traffic)**

1. **F-01** — ~~Harden the AS4 `DocumentBuilderFactory` (XXE) and restrict the `URIDereferencer`.~~
   **Fixed** (`def7ae7`); only the `URIDereferencer` restriction remains.
2. **F-02** — ~~baked into images~~ **Image embedding fixed** (`c568bc2`; keystore now mounted at
   runtime and excluded from the build context). Still open: revoke and rotate the committed AS4
   private key/keystore, purge it from git history, and move to per-environment secret delivery.
3. **F-03** — Externalize `INTERNAL_SERVICE_TOKEN`, `ARCHIVE_OPS_TOKEN`, `TIM_ADMIN_TOKEN`; fail
   closed when unset; rotate.
4. **F-10 / F-11 / F-12** — Remove tracked secrets and dev defaults from the production path;
   ensure `compose.override.yml` is never deployed (JDWP RCE, trust auth, dev-login).

**Short term (P1)**

5. **F-04** — Enforce the `/xroad/**` network boundary in code and CI, not just ingress docs.
6. **F-05 / F-15** — Authenticate internal Kotlin endpoints; add ownership checks to multiplexer
   polling.
7. **F-07 / F-23** — Add certificate validity/revocation/sender-binding and reject non-conforming
   AS4 algorithms. *(F-07 validity check done in `2edac04`; revocation, sender binding and F-23
   still open.)*
8. **F-06** — Point ReSql at the least-privilege `app`/`db_archiver` roles so append-only is
   enforced at runtime.
9. **F-08 / F-19** — Validate/allowlist registry and user-controlled URLs; lock down CORS.
10. **F-09 / F-18** — Enforce body-size/parse limits and proxy-layer rate limiting.

**Medium term (P2)**

11. **F-13 / F-24** — Security headers, TLS, pinned images/actions, full-image Trivy, blocking
    security gates.
12. **F-17 / F-20 / F-21 / F-25 / F-26 / F-27** — Output escaping, log redaction, audit writes,
    parser hardening, root `.dockerignore` (`code/.dockerignore` now added), subset-vocabulary
    unification.
13. **F-14 / F-16 / F-22** — Restrict diagnostics, harden key storage/comparison, guard the mock
    project.

---

## Appendix — Verification commands

```sh
# Secrets tracked in git
git ls-files | grep -E '(\.env$|own\.(key|p12|crt)|\.pem$)'

# AS4 keystore no longer baked into images (expect no output)
grep -n 'COPY certs' docker/code/Dockerfile || echo "not baked"

# SQL interpolation should be empty
grep -rE '\$\{|\{\{|EXECUTE |format\(' DSL/Resql/ || echo "clean"

# Unhardened XML parser
grep -n 'DocumentBuilderFactory' code/edelivery/src/edelivery/SignatureVerifier.kt

# Compose exposure / debug agents
grep -nE 'ports:|5051|5052|5053|POSTGRES_HOST_AUTH_METHOD' compose.yml compose.override.yml

# Missing security headers
grep -n 'add_header\|Strict-Transport\|Content-Security' docker/ui/nginx.conf || echo "none"
```

*Report generated by static analysis of the repository at `b9a49fa`. Findings should be
validated against the actual deployment topology, which may supply compensating controls
(ingress rules, NetworkPolicies, secret injection) not present in this repository.*
