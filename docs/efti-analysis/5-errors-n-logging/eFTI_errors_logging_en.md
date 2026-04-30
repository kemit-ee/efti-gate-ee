# Error Handling and Logging Specification

| | |
|---|---|
| **Author** | Sten Viljus |
| **Company** | Askend Estonia OÜ |
| **Contact** | sten.viljus@askend.com |

> **v2.0 spetsifikatsioonid:**
> - Logimine: [`../../specs/logging-spec.md`](../../specs/logging-spec.md) — ECS 8.x JSON logivormingu täielik kirjeldus
> - Veakoodid: [`../../specs/errors.json`](../../specs/errors.json) — RFC 7807 struktureeritud veakataloog

## 1. Error Code List

eFTI Gate PoC uses Klite framework's standard HTTP exception classes. In the current PoC, separate business error codes **are not implemented** — errors are returned via HTTP status codes and free-text messages.

> **v2.0 change:** `specs/errors.json` defines a structured error catalog with machine-readable codes
> (`INVALID_XML`, `DUPLICATE_REQUEST_ID`, `GATE_OFFLINE`, etc.) and RFC 7807 `application/problem+json`
> response format. See §3.2 below for the target format.

### 1.1 Klite Exception Classes and Their HTTP Mapping

| Exception Class | HTTP Status Code | When It Occurs |
|----------------|-----------------|----------------|
| `UnauthorizedException` | 401 | Unauthenticated request — `Authorization` header is missing or invalid |
| `ForbiddenException` | 403 | Authenticated user lacks the required role or access to the resource |
| `BadRequestException` | 400 | Invalid input — XML parsing error, duplicate request ID |
| `StatusCodeException(BadGateway)` | 502 | Target gate is offline or platform is not responding |
| `StatusCodeException(InternalServerError)` | 500 | Internal error — e.g. eDelivery connection interruption |
| `NoSuchElementException` | 500 | User not found (UserRepository) |
| `IllegalStateException` (via `error()`) | 500 | Unknown gate, platform or authority ID; missing annotation |
| `IllegalArgumentException` (via `require()`) | 500 | Unauthorized user deletion; subsets do not match authority's |

### 1.2 Business Error Situations

| # | Error Situation | Component | Exception | HTTP Code | Message | Resolution |
|---|----------------|-----------|-----------|-----------|---------|------------|
| 1 | Duplicate request ID | `RequestIdValidator` | `BadRequestException` | 400 | `Request Id 'X' already used` | Generate a unique `X-Request-ID` header for each request (UUID). A duplicate means the same request ID was sent twice within 10 minutes |
| 2 | Invalid identifier XML | `EftiService.saveIdentifiers()` | `BadRequestException` | 400 | `Error parsing identifiers: <parsing error>` | Check XML conformance to the eFTI common dataset XSD schema. The error message contains the specific parsing error (e.g. missing element, wrong type) |
| 3 | User lacks platform role | `PlatformRoutes.before()` | `UnauthorizedException` | 401 | `User has no platform access` | The user must be assigned a PLATFORM role with a platform Party ID via the Admin UI |
| 4 | User has multiple platforms | `PlatformRoutes.before()` | `UnauthorizedException` | 401 | `User has more than one platform registered...` | The identifier registration API requires exactly one platform role. Create a separate user for each platform or use the Admin API |
| 5 | Invalid Basic/Bearer token | `AccessChecker.before()` | `ForbiddenException` | 403 | `Invalid authorization (must be valid Basic or Bearer token)` | Check the `Authorization` header format: Basic → `base64(email:password)`, Bearer → `base64(userId:password)`. Ensure the user exists and the password is correct |
| 6 | Missing role | `AccessChecker.checkAccess()` | `ForbiddenException` | 403 | (empty message) | The user lacks the required role for the endpoint. Check user roles in the Admin UI and add the correct role if needed |
| 7 | Missing write access | `User.checkWriteAccess()` | `ForbiddenException` | 403 | `No access to <entityId>` | The user is trying to modify a resource outside their roles. Ensure the user's `roles` contain the Party ID of the resource being modified |
| 8 | Admin cannot assign higher roles | `UserAdminRoutes.ensureAllowedRoles()` | `ForbiddenException` | 403 | (empty message) | A regular Admin can only create users with their own roles. Use the Super Admin account to assign higher roles |
| 9 | Unknown gate ID | `GateRegistry.get()` | `IllegalStateException` | 500 | `Unknown gate: <id>` | Check the gate ID. The gate must be pre-registered via the Admin UI. Ensure the ID spelling is correct |
| 10 | Unknown platform ID | `PlatformRegistry.get()` | `IllegalStateException` | 500 | `No platform with id: <id>` | Check the platform ID. The platform must be pre-registered via the Admin UI |
| 11 | Unknown authority ID | `AuthorityRegistry.get()` | `IllegalStateException` | 500 | `No authority with id <id>` | Check the authority ID. The authority must be pre-registered via the Admin UI |
| 12 | Gate offline | `EftiService.checkGateAvailable()` | `StatusCodeException(502)` | 502 | `Cannot reach Gate <id>: <status>` | The target gate is not reachable. Check the gate status in the Admin UI. Wait until the gate returns to ONLINE status (GatePingJob checks automatically) |
| 13 | Platform ping fails | `PlatformClient.ping()` | `StatusCodeException` | platform code | `Ping failed, code <code>` | The platform health check failed. Check the platform URL and network connectivity. The HTTP code indicates the specific error on the platform side |
| 14 | Gate ping fails (connection) | `GateClient.ping()` | `StatusCodeException(500)` | 500 | `Could not connect to URL` | The target gate URL is not reachable. Check the URL, DNS, and firewall rules. Ensure the TLS certificate is valid |
| 15 | Gate ping fails (HTTP) | `GateClient.ping()` | `StatusCodeException` | gate code | `Ping failed, code <code>` | The target gate responded with an error code. Check the gate logs for the specific error |
| 16 | Platform connection interrupted | `PlatformClient.sendRequest()` | (caught) | 502 | Exception message | The connection to the platform was interrupted during the request. Check platform availability and timeout settings. Retry |
| 17 | Follow-up wrong gate | `EftiService.handlePostFollowUpRequest()` | `IllegalStateException` | 500 | `Follow up gateId does not match this gate Ids` | The follow-up message `gateId` does not match this gate. Check the UIL (gateId/platformId/datasetId) — the follow-up must be sent to the correct gate |
| 18 | User not found | `UserRepository.save()` | `NoSuchElementException` | 500 | `User not found: <id>` | The user UUID does not exist in the database. The user may have been deleted concurrently. Reload the user list |
| 19 | User deletion forbidden | `UserAdminRoutes.deleteUser()` | `IllegalArgumentException` | 500 | `Not allowed to delete that user` | Admin cannot delete themselves or users outside their role scope |
| 20 | Subsets don't match authority's | `UserAdminRoutes.checkAuthorityUserSubsets()` | `IllegalArgumentException` | 500 | `Subsets must match Authority's subsets` | The subsets assigned to the user must be a subset of the Authority's own subsets. Check the Authority's subset configuration |
| 21 | eDelivery message processing error | `EDeliveryRoutes.msh()` | (caught) | 500 | SOAP Fault XML | An error occurred while processing the eDelivery AS4 message. Check the sender's certificate, message format, and encryption. The SOAP Fault contains the specific error |
| 22 | Wrong KeyIdentifier | `EDeliveryRoutes.decryptPayload()` | `IllegalArgumentException` | 500 | `Invalid KeyIdentifier "<x>", expected "<y>"` | The sender encrypted the message with the wrong certificate. The sender must use the recipient's valid eDelivery certificate (SKI must match) |

### 1.3 Notes

- **`IllegalStateException` and `IllegalArgumentException` return 500** — these are Kotlin `error()` and `require()` throws. In production, some should be `BadRequestException` (400) or `NotFoundException` (404), not 500.
  - For example, `Unknown gate: <id>` and `No platform with id: <id>` should return **404**, not 500.
  - `Subsets must match Authority's subsets` and `Not allowed to delete that user` should return **400** or **403**, not 500.
- **eDelivery errors are returned as SOAP Fault XML**, not JSON — this is an eDelivery AS4 protocol requirement.
- **TODO in code:** `EftiService.checkGateAvailable()` — `// TODO: in XML api, render errors either as plain text or xml`.

---

## 2. HTTP Statuses by Endpoint

### 2.1 eFTI REST API (`/v1`)

| Endpoint | Method | Success | Unauthenticated | Forbidden | Invalid Input | Target Offline | Internal Error |
|----------|--------|---------|-----------------|-----------|---------------|---------------|----------------|
| `/v1/identifiers/:identifier` | GET | 200 | 401 | 403 | 400¹ | — | 500 |
| `/v1/dataset/:gateId/:platformId/:datasetId` | GET | platform code² | 401 | 403 | 400¹ | 502 | 500 |
| `/v1/follow-up/:gateId/:platformId/:datasetId/:datasetRequestId` | POST | 200 | 401 | 403 | 400¹ | 502 | 500 |
| `/v1/consignments/identifier/:datasetId` | POST | 200 | 401 | 403 | 400 | — | 500 |

¹ Duplicate request ID (`RequestIdValidator`)
² For dataset queries, the platform's response status code is transparently returned — typically 200 for successful queries

### 2.2 Admin API (`/api`)

| Endpoint | Method | Success | Unauthenticated | Forbidden |
|----------|--------|---------|-----------------|-----------|
| `/api/user` | GET | 200 | 401 | 403 |
| `/api/switch` | GET | 200 / 401³ | 401 | — |
| `/api/gates` | GET | 200 | 401 | 403 |
| `/api/gates` | POST | 200 | 401 | 403 |
| `/api/gates/:gateId` | DELETE | 200 | 401 | 403 |
| `/api/gates/:gateId/ping` | POST | 200 | 401 | 403⁴ |
| `/api/platforms` | GET | 200 | 401 | 403 |
| `/api/platforms` | POST | 200 | 401 | 403 |
| `/api/platforms/:platformId` | DELETE | 200 | 401 | 403 |
| `/api/platforms/:platformId/ping` | POST | 200 | 401 | 403⁴ |
| `/api/authorities` | GET | 200 | 401 | 403 |
| `/api/authorities/:authorityId` | GET | 200 | 401 | 403 |
| `/api/authorities` | POST | 200 | 401 | 403 |
| `/api/authorities/:authorityId` | DELETE | 200 | 401 | 403 |
| `/api/users` | GET | 200 | 401 | 403 |
| `/api/users` | POST | 200⁵ | 401 | 403 |
| `/api/users/:userId` | DELETE | 200 | 401 | 403 |
| `/api/consignments` | GET | 200 | 401 | 403 |
| `/api/consignments/:datasetId` | DELETE | 200 | 401 | 403 |

³ `AdminAuthRoutes.userSwitch()` — deliberately uses 401 for user switching (Basic Auth re-prompt)
⁴ Ping may return 500 (connection failure) or 502 (gate/platform offline)
⁵ If `generateSecret=true`, returns the generated secret (`base64(id:password)` or plain password)

### 2.3 eDelivery Endpoints (`/services`)

| Endpoint | Method | Success | Error |
|----------|--------|---------|-------|
| `/services/msh` | GET | 200 | — |
| `/services/msh` | POST | 200 + SOAP response | 500 + SOAP Fault |
| `/services/fast` | POST | 200 + XML response | 500 |

### 2.4 Other Endpoints

| Endpoint | Method | Success |
|----------|--------|---------|
| `/health` | GET | 200 (`OK`) |
| `/metrics` | GET | 200 (JSON metrics) |

---

## 3. Error Message Format

### 3.1 Current Format (PoC)

The gate **lacks a unified error format standard**. Error messages are returned in different formats depending on the context:

**REST API errors — plain text:**
```
HTTP 400
Request Id 'abc-123' already used
```

```
HTTP 403
Invalid authorization (must be valid Basic or Bearer token)
```

```
HTTP 502
Cannot reach Gate gate-fi1: OFFLINE
```

**eDelivery errors — SOAP Fault XML:**
```xml
HTTP 500
<env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
  <env:Header/>
  <env:Body>
    <env:Fault>
      <env:Code><env:Value>env:Receiver</env:Value></env:Code>
      <env:Reason><env:Text xml:lang="en">Failed to process eDelivery message: <error message></env:Text></env:Reason>
    </env:Fault>
  </env:Body>
</env:Envelope>
```

**Dataset response errors — XML wrapper:**
```xml
<uilResponse xmlns="http://efti.eu/v1/edelivery" requestId="abc-123" status="502">
  <description>Cannot reach Gate gate-fi1: OFFLINE</description>
</uilResponse>
```

### 3.2 v2.0 Target: RFC 7807 `application/problem+json`

In v2.0, all REST API errors use the [RFC 7807 Problem Details](https://datatracker.ietf.org/doc/html/rfc7807) format.
The full catalog is in [`../../specs/errors.json`](../../specs/errors.json).

```json
{
  "type": "https://efti.eu/errors/invalid-xml",
  "title": "Invalid XML",
  "status": 400,
  "detail": "Error parsing identifiers: element 'mainCarriageTransportMovement' is not closed",
  "instance": "/v1/identifiers/550e8400-e29b-41d4-a716-446655440000",
  "errorCode": "INVALID_XML",
  "requestId": "550e8400-e29b/abc-123"
}
```

| Field | Description |
|-------|-------------|
| `type` | URI identifying the error type (stable, machine-readable) |
| `title` | Short human-readable summary |
| `status` | HTTP status code |
| `detail` | Specific error description for this occurrence |
| `instance` | Request path where the error occurred |
| `errorCode` | Machine-readable code from the error catalog (`INVALID_XML`, `DUPLICATE_REQUEST_ID`, etc.) |
| `requestId` | `X-Request-ID` value for log correlation |

**Error codes defined in v2.0 catalog:**

| Code | HTTP | Situation |
|------|------|-----------|
| `BAD_REQUEST_GENERAL` | 400 | Generic invalid input |
| `INVALID_XML` | 400 | XML parse error or XSD validation failure |
| `DUPLICATE_REQUEST_ID` | 400 | `X-Request-ID` already used within 10 minutes |
| `UNAUTHORIZED` | 401 | Missing or invalid `Authorization` header |
| `FORBIDDEN` | 403 | Valid credentials but insufficient role/access |
| `NOT_FOUND` | 404 | Unknown gate, platform, authority, or consignment ID |
| `GATE_OFFLINE` | 502 | Target gate is not reachable |
| `PLATFORM_UNAVAILABLE` | 502 | Platform not responding |
| `INTERNAL_ERROR` | 500 | Unhandled internal error |

> **NB:** `IllegalStateException` errors for unknown IDs (currently 500) are mapped to **404** in v2.0.
> `IllegalArgumentException` errors for invalid input (currently 500) are mapped to **400** or **403**.

eDelivery endpoints continue using the SOAP Fault format (AS4 protocol requirement).

---

## 4. Logging Rules

### 4.1 Logging Framework

- **SLF4J** via Klite wrapper (`klite.logger()`)
- **Log levels:** `INFO`, `WARN`, `ERROR`, `DEBUG`
- **Request ID:** Klite `UUIDRequestIdGenerator` generates in `internalId/externalRequestId` format. Request ID is in the thread name but **does not propagate to log messages via MDC**.

### 4.1b Mandatory Log Fields (Requirement)

According to KeMIT MFN (v1.2.0) and technical specification requirements, every log message must contain the following fields. Logs must be in **JSON format** according to the **Elastic Common Schema (ECS)** standard (see [ECS documentation](https://www.elastic.co/guide/en/ecs/current/index.html)).

| Field | ECS Field | Description | Current Status |
|-------|-----------|-------------|----------------|
| **Timestamp** | `@timestamp` | ISO 8601 UTC format | ✅ SLF4J adds automatically |
| **Log level** | `log.level` | DEBUG, INFO, WARN, ERROR | ✅ In use |
| **Request ID** | `trace.id` | `X-Request-ID` header / generated UUID, correlatable throughout the request lifecycle | ⚠️ Present in thread name but does not propagate to log messages via MDC |
| **Service** | `service.name` | Application name (e.g. `efti-gate`) | ❌ Missing, must be added to logback configuration |
| **User ID** | `user.id` | Authenticated user UUID | ❌ Missing from log messages |
| **User role** | `user.roles` | User roles (ADMIN, GATE, PLATFORM, AUTHORITY) | ❌ Missing from log messages |
| **Endpoint** | `url.path` | HTTP method + path (e.g. `GET /v1/identifiers/ABC-123`) | ✅ RequestLogger logs |
| **Message** | `message` | Log message content (in English) | ✅ All components |
| **Client IP** | `client.ip` | Request source IP | ❌ Missing |
| **Response code** | `http.response.status_code` | HTTP status code | ✅ RequestLogger logs |
| **Duration (ms)** | `event.duration` | Request processing time in milliseconds | ⚠️ Only RequestLogger and PlatformClient (REST) |

**References:**
- KeMIT MFN v1.2.0, chapter "Logging and monitoring": log messages in English, JSON format, ECS standard, associated with user and role, sensitive data excluded from logs
- KeMIT MFN v1.2.0, chapter "Observability": [https://wiki.kemit.ee/spaces/MFN/pages/289855884/Observability](https://wiki.kemit.ee/spaces/MFN/pages/289855884/Observability)
- Technical specification chapter 9.5: mandatory fields (timestamp, level, requestId, service, userId, endpoint, message), JSON format, request correlation

**Sensitive data prohibition** (KeMIT MFN + GDPR): logs **must not** contain passwords, tokens, personal identification codes, credit card numbers, or other sensitive personal data. This requirement is currently met.

### 4.2 Existing Logging Coverage by Component

| Component | File | Logs success | Logs errors | Logs duration | Logs destination |
|-----------|------|:---:|:---:|:---:|:---:|
| **RequestLogger** | `GateLauncher.kt` | ✅ | ✅ | ✅ | — (incoming) |
| **PlatformClient** (REST) | `PlatformClient.kt` | ✅ | ✅ | ✅ | ✅ |
| **PlatformClient** (eDelivery) | `PlatformClient.kt` | ❌ | ❌ | ❌ | ❌ |
| **GateClient** | `GateClient.kt` | ❌ | ✅ (ping only) | ❌ | ❌ |
| **EDeliveryClient** | `EDeliveryClient.kt` | ❌ | ❌ | ❌ | ❌ |
| **EDeliveryRoutes** | `EDeliveryRoutes.kt` | ❌ | ✅ | ❌ | — (incoming) |
| **GateMessageHandler** | `GateMessageHandler.kt` | ✅ | ✅ | ❌ | — (incoming) |
| **EftiService** | `EftiService.kt` | ❌ | ✅ (broadcast) | ❌ | ❌ |
| **AccessChecker** | `AccessChecker.kt` | ❌ | ✅ | ❌ | — |
| **GatePingJob** | `GatePingJob.kt` | ✅ | ✅ | ❌ | ❌ |
| **IdentifierExpirationJob** | `IdentifierExpirationJob.kt` | ✅ | — | — | — |
| **KeyManager** | `KeyManager.kt` | ✅ | — | — | — |
| **MultiNodeAsyncResponseProvider** | `MultiNodeAsyncResponseProvider.kt` | ✅ | — | ❌ | — |

### 4.3 Incoming Request Logging

**RequestLogger** (`GateLauncher.kt` line 39–41) logs every incoming HTTP request:

```kotlin
register<RequestLogger>(RequestLogger { ms ->
    "<" + attr<String?>("client") + "> " + defaultRequestLogFormatter(ms)
})
```

Format:
```
<client> METHOD /path - statusCode XXms
```

`client` attribute is set by:
- `AuthorityRoutes.before()` — authority ID (`authorityId` from role) or user email
- `PlatformRoutes.before()` — platform ID (first `PLATFORM` role)
- `EDeliveryRoutes.msh()` — eDelivery sender Party ID (parsed from XML)
- If not set — `null`

Example output:
```
<eu-authority-1> GET /v1/identifiers/ABC-123 - 200 12ms
<demo-platform> POST /v1/consignments/identifier/uuid - 200 8ms
<gate-fi1> POST /services/msh - 200 45ms
<null> GET /health - 200 1ms
```

### 4.4 Outgoing Request Logging

**PlatformClient** (REST) is the only component that properly logs outgoing requests:

```kotlin
private fun log(platform: Platform, request: HttpRequest, start: Long, response: HttpResponse<String>? = null, e: Exception? = null) =
    log.info("${platform.id}: ${request.method()} ${request.uri()} - ${response?.statusCode() ?: e?.toString()} ${currentTimeMillis() - start} ms")
```

Example output:
```
demo-platform: GET https://platform.example/v1/dataset/uuid?subsetId=... - 200 45 ms
demo-platform: GET https://platform.example/v1/dataset/uuid?subsetId=... - java.net.ConnectException: Connection refused 5003 ms
```

**GateClient** — logger is declared but only used for ping errors:
```kotlin
log.error("Could not ping gate", e)
```

Unlogged methods: `getIdentifiers()`, `getDataset()`, `postFollowUp()`, `sendAndReceive()`.

**EDeliveryClient** — logger is **not used**. Only metric counter `edelivery_messages_sent`.

### 4.5 eDelivery Message Reception

**EDeliveryRoutes** logs only errors and warnings:

| Situation | Level | Example |
|-----------|-------|---------|
| Unknown receiver | `WARN` | `Unknown receiver: gate-xx1` |
| Unknown key encryption method | `WARN` | `Unknown key encryption method: http://...` |
| Unknown data encryption method | `WARN` | `Unknown data encryption method: http://...` |
| Message processing error | `ERROR` | `Error when processing message: <error>. Raw content: <message>` |

**GateMessageHandler** logs every incoming eDelivery message type and sender:
```
Handling uilQuery from RequestKey(senderId=gate-fi1, requestId=abc-123, receiverId=eu-ee31).
```
Errors include the full XML payload.

### 4.6 Business Logic Logging

**EftiService** only logs broadcast errors:
```kotlin
log.warn("${gate.id} failed with $e for $q")
```

Not logged: `saveIdentifiers()`, `getDataset()` routing decision, `getIdentifiers()` broadcast start/summary, `sendFollowUp()` routing, `handleUilQuery()`, `handleIdentifierQuery()`, `handlePostFollowUpRequest()`.

### 4.7 Authentication

**AccessChecker** only logs failed authentications (`log.error` with exception). Successful authentications, authorization checks, and role validations **are not logged**.

### 4.8 Background Jobs

**GatePingJob:**
- Ping failure: `Gate ${gate.id} ping failed: ${e.message}`
- Status change: `Gate ${gate.id} status changed: ${gate.status} -> $newStatus`
- Updated gates count: `Updated status for $updatedCount gates`

**IdentifierExpirationJob:**
- Deleted records: `Removed $count expired identifiers`

### 4.9 Cryptography

**KeyManager** logs at startup:
- Own Party ID and certificate SKI
- Each certificate's name and SKI
- TrustStore build (default + custom certificate count)

### 4.10 Asynchronous Response Handling

**MultiNodeAsyncResponseProvider** (`MultiNodeAsyncResponseProvider.kt`) logs:
- Response waiting start: `Waiting for response for $key`
- Response storage to DB (for another node): `Inserting response for $requestKey`

**Assessment:** ✅ Good — sufficient for async flow debugging.

### 4.11 Metrics

| Metric | Component | Description |
|--------|-----------|-------------|
| `edelivery_messages_sent` | `EDeliveryClient` | Total eDelivery messages sent |
| `edelivery_messages_received` | `EDeliveryRoutes` | Total eDelivery messages received |
| `edelivery_client` | `EDeliveryClient` | HTTP client state: pendingRequests, openedConnections, pendingOperationCount |

Metrics are available at the `/metrics` endpoint.

### 4.12 Overall Assessment

Current logging is at **PoC level**:
- Critical errors are logged
- `RequestLogger` provides an overview of incoming requests
- `PlatformClient` (REST) is a good example of how to log outgoing requests

Only `PlatformClient` (REST variant) properly answers the question **"where was the request made from, where to, what was the result"**. For all other outgoing requests (GateClient, EDeliveryClient), information is completely missing.

**The current logging level is not sufficient for a production environment.**

---

## 5. Audit Requirements

### 5.1 Current State

Audit logging is **minimal** — only failed authentications are logged (`AccessChecker.log.error`). Events required from a GDPR and audit perspective **are not logged**.

### 5.2 Auditable Events (Proposal)

| # | Event | Log Level | Required Info | Current Status |
|---|-------|-----------|--------------|----------------|
| A1 | **Successful login** | INFO | User ID, email, role, IP address, authentication method (Basic/Bearer) | ❌ Missing |
| A2 | **Failed login** | WARN | IP address, username (if available), reason | ✅ Present (`AccessChecker.log.error`) |
| A3 | **User creation** | INFO | Created user ID, email, roles, admin user ID | ❌ Missing |
| A4 | **User modification** | INFO | Modified user ID, changed fields, admin user ID | ❌ Missing |
| A5 | **User deletion** | INFO | Deleted user ID, admin user ID | ❌ Missing |
| A6 | **Gate addition/modification** | INFO | Gate ID, admin user ID | ❌ Missing |
| A7 | **Gate deletion** | INFO | Gate ID, admin user ID | ❌ Missing |
| A8 | **Platform addition/modification** | INFO | Platform ID, admin user ID | ❌ Missing |
| A9 | **Platform deletion** | INFO | Platform ID, admin user ID | ❌ Missing |
| A10 | **Authority addition/modification** | INFO | Authority ID, admin user ID | ❌ Missing |
| A11 | **Authority deletion** | INFO | Authority ID, admin user ID | ❌ Missing |
| A12 | **Identifier search** | INFO | Searcher (authority ID), search parameters, result count | ❌ Missing |
| A13 | **Dataset query** | INFO | Requester (authority ID), UIL, subsets | ❌ Missing |
| A14 | **Follow-up message sending** | INFO | Sender (authority ID), UIL, message length | ❌ Missing |
| A15 | **Identifier registration** | INFO | Platform ID, dataset ID, identifier count | ❌ Missing |
| A16 | **Consignment deletion** | INFO | Dataset ID, admin user ID | ❌ Missing |

### 5.3 GDPR Requirements

- **Data access logging** (A12, A13) — eFTI data contains freight information that may be associated with individuals. Every access must be traceable.
- **Retention period** — audit log retention period must comply with GDPR requirements (typically 1–5 years).
- **Log protection** — audit logs must be immutable and protected against unauthorized access.

---

## 6. Security Logs

### 6.1 Current State

| # | Security Event | Logged? | Component | Log Level |
|---|---------------|:---:|-----------|-----------|
| T1 | Failed authentication | ✅ | `AccessChecker` | ERROR |
| T2 | Successful authentication | ❌ | — | — |
| T3 | Authorization denial (missing role) | ❌ | `AccessChecker` | (throws ForbiddenException, does not log) |
| T4 | Write access denial | ❌ | `User.checkWriteAccess()` | (throws ForbiddenException, does not log) |
| T5 | Unknown eDelivery receiver | ✅ | `EDeliveryRoutes` | WARN |
| T6 | Wrong KeyIdentifier in eDelivery message | ✅ | `EDeliveryRoutes` | ERROR (via require) |
| T7 | Unknown encryption method | ✅ | `EDeliveryRoutes` | WARN |
| T8 | eDelivery message processing error | ✅ | `EDeliveryRoutes` | ERROR |
| T9 | Gate connection failure | ✅ | `GateClient` | ERROR |
| T10 | Duplicate request ID | ❌ | `RequestIdValidator` | (throws BadRequestException, does not log) |

### 6.2 Missing Security Logs (Proposal)

| # | Security Event | Recommended Log Level | Priority | Description |
|---|---------------|----------------------|----------|-------------|
| T3 | **Authorization denial** | WARN | HIGH | Currently `AccessChecker` throws `ForbiddenException` but **does not log it** — denials are not visible in logs. Essential for detecting brute force and privilege escalation attempts |
| T11 | **Repeated failed authentication from same IP** | WARN | HIGH | Currently missing — needed for brute force detection |
| T2 | **Successful authentication** | INFO | MEDIUM | Who logged in, from which IP, with which role. Needed for audit trail |
| T4 | **Write access denial** | WARN | MEDIUM | `User.checkWriteAccess()` throws `ForbiddenException` but does not log — unauthorized modification attempts are not traceable |
| T10 | **Duplicate request ID** | WARN | LOW | May indicate a replay attack |

### 6.3 Notes

- **`AccessChecker` logs failed authentications with `log.error`**, but **does not log authorization denials** (`ForbiddenException` is thrown but not logged). This means someone attempting to access a resource they don't have permission for **remains invisible in the logs**.
- **`RequestIdValidator` does not log duplicate request IDs** — only throws `BadRequestException`. The 400 response appears in the `RequestLogger`, but the specific reason (duplicate ID) is not visible.

---

## 7. Example Scenarios

### 7.1 Identifier Search (Successful)

**Scenario:** Authority searches for identifier "ABC-123", data is available locally.

```
1. → Incoming request
   RequestLogger: <eu-authority-1> GET /v1/identifiers/ABC-123 - 200 15ms

2. Business logic (currently NOT LOGGED)
   - EftiService.getIdentifiers() — local search result: 2 consignments
   - Broadcast does not start (data available locally)

3. ← Response: 200 OK + JSON/SSE
```

**Missing from logs:** How many results were found, whether broadcast was started, how long the search took.

### 7.2 Dataset Query from Another Gate (Successful)

**Scenario:** Authority queries a dataset located in the Finnish gate.

```
1. → Incoming request
   RequestLogger: <eu-authority-1> GET /v1/dataset/gate-fi1/platform-fi/uuid?subsetId=S1,S2 - 200 1250ms

2. Business logic (currently NOT LOGGED)
   - EftiService.getDataset() — routing: remote gate gate-fi1

3. Gate-to-gate communication (currently NOT LOGGED)
   - GateClient.sendAndReceive() → gate-fi1 (eDelivery)
   - EDeliveryClient.send() → https://gate-fi1.example/services/msh

4. eDelivery response reception
   GateMessageHandler: Handling uilResponse from RequestKey(senderId=gate-fi1, requestId=abc-123, receiverId=eu-ee31).

5. ← Response: 200 OK + XML dataset
```

**Missing from logs:** Routing decision (local vs remote), eDelivery destination and duration, GateClient result.

### 7.3 Authentication Failure

**Scenario:** Someone attempts to access with an invalid Bearer token.

```
1. → Incoming request
   AccessChecker: log.error — java.lang.IllegalArgumentException: Invalid UUID string: xxx

2. ← Response: 403 Forbidden
   RequestLogger: <null> GET /v1/identifiers/ABC-123 - 403 2ms
```

**Missing from logs:** IP address, which token was used (in hashed form), repeated failure warning.

### 7.4 Platform Identifier Registration (Invalid XML)

**Scenario:** Platform sends invalid XML.

```
1. → Incoming request
   RequestLogger: <demo-platform> POST /v1/consignments/identifier/uuid - 400 5ms

2. Business logic
   EftiService.saveIdentifiers() → throws BadRequestException("Error parsing identifiers: ...")

3. ← Response: 400 Bad Request + plain text error message
```

**Missing from logs:** Which parsing error exactly occurred (only reaches the response, not the log).

### 7.5 eDelivery Message Reception (Encryption Error)

**Scenario:** A message from another gate has a mismatched KeyIdentifier.

```
1. → Incoming request
   RequestLogger: <gate-de1> POST /services/msh - 500 8ms

2. eDelivery processing
   EDeliveryRoutes: ERROR Error when processing message: Invalid KeyIdentifier "xxx", expected "yyy". Raw content: <full message>

3. ← Response: 500 + SOAP Fault XML
```

### 7.6 Gate Offline (Dataset Query)

**Scenario:** Authority queries a dataset from a gate that is offline.

```
1. → Incoming request
   RequestLogger: <eu-authority-1> GET /v1/dataset/gate-fi1/platform-fi/uuid?subsetId=S1 - 502 1ms

2. Business logic
   EftiService.checkGateAvailable() → throws StatusCodeException(502, "Cannot reach Gate gate-fi1: OFFLINE")

3. ← Response: 502 Bad Gateway + plain text "Cannot reach Gate gate-fi1: OFFLINE"
```

---

## 8. Strengths

1. **RequestLogger** automatically covers all incoming HTTP requests with client identifier
2. **PlatformClient** (REST) is exemplary — logs destination, result, and duration in one line
3. **GateMessageHandler** logs incoming message type and sender — good overview of eDelivery traffic
4. **GatePingJob** logs status changes — sufficient for tracking gate availability
5. **KeyManager** logs certificate info — helps debug cryptography issues
6. **MultiNodeAsyncResponseProvider** logs async response flow — helps debug multi-node synchronization
7. **Client identifier** is always present in RequestLogger (authority ID, platform ID, eDelivery sender ID)

---

## 9. Deficiencies

| # | Deficiency | Severity | Impact |
|---|-----------|----------|--------|
| 1 | **GateClient does not log outgoing requests** | HIGH | Gate-to-gate communication tracking is impossible. Broadcast identifier queries, remote dataset requests, and follow-up messages are invisible in logs |
| 2 | **EDeliveryClient.send() does not log** | HIGH | eDelivery message sending is completely untraceable — destination, response, and duration are not logged |
| 3 | **Request ID does not propagate to log messages** | HIGH | Requests cannot be correlated in logs — a single user request's path through the system cannot be traced. Thread name contains request ID but it doesn't always reach the log message |
| 4 | **EftiService does not log business logic flows** | MEDIUM | Routing decisions (local vs remote), operation start/end, and results are invisible |
| 5 | **Structured logging is missing** | MEDIUM | Logs are in free-text format — machine-readable parsing, filtering, and monitoring are difficult |
| 6 | **Successful authentications are not logged** | MEDIUM | Audit information about who logged in and when is missing |
| 7 | **Authorization denials are not logged** | MEDIUM | `AccessChecker` throws `ForbiddenException` but does not log — security incidents remain unnoticed |
| 8 | **PlatformClient eDelivery variant does not log** | MEDIUM | When a platform uses eDelivery (not REST), PlatformClient's good logging does not apply |
| 9 | **Duration logging is missing from most components** | MEDIUM | Only PlatformClient (REST) and RequestLogger log duration |
| 10 | **Unified error format is missing** | MEDIUM | REST API errors are returned as plain text, missing request ID and error code in response |

---

## 10. Improvement Proposals

### 10.1 Outgoing Request Logging (Priority: HIGH)

Add a `PlatformClient`-like logging pattern to `GateClient` and `EDeliveryClient`:

**GateClient — add logging:**
- `sendAndReceive()` — log gate ID, protocol (Fast/eDelivery), destination URL, result, duration
- `getIdentifiers()` — log broadcast result (how many consignments found)
- `getDataset()` — log remote dataset result
- `postFollowUp()` — log follow-up sending
- `ping()` — log successful ping

**EDeliveryClient — add logging:**
- `send()` — log destination URL, receiver Party ID, request ID, response status code, duration
- `sendAndReceive()` — log async waiting start and duration
- `ping()` — log ping destination and result

**Recommended log format:**
```
GateClient: gate-fi1 (fast) POST https://gate-fi1.example/services/fast - 200 45ms
GateClient: gate-de1 (eDelivery) sendAndReceive https://gate-de1.example/services/msh - 200 1250ms
EDeliveryClient: send to gate-fi1 https://gate.example/services/msh - 200 89ms (requestId=abc-123)
```

### 10.2 Request ID Propagation (Priority: HIGH)

Currently `UUIDRequestIdGenerator` generates request ID and sets it as the thread name. Recommendation: use SLF4J MDC (Mapped Diagnostic Context):
- Add incoming request's request ID to MDC in the `Before` handler
- Log format: `%d [%X{requestId}] %-5level %logger - %msg%n`

Result:
```
[abc-123] INFO  RequestLogger - <eu-authority-1> GET /v1/dataset/uuid - 200 1250ms
[abc-123] INFO  EftiService - getDataset routing: remote gate gate-fi1
[abc-123] INFO  GateClient - gate-fi1 (eDelivery) sendAndReceive - 200 1200ms
[abc-123] INFO  EDeliveryClient - send to gate-fi1 https://... - 200 89ms
```

### 10.3 Business Logic Flow Logging (Priority: MEDIUM)

`EftiService` is the central business logic class. The following should be added:
- `getDataset()` — log routing decision (local platform vs remote gate) and result
- `getIdentifiers()` — log broadcast start (to how many gates), local search result count, and summary
- `saveIdentifiers()` — log saved identifier count and UIL
- `sendFollowUp()` — log follow-up routing (local vs remote)
- `handleUilQuery()` / `handleIdentifierQuery()` — log response generation (result count)

### 10.4 Structured Logging (Priority: MEDIUM)

Add `logback-classic` + `logstash-logback-encoder`. JSON format only in production (switchable via env variable):

```xml
<!-- logback.xml (prod) -->
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <includeMdcKeyName>requestId</includeMdcKeyName>
    <includeMdcKeyName>client</includeMdcKeyName>
</encoder>
```

### 10.5 Audit Logging (Priority: MEDIUM)

In a production environment, the following must be logged:
- Successful logins (who, when, from which IP, which role)
- Administrator actions (user creation, gate/platform/authority addition/modification/deletion)
- Data access (who queried which identifier / dataset)

This is important for GDPR and audit requirements compliance.

### 10.6 Authorization Denial Logging (Priority: MEDIUM)

`AccessChecker` and `User.checkWriteAccess()` must log denials before throwing `ForbiddenException`:
```kotlin
log.warn("Access denied for user ${user?.id} to ${exchange.method} ${exchange.path}: insufficient roles")
```

---

## 11. Improvement Proposals Summary

| # | Proposal | Priority |
|---|----------|----------|
| 1 | Outgoing request logging (GateClient, EDeliveryClient) | HIGH |
| 2 | Request ID propagation (MDC) | HIGH |
| 3 | Business logic flow logging (EftiService) | MEDIUM |
| 4 | Structured logging (JSON, logback) | MEDIUM |
| 5 | Audit logging (successful logins, admin actions, data access) | MEDIUM |
| 6 | Authorization denial logging | MEDIUM |
| 7 | Unified error format (JSON + error code + request ID) | MEDIUM |
