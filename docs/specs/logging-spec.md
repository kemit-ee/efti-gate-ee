# eFTI Gate v2.0 Logging Specification

**Version**: 1.0  
**Date**: 2026-04-23  
**Status**: Development-ready specification  

---

## 1. Overview

### 1.1 Purpose

The eFTI Gate produces structured logs to support:
- **Operational troubleshooting** — trace individual requests end-to-end across gate-to-gate and gate-to-platform hops
- **GDPR Art. 30 compliance** — audit trail for all dataset access by authorities (7-year retention)
- **Performance monitoring** — detect slow queries, gate timeouts, and broadcast degradation
- **Security audit** — record authentication failures, authorisation denials, and suspicious patterns

### 1.2 Compliance Requirements

- **eFTI Regulation 2024/1942 and 2025/2243**: All dataset access by competent authorities must be logged with user identity, timestamp, and legal basis
- **GDPR Art. 30**: Record of processing activities — who accessed what data, when, and why
- **eDelivery AS4**: Gate-to-gate message IDs must be preserved in logs for cross-gate correlation

### 1.3 Architecture

```
Application (Kotlin/JVM)
  └── Logback AsyncAppender (queue: 512)
        └── RollingFileAppender → /var/log/efti-gate/application.log
              └── Filebeat / Fluentd
                    └── Elasticsearch / OpenSearch
                          └── Kibana dashboards + alerts
```

---

## 2. JSON Logging Format

### 2.1 Standard: Elastic Common Schema (ECS) 8.x

All log entries **must** be valid JSON on a single line. The format follows [ECS 8.x](https://www.elastic.co/guide/en/ecs/current/index.html).

### 2.2 Standard Fields (Always Present)

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `@timestamp` | ISO 8601 string | Event time in UTC | `"2026-04-23T10:15:30.123Z"` |
| `log.level` | string | Log level (lowercase) | `"info"` |
| `message` | string | Human-readable summary | `"Identifier registered successfully"` |
| `service.name` | string | Always `"efti-gate"` | `"efti-gate"` |
| `service.version` | string | Gate software version | `"2.0.0"` |
| `host.hostname` | string | Node hostname | `"gate-eu-ee31-node1"` |

### 2.3 Context Fields (Present When Applicable)

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `event.action` | string | Dot-separated event name | `"identifier.register"` |
| `event.outcome` | string | `success` or `failure` | `"success"` |
| `event.duration` | long | Nanoseconds elapsed | `4500000` |
| `http.request.id` | string | UUID v4 from X-Request-ID | `"550e8400-e29b-41d4-a716-446655440000"` |
| `http.request.method` | string | HTTP verb | `"POST"` |
| `http.request.path` | string | Exact path from request | `"/identifiers/550e8400-..."` |
| `http.request.body.bytes` | int | Request body size | `1024` |
| `http.response.status_code` | int | HTTP response code | `201` |
| `user.id` | string | User UUID or party ID | `"502d74a0-eb03-11f0-b86c-3c9c0f2eb459"` |
| `user.roles` | string[] | User's gate roles | `["PLATFORM"]` |
| `efti.dataset.id` | string | UUID of the dataset | `"550e8400-e29b-41d4-a716-446655440000"` |
| `efti.platform.id` | string | Platform party ID | `"demo"` |
| `efti.gate.id` | string | Gate party ID | `"eu-ee31"` |
| `efti.authority.id` | string | Authority party ID | `"demo"` |
| `efti.identifier.value` | string | Identifier searched | `"123ABC"` |
| `efti.identifier.type` | string | Identifier type enum | `"means"` |
| `efti.mode` | string | Transport mode code | `"1"` |
| `efti.dangerous_goods` | boolean | Dangerous goods flag | `true` |
| `efti.subsets` | string[] | Requested subsets | `["full"]` |
| `efti.error.code` | string | Error catalog code | `"INVALID_XML"` |
| `db.table` | string | Primary table touched | `"consignments"` |
| `db.operation` | string | SQL operation | `"INSERT"` |
| `db.duration_ms` | int | Query duration in ms | `12` |
| `g2g.source_gate` | string | Originating gate for G2G | `"eu-fi01"` |
| `g2g.target_gate` | string | Target gate for G2G | `"eu-de01"` |
| `g2g.response_time_ms` | long | G2G round-trip ms | `342` |
| `error.type` | string | Exception class name | `"BadRequestException"` |
| `error.message` | string | Exception message | `"Error parsing identifiers: ..."` |
| `error.stack_trace` | string | First 10 stack frames | `"efti.EftiService..."` |
| `job.id` | string | Background job identifier | `"job-20260423-550e8400"` |
| `job.name` | string | Job class name | `"IdentifierExpirationJob"` |

### 2.4 Custom `efti.*` Namespace

All eFTI-specific fields use the `efti.` prefix to avoid collision with ECS root fields. Never place custom fields at the ECS root level.

---

## 3. Log Levels

### ERROR
**Criteria**: Unhandled exceptions, database failures, critical system faults that require immediate operator attention.  
**Examples**: `PSQLException` during identifier save, JAXB marshaller misconfiguration, OOM error.  
**Action**: Alert on-call engineer within 5 minutes.

### WARN
**Criteria**: Client errors (4xx), recoverable failures, gate/platform unreachable, circuit breaker state changes, authentication failures.  
**Examples**: `INVALID_XML` from platform, gate marked OFFLINE, rate limit exceeded, `ForbiddenException`.  
**Action**: No immediate action required; review during business hours.

### INFO
**Criteria**: Normal business events — identifier registered, search completed, dataset delivered, admin action, user login, background job completion.  
**Examples**: Identifier saved to `consignments`, broadcast completed, authority dataset request fulfilled.  
**Action**: Retained for audit and business analytics.

### DEBUG
**Criteria**: Detailed flow — SQL queries with bind parameters, XML transformation steps, gate communication headers, SSE stream lifecycle.  
**Examples**: SQL INSERT statement, parsed `ConsignmentXml` fields, eDelivery SOAP envelope details.  
**Action**: Disabled in production by default (`LOG_LEVEL=INFO`).

### TRACE
**Criteria**: Full request/response bodies, raw XML payloads, complete SOAP envelopes.  
**Examples**: Full consignment XML submitted by platform, complete AS4 response from gate.  
**Action**: **Never enabled in production**. Use only in isolated development environments.

**Configuration**: `LOG_LEVEL` environment variable (default: `INFO`). Valid values: `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE`.

---

## 4. Logging Scenarios

### 4.1 Platform API Operations

#### 4.1.1 Identifier Registration (Success)

**Trigger**: Platform POSTs valid consignment XML → `EftiService.saveIdentifiers()` completes without exception → HTTP 200  
**Business context**: Platform registers new consignment identifiers (vehicle plate, container, trailer) in the local gate registry.  
**Log level**: INFO  
**Retention**: 90 days (business event)

```json
{
  "@timestamp": "2026-04-23T10:15:30.123Z",
  "log.level": "info",
  "message": "Identifier registered successfully",
  "event.action": "identifier.register",
  "event.outcome": "success",
  "event.duration": 4500000,
  "http": {
    "request.id": "550e8400-e29b-41d4-a716-446655440000",
    "request.method": "POST",
    "request.path": "/identifiers/550e8400-e29b-41d4-a716-446655440000",
    "request.body.bytes": 1842,
    "response.status_code": 200
  },
  "user": {
    "id": "502d74a0-eb03-11f0-b86c-3c9c0f2eb459",
    "roles": ["PLATFORM"]
  },
  "efti": {
    "dataset.id": "550e8400-e29b-41d4-a716-446655440000",
    "platform.id": "demo",
    "gate.id": "eu-ee31",
    "identifier.value": "123ABC",
    "identifier.type": "means",
    "mode": "1",
    "dangerous_goods": false
  },
  "db": {
    "table": "consignments",
    "operation": "INSERT",
    "duration_ms": 8
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.1.2 Identifier Registration (XML Validation Error)

**Trigger**: Platform POSTs malformed XML → `EftiParser.parseIdentifiers()` throws → `BadRequestException` returned → HTTP 400  
**Log level**: WARN

```json
{
  "@timestamp": "2026-04-23T10:16:45.789Z",
  "log.level": "warn",
  "message": "Identifier registration failed: XML parse error",
  "event.action": "identifier.register",
  "event.outcome": "failure",
  "event.duration": 1200000,
  "http": {
    "request.id": "660f9511-f39c-42e5-b827-557766551111",
    "request.method": "POST",
    "request.path": "/identifiers/660f9511-f39c-42e5-b827-557766551111",
    "request.body.bytes": 542,
    "response.status_code": 400
  },
  "user": {
    "id": "502d74a0-eb03-11f0-b86c-3c9c0f2eb459",
    "roles": ["PLATFORM"]
  },
  "efti": {
    "dataset.id": "660f9511-f39c-42e5-b827-557766551111",
    "platform.id": "demo",
    "error.code": "INVALID_XML"
  },
  "error": {
    "type": "BadRequestException",
    "message": "Error parsing identifiers: XML parse error at line 4: element 'modeCode' is not closed"
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.1.3 Identifier Registration (Duplicate Dataset ID)

**Trigger**: Platform re-POSTs same datasetId → `DataIntegrityViolationException` (primary key on `consignments.datasetId`) → HTTP 409  
**Log level**: WARN

```json
{
  "@timestamp": "2026-04-23T10:17:12.456Z",
  "log.level": "warn",
  "message": "Identifier registration failed: duplicate datasetId",
  "event.action": "identifier.register",
  "event.outcome": "failure",
  "event.duration": 3200000,
  "http": {
    "request.id": "770fa622-a49d-53f6-c938-668877662222",
    "request.method": "POST",
    "request.path": "/identifiers/550e8400-e29b-41d4-a716-446655440000",
    "response.status_code": 409
  },
  "user": {
    "id": "502d74a0-eb03-11f0-b86c-3c9c0f2eb459",
    "roles": ["PLATFORM"]
  },
  "efti": {
    "dataset.id": "550e8400-e29b-41d4-a716-446655440000",
    "platform.id": "demo",
    "error.code": "DUPLICATE_DATASET_ID"
  },
  "error": {
    "type": "DataIntegrityViolationException",
    "message": "duplicate key value violates unique constraint \"consignments_pkey\""
  },
  "db": {
    "table": "consignments",
    "operation": "INSERT"
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.1.4 Identifier Expiration (Scheduled Job)

**Trigger**: `IdentifierExpirationJob` runs on schedule, deletes expired consignments  
**Log level**: INFO  
**Retention**: 7 years (audit log — deletion of data)

```json
{
  "@timestamp": "2026-04-23T02:00:01.000Z",
  "log.level": "info",
  "message": "Identifier expiration job completed",
  "event.action": "identifier.expire",
  "event.outcome": "success",
  "event.duration": 345000000,
  "job": {
    "id": "job-20260423-550e8400-e29b-41d4-a716",
    "name": "IdentifierExpirationJob"
  },
  "efti": {
    "expired_count": 14,
    "cutoff_datetime": "2026-04-16T02:00:00.000Z"
  },
  "db": {
    "table": "consignments",
    "operation": "DELETE",
    "duration_ms": 287
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

### 4.2 Authority API Operations

#### 4.2.1 Identifier Search (Local-Only, Results Found)

**Trigger**: `AuthorityRoutes.getIdentifiers()` → local results found → broadcast skipped → HTTP 200  
**Log level**: INFO  
**Retention**: 7 years (GDPR — authority data access audit)

```json
{
  "@timestamp": "2026-04-23T11:05:22.301Z",
  "log.level": "info",
  "message": "Identifier search completed: local results found, broadcast skipped",
  "event.action": "identifier.search",
  "event.outcome": "success",
  "event.duration": 12000000,
  "http": {
    "request.id": "880fb733-b59e-64a7-d049-779988773333",
    "request.method": "GET",
    "request.path": "/identifiers/123ABC",
    "response.status_code": 200
  },
  "user": {
    "id": "04fa30eb-eb08-11f0-b506-3c9c0f2eb459",
    "roles": ["AUTHORITY"]
  },
  "efti": {
    "authority.id": "demo",
    "identifier.value": "123ABC",
    "identifier.type": "means",
    "search.local_results": 2,
    "search.broadcast": false,
    "mode": "1"
  },
  "db": {
    "table": "identifiers",
    "operation": "SELECT",
    "duration_ms": 6
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.2.2 Identifier Search (Broadcast to All Gates, SSE Stream)

**Trigger**: Local search returns 0 results → `EftiService.getIdentifiers()` broadcasts to all `gateRegistry.online()` gates → SSE stream opened  
**Log level**: INFO  
**Retention**: 7 years (GDPR audit)

```json
{
  "@timestamp": "2026-04-23T11:06:44.512Z",
  "log.level": "info",
  "message": "Identifier search broadcast initiated: 4 online gates",
  "event.action": "identifier.search.broadcast",
  "event.outcome": "success",
  "event.duration": 8200000000,
  "http": {
    "request.id": "990gc844-c60f-75b8-e150-880099884444",
    "request.method": "GET",
    "request.path": "/identifiers/456XYZ",
    "response.status_code": 200
  },
  "user": {
    "id": "04fa30eb-eb08-11f0-b506-3c9c0f2eb459",
    "roles": ["AUTHORITY"]
  },
  "efti": {
    "authority.id": "demo",
    "identifier.value": "456XYZ",
    "search.local_results": 0,
    "search.broadcast": true,
    "search.gates_queried": ["eu-fi01", "eu-de01", "eu-se01", "eu-pl01"],
    "search.total_results": 1
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.2.3 Identifier Search (No Results, Broadcast Timeout)

**Trigger**: Local = 0, broadcast sent, all gates returned empty, one gate timed out  
**Log level**: WARN (partial failure due to gate timeout)  
**Retention**: 7 years (GDPR audit)

```json
{
  "@timestamp": "2026-04-23T11:08:03.100Z",
  "log.level": "warn",
  "message": "Identifier search broadcast completed with partial failure: gate eu-de01 timed out",
  "event.action": "identifier.search.broadcast",
  "event.outcome": "failure",
  "event.duration": 8050000000,
  "http": {
    "request.id": "aa1hd955-d71g-86c9-f261-991100995555",
    "request.method": "GET",
    "request.path": "/identifiers/789DEF",
    "response.status_code": 200
  },
  "user": {
    "id": "04fa30eb-eb08-11f0-b506-3c9c0f2eb459",
    "roles": ["AUTHORITY"]
  },
  "efti": {
    "authority.id": "demo",
    "identifier.value": "789DEF",
    "search.local_results": 0,
    "search.broadcast": true,
    "search.total_results": 0,
    "search.failed_gates": ["eu-de01"]
  },
  "g2g": {
    "target_gate": "eu-de01",
    "response_time_ms": 8050,
    "error": "eu-de01 failed with ConnectTimeoutException for IdentifiersQuery(id=789DEF)"
  },
  "efti.error.code": "GATE_TIMEOUT",
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.2.4 Dataset Request (Success — Local Platform)

**Trigger**: `AuthorityRoutes.getDataset()` → `EftiService.getDataset()` → local gate → `PlatformClient.getDataset()` → HTTP 200 from platform → forwarded to authority  
**Log level**: INFO  
**Retention**: 7 years (GDPR — dataset access by authority)

```json
{
  "@timestamp": "2026-04-23T11:09:18.234Z",
  "log.level": "info",
  "message": "Dataset delivered to authority",
  "event.action": "dataset.deliver",
  "event.outcome": "success",
  "event.duration": 245000000,
  "http": {
    "request.id": "bb2ie066-e82h-97da-g372-002211006666",
    "request.method": "GET",
    "request.path": "/dataset/eu-ee31/demo/550e8400-e29b-41d4-a716-446655440000",
    "response.status_code": 200
  },
  "user": {
    "id": "04fa30eb-eb08-11f0-b506-3c9c0f2eb459",
    "roles": ["AUTHORITY"]
  },
  "efti": {
    "authority.id": "demo",
    "dataset.id": "550e8400-e29b-41d4-a716-446655440000",
    "platform.id": "demo",
    "gate.id": "eu-ee31",
    "subsets": ["full"],
    "dataset.size_bytes": 15234
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.2.5 Dataset Request (Platform Timeout)

**Trigger**: `PlatformClient.getDataset()` → platform does not respond within 30s → `SocketTimeoutException`  
**Log level**: WARN

```json
{
  "@timestamp": "2026-04-23T11:10:50.789Z",
  "log.level": "warn",
  "message": "Dataset request failed: platform timeout",
  "event.action": "dataset.deliver",
  "event.outcome": "failure",
  "event.duration": 30050000000,
  "http": {
    "request.id": "cc3jf177-f93i-08eb-h483-113322117777",
    "request.method": "GET",
    "request.path": "/dataset/eu-ee31/demo/660f9511-f39c-42e5-b827-557766551111",
    "response.status_code": 504
  },
  "user": {
    "id": "04fa30eb-eb08-11f0-b506-3c9c0f2eb459",
    "roles": ["AUTHORITY"]
  },
  "efti": {
    "authority.id": "demo",
    "dataset.id": "660f9511-f39c-42e5-b827-557766551111",
    "platform.id": "demo",
    "gate.id": "eu-ee31",
    "error.code": "PLATFORM_TIMEOUT"
  },
  "error": {
    "type": "SocketTimeoutException",
    "message": "Read timeout after 30000ms connecting to http://demo-platform:8070"
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.2.6 Dataset Request (Remote Gate — Via G2G)

**Trigger**: Dataset UIL references gate `eu-fi01` (not this gate) → `GateClient.getDataset()` called → success  
**Log level**: INFO  
**Retention**: 7 years (GDPR audit)

```json
{
  "@timestamp": "2026-04-23T11:12:05.456Z",
  "log.level": "info",
  "message": "Dataset proxied from remote gate eu-fi01",
  "event.action": "dataset.proxy",
  "event.outcome": "success",
  "event.duration": 380000000,
  "http": {
    "request.id": "dd4kg288-g04j-19fc-i594-224433228888",
    "request.method": "GET",
    "request.path": "/dataset/eu-fi01/plt-456/770fa622-a49d-53f6-c938-668877662222",
    "response.status_code": 200
  },
  "user": {
    "id": "04fa30eb-eb08-11f0-b506-3c9c0f2eb459",
    "roles": ["AUTHORITY"]
  },
  "efti": {
    "authority.id": "demo",
    "dataset.id": "770fa622-a49d-53f6-c938-668877662222",
    "platform.id": "plt-456",
    "gate.id": "eu-fi01",
    "subsets": ["dangerous-goods"]
  },
  "g2g": {
    "target_gate": "eu-fi01",
    "response_time_ms": 378
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.2.7 Follow-Up Message Sent

**Trigger**: `AuthorityRoutes.postFollowUp()` → `EftiService.sendFollowUp()` → platform or gate notified  
**Log level**: INFO  
**Retention**: 7 years (GDPR — authority action record)

```json
{
  "@timestamp": "2026-04-23T11:14:22.100Z",
  "log.level": "info",
  "message": "Follow-up message sent to platform",
  "event.action": "followup.send",
  "event.outcome": "success",
  "event.duration": 95000000,
  "http": {
    "request.id": "ee5lh399-h15k-20gd-j605-335544339999",
    "request.method": "POST",
    "request.path": "/follow-up/eu-ee31/demo/550e8400-e29b-41d4-a716-446655440000/dd4kg288-g04j-19fc-i594",
    "response.status_code": 200
  },
  "user": {
    "id": "04fa30eb-eb08-11f0-b506-3c9c0f2eb459",
    "roles": ["AUTHORITY"]
  },
  "efti": {
    "authority.id": "demo",
    "dataset.id": "550e8400-e29b-41d4-a716-446655440000",
    "platform.id": "demo",
    "gate.id": "eu-ee31"
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

### 4.3 Admin API Operations

#### 4.3.1 User Login (Success)

**Trigger**: `AccessChecker.before()` resolves credentials via `UserRepository.byCredentials()` — valid user found  
**Log level**: INFO  
**Retention**: 7 years (security audit)

```json
{
  "@timestamp": "2026-04-23T09:00:05.321Z",
  "log.level": "info",
  "message": "User authenticated successfully",
  "event.action": "user.login",
  "event.outcome": "success",
  "http": {
    "request.method": "GET",
    "request.path": "/admin/user",
    "response.status_code": 200
  },
  "user": {
    "id": "175791a3-da82-11f0-b10c-3c9c0f2eb459",
    "roles": ["ADMIN"]
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.3.2 User Login (Failed Authentication)

**Trigger**: `AccessChecker.before()` → `byCredentials()` returns null or throws → `ForbiddenException` → HTTP 401  
**Log level**: WARN  
**Retention**: 7 years (security audit)

```json
{
  "@timestamp": "2026-04-23T09:01:14.678Z",
  "log.level": "warn",
  "message": "Authentication failed: invalid credentials",
  "event.action": "user.login",
  "event.outcome": "failure",
  "http": {
    "request.method": "POST",
    "request.path": "/identifiers/550e8400-e29b-41d4-a716-446655440000",
    "response.status_code": 401
  },
  "efti": {
    "error.code": "TOKEN_INVALID"
  },
  "error": {
    "type": "ForbiddenException",
    "message": "Invalid authorization (must be valid Basic or Bearer token)"
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.3.3 Authorisation Denied

**Trigger**: User authenticated but `checkAccess()` fails — wrong role for endpoint  
**Log level**: WARN  
**Retention**: 7 years (security audit)

```json
{
  "@timestamp": "2026-04-23T09:02:45.234Z",
  "log.level": "warn",
  "message": "Authorisation denied: insufficient role",
  "event.action": "user.access.denied",
  "event.outcome": "failure",
  "http": {
    "request.method": "POST",
    "request.path": "/identifiers/550e8400-e29b-41d4-a716-446655440000",
    "response.status_code": 403
  },
  "user": {
    "id": "04fa30eb-eb08-11f0-b506-3c9c0f2eb459",
    "roles": ["AUTHORITY"]
  },
  "efti": {
    "error.code": "FORBIDDEN",
    "required_role": "PLATFORM"
  },
  "error": {
    "type": "ForbiddenException",
    "message": "Access denied: endpoint requires PLATFORM role"
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.3.4 Platform Registered (Admin Action)

**Trigger**: Admin POSTs new platform via admin API → INSERT into `platforms` table  
**Log level**: INFO  
**Retention**: 7 years (admin audit trail)

```json
{
  "@timestamp": "2026-04-23T14:30:00.000Z",
  "log.level": "info",
  "message": "Platform registered by admin",
  "event.action": "platform.create",
  "event.outcome": "success",
  "event.duration": 22000000,
  "http": {
    "request.method": "POST",
    "request.path": "/admin/platforms",
    "response.status_code": 200
  },
  "user": {
    "id": "175791a3-da82-11f0-b10c-3c9c0f2eb459",
    "roles": ["ADMIN"]
  },
  "efti": {
    "platform.id": "plt-new-001"
  },
  "db": {
    "table": "platforms",
    "operation": "INSERT",
    "duration_ms": 11
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.3.5 Platform Deleted (Admin Action)

**Trigger**: Admin DELETEs platform — cascade deletes associated users and consignments  
**Log level**: INFO  
**Retention**: 7 years (admin audit trail — data deletion)

```json
{
  "@timestamp": "2026-04-23T15:10:05.789Z",
  "log.level": "info",
  "message": "Platform deleted by admin",
  "event.action": "platform.delete",
  "event.outcome": "success",
  "event.duration": 45000000,
  "http": {
    "request.method": "DELETE",
    "request.path": "/admin/platforms/plt-old-001",
    "response.status_code": 200
  },
  "user": {
    "id": "175791a3-da82-11f0-b10c-3c9c0f2eb459",
    "roles": ["ADMIN"]
  },
  "efti": {
    "platform.id": "plt-old-001"
  },
  "db": {
    "table": "platforms",
    "operation": "DELETE",
    "duration_ms": 34
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.3.6 Authority Registered (Admin Action)

**Trigger**: Admin POSTs new authority  
**Log level**: INFO  
**Retention**: 7 years

```json
{
  "@timestamp": "2026-04-23T15:20:12.456Z",
  "log.level": "info",
  "message": "Authority registered by admin",
  "event.action": "authority.create",
  "event.outcome": "success",
  "event.duration": 18000000,
  "http": {
    "request.method": "POST",
    "request.path": "/admin/authorities",
    "response.status_code": 200
  },
  "user": {
    "id": "175791a3-da82-11f0-b10c-3c9c0f2eb459",
    "roles": ["ADMIN"]
  },
  "efti": {
    "authority.id": "aut-new-002",
    "authority.country": "EE",
    "authority.subsets": ["full", "dangerous-goods"]
  },
  "db": {
    "table": "authorities",
    "operation": "INSERT",
    "duration_ms": 9
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

### 4.4 Gate-to-Gate Communication

#### 4.4.1 Outgoing Gate Ping (Success)

**Trigger**: `GatePingJob` scheduled every N minutes — pings each registered gate  
**Log level**: INFO

```json
{
  "@timestamp": "2026-04-23T12:00:00.100Z",
  "log.level": "info",
  "message": "Gate ping successful: eu-fi01",
  "event.action": "gate.ping",
  "event.outcome": "success",
  "event.duration": 234000000,
  "job": {
    "id": "job-20260423-ping-eu-fi01",
    "name": "GatePingJob"
  },
  "g2g": {
    "target_gate": "eu-fi01",
    "response_time_ms": 234
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.4.2 Gate Marked Offline (Ping Failure)

**Trigger**: `GatePingJob` fails to reach gate → gate status updated to OFFLINE in `gates` table  
**Log level**: WARN

```json
{
  "@timestamp": "2026-04-23T12:05:00.500Z",
  "log.level": "warn",
  "message": "Gate ping failed: eu-de01 marked OFFLINE",
  "event.action": "gate.ping",
  "event.outcome": "failure",
  "event.duration": 5010000000,
  "job": {
    "id": "job-20260423-ping-eu-de01",
    "name": "GatePingJob"
  },
  "g2g": {
    "target_gate": "eu-de01",
    "response_time_ms": 5010
  },
  "efti.error.code": "GATEWAY_UNAVAILABLE",
  "error": {
    "type": "ConnectTimeoutException",
    "message": "Connection timeout to https://gate-de01.efti.eu eDelivery endpoint after 5000ms"
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.4.3 Incoming Identifier Search Request from Gate

**Trigger**: Remote gate sends AS4 identifier query → `GateMessageHandler` receives → `EftiService.handleIdentifierQuery()` called  
**Log level**: INFO

```json
{
  "@timestamp": "2026-04-23T11:30:45.678Z",
  "log.level": "info",
  "message": "Incoming G2G identifier search request processed",
  "event.action": "g2g.identifier.search.incoming",
  "event.outcome": "success",
  "event.duration": 9000000,
  "g2g": {
    "source_gate": "eu-fi01",
    "response_time_ms": 9
  },
  "efti": {
    "identifier.value": "123ABC",
    "search.local_results": 1
  },
  "db": {
    "table": "identifiers",
    "operation": "SELECT",
    "duration_ms": 5
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.4.4 Outgoing Identifier Search Broadcast (Single Gate)

**Trigger**: `EftiService.getIdentifiers()` launches coroutine per online gate → `GateClient.getIdentifiers(gate, q)`  
**Log level**: INFO (one log entry per gate)

```json
{
  "@timestamp": "2026-04-23T11:32:00.100Z",
  "log.level": "info",
  "message": "Outgoing G2G identifier search sent to eu-fi01",
  "event.action": "g2g.identifier.search.outgoing",
  "event.outcome": "success",
  "event.duration": 287000000,
  "http": {
    "request.id": "ff6mi400-i26l-31he-k716-446655440000"
  },
  "g2g": {
    "target_gate": "eu-fi01",
    "response_time_ms": 287
  },
  "efti": {
    "identifier.value": "456XYZ",
    "search.gate_results": 1
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.4.5 Incoming UIL Dataset Request from Gate

**Trigger**: Remote gate sends AS4 UIL query → `GateMessageHandler` → `EftiService.handleUilQuery()`  
**Log level**: INFO  
**Retention**: 7 years (GDPR)

```json
{
  "@timestamp": "2026-04-23T11:35:10.200Z",
  "log.level": "info",
  "message": "Incoming G2G UIL dataset request processed",
  "event.action": "g2g.dataset.request.incoming",
  "event.outcome": "success",
  "event.duration": 245000000,
  "g2g": {
    "source_gate": "eu-se01",
    "response_time_ms": 245
  },
  "efti": {
    "dataset.id": "550e8400-e29b-41d4-a716-446655440000",
    "platform.id": "demo",
    "gate.id": "eu-ee31",
    "subsets": ["full"]
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

### 4.5 System Events

#### 4.5.1 Application Startup

**Trigger**: Gate fully initialised — database connected, routes registered, jobs scheduled  
**Log level**: INFO

```json
{
  "@timestamp": "2026-04-23T08:00:01.000Z",
  "log.level": "info",
  "message": "eFTI Gate started successfully",
  "event.action": "application.start",
  "event.outcome": "success",
  "efti": {
    "gate.id": "eu-ee31",
    "log_level": "INFO",
    "platform_count": 2,
    "authority_count": 1,
    "gate_count": 4
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.5.2 XML Parsing Error (eDelivery Message)

**Trigger**: `GateMessageHandler` receives malformed AS4 payload — XML cannot be parsed  
**Log level**: WARN

```json
{
  "@timestamp": "2026-04-23T11:40:00.987Z",
  "log.level": "warn",
  "message": "XML parsing error in incoming eDelivery message",
  "event.action": "edelivery.message.receive",
  "event.outcome": "failure",
  "g2g": {
    "source_gate": "eu-pl01"
  },
  "efti.error.code": "INVALID_XML",
  "error": {
    "type": "SAXParseException",
    "message": "Content is not allowed in prolog at line 1 column 1"
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.5.3 Database Connection Pool Warning

**Trigger**: JDBC connection pool exhausted or near-exhausted — less than 2 connections available  
**Log level**: WARN

```json
{
  "@timestamp": "2026-04-23T13:55:00.123Z",
  "log.level": "warn",
  "message": "Database connection pool near exhaustion: 1 of 10 connections available",
  "event.action": "db.pool.warning",
  "event.outcome": "failure",
  "efti": {
    "db.pool.available": 1,
    "db.pool.max": 10,
    "db.pool.pending": 8
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.5.4 Background Registry Sync Job

**Trigger**: Scheduled job reloads gate/platform/authority registry from database  
**Log level**: INFO

```json
{
  "@timestamp": "2026-04-23T12:00:00.050Z",
  "log.level": "info",
  "message": "Registry sync job completed",
  "event.action": "registry.sync",
  "event.outcome": "success",
  "event.duration": 56000000,
  "job": {
    "id": "job-20260423-regSync-550e8400",
    "name": "RegistrySyncJob"
  },
  "efti": {
    "gate_count": 4,
    "platform_count": 2,
    "authority_count": 1
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.5.5 Async Response Stored (eDelivery AS4)

**Trigger**: Asynchronous AS4 response received and stored in `async_responses` table for later retrieval  
**Log level**: INFO

```json
{
  "@timestamp": "2026-04-23T11:33:00.400Z",
  "log.level": "info",
  "message": "Async eDelivery response stored",
  "event.action": "edelivery.response.store",
  "event.outcome": "success",
  "g2g": {
    "source_gate": "eu-fi01"
  },
  "http": {
    "request.id": "ff6mi400-i26l-31he-k716-446655440000"
  },
  "db": {
    "table": "async_responses",
    "operation": "INSERT",
    "duration_ms": 4
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

### 4.6 Performance Logging

#### 4.6.1 Slow Database Query Warning

**Trigger**: Any SQL query exceeds 500ms  
**Log level**: WARN

```json
{
  "@timestamp": "2026-04-23T14:02:33.001Z",
  "log.level": "warn",
  "message": "Slow database query detected: 623ms",
  "event.action": "db.query.slow",
  "event.outcome": "success",
  "http": {
    "request.id": "gg7nj511-j37m-42if-l827-557766551111"
  },
  "db": {
    "table": "identifiers",
    "operation": "SELECT",
    "duration_ms": 623,
    "query_hash": "SELECT id, datasetId FROM identifiers WHERE id = $1 AND type = $2"
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.6.2 SSE Stream Opened

**Trigger**: Authority request includes `Accept: text/event-stream` → `e.startEventStream()` called  
**Log level**: INFO

```json
{
  "@timestamp": "2026-04-23T11:06:44.500Z",
  "log.level": "info",
  "message": "SSE stream opened for identifier search",
  "event.action": "sse.stream.open",
  "event.outcome": "success",
  "http": {
    "request.id": "990gc844-c60f-75b8-e150-880099884444",
    "request.path": "/identifiers/456XYZ"
  },
  "user": {
    "id": "04fa30eb-eb08-11f0-b506-3c9c0f2eb459",
    "roles": ["AUTHORITY"]
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.6.3 SSE Stream Closed

**Trigger**: All gates responded or timed out → `Event(name = "complete")` sent → connection closed  
**Log level**: INFO

```json
{
  "@timestamp": "2026-04-23T11:06:52.811Z",
  "log.level": "info",
  "message": "SSE stream closed: broadcast complete",
  "event.action": "sse.stream.close",
  "event.outcome": "success",
  "event.duration": 8311000000,
  "http": {
    "request.id": "990gc844-c60f-75b8-e150-880099884444",
    "request.path": "/identifiers/456XYZ"
  },
  "efti": {
    "search.events_sent": 6,
    "search.total_results": 1
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

#### 4.6.4 High Heap Memory Warning

**Trigger**: JVM heap usage exceeds 80% of `-Xmx`  
**Log level**: WARN

```json
{
  "@timestamp": "2026-04-23T15:30:00.001Z",
  "log.level": "warn",
  "message": "High heap memory usage: 83%",
  "event.action": "jvm.memory.warning",
  "efti": {
    "jvm.heap.used_mb": 830,
    "jvm.heap.max_mb": 1000,
    "jvm.heap.percent": 83
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

---

## 5. Sensitive Data Handling

### 5.1 Redacted Entirely (Never Logged)

- `secretHash` column values from `users` table
- Private key material from `eDeliveryCert` / `tlsCert` columns
- Full XML dataset content (the consignment payload stored in `consignments.xml`)
- API key / Bearer token values from `Authorization` header
- Password values from HTTP Basic Auth

### 5.2 Partially Redacted

- **Vehicle plate in audit logs** (where legally required): Show first 3 characters — `"123***"`. Use only in GDPR-sensitive contexts. Full plate is logged in operational INFO logs.
- **User email**: Log only user UUID (`user.id`), not `user.email`

### 5.3 Never Logged at INFO or Above

- Full request body XML (only size in bytes: `http.request.body.bytes`)
- Full response body XML (only size in bytes: `http.response.body.bytes`)
- SQL query bind parameter values at WARN/INFO (only query hash or template)

### 5.4 Dataset Content Logging

Full XML dataset content may be logged only at TRACE level, and only when `LOG_DATASET_CONTENT=true` environment variable is set. This must **never** be enabled in production.

---

## 6. Correlation & Tracing

### 6.1 X-Request-ID Flow

```
Authority Browser/System
  → HTTP GET /identifiers/123ABC
      Header: X-Request-ID: 550e8400-e29b-41d4-a716-446655440000
  → AuthorityRoutes (http.request.id = "550e8400-...")
  → EftiService.getIdentifiers()
      Local search → logged with same http.request.id
  → GateClient.getIdentifiers(eu-fi01, q)
      AS4 message → requestId propagated in SOAP/eDelivery header
  → Remote gate eu-fi01 logs with same requestId
  → Response → logged with same http.request.id
  → SSE events sent → each contains requestId
  → Final log entry → http.request.id = "550e8400-..."
```

### 6.2 Background Job IDs

Format: `job-{yyyyMMdd}-{uuid-prefix}`  
Example: `job-20260423-550e8400-e29b-41d4`

### 6.3 Cross-Gate Tracing

When broadcasting, each gate-to-gate call carries the original `requestId` as the eDelivery message correlation ID. Search for `http.request.id: "550e8400-..."` across all gate Elasticsearch indices to reconstruct the full broadcast trace.

---

## 7. Performance Requirements

- **JSON serialisation overhead**: Must not exceed 2ms per log entry (use async appender)
- **Buffering**: Logback `AsyncAppender` with `queueSize=512`, `discardingThreshold=0` (never discard ERROR/WARN)
- **Throughput**: Must support 1000 log entries/second per gate node without blocking request threads
- **Sampling**: High-volume events (`g2g.identifier.search.outgoing` at DEBUG) may be sampled at 10% in production via `LOG_SAMPLING_RATE=0.1`

---

## 8. Retention Policies

| Log type | Retention | Legal basis | Examples |
|----------|-----------|-------------|---------|
| Audit logs (authority data access) | **7 years** | GDPR Art. 30, eFTI Reg. 2024/1942 | `dataset.deliver`, `identifier.search`, `followup.send` |
| Admin audit trail | **7 years** | GDPR Art. 30 | `platform.create`, `platform.delete`, `authority.create` |
| Security events | **7 years** | Security policy | `user.login`, `user.access.denied` |
| Business events | **90 days** | Operational | `identifier.register`, `gate.ping` |
| Performance logs | **30 days** | Operational | `db.query.slow`, `jvm.memory.warning` |
| Error logs | **90 days** | Operational | ERROR, WARN level entries |
| Debug logs | **7 days** | Development only | DEBUG level |
| Trace logs | **1 day** | Development only | TRACE level (disabled in production) |

---

## 9. Log Storage & Aggregation

### 9.1 Recommended Stack

- **Log shipper**: Filebeat or Fluentd reading `/var/log/efti-gate/application.log`
- **Storage**: Elasticsearch 8.x or OpenSearch 2.x
- **Index strategy**: Daily rolling indices — `efti-gate-2026.04.23`
- **ILM policy**: Hot (0-7 days) → Warm (7-90 days) → Cold (90 days → 7 years for audit) → Delete

### 9.2 Indexed Fields (for query performance)

- `event.action`
- `user.id`
- `http.request.id`
- `efti.dataset.id`
- `efti.platform.id`
- `efti.identifier.value`
- `efti.error.code`
- `log.level`

---

## 10. Configuration

| Environment variable | Default | Description |
|---------------------|---------|-------------|
| `LOG_LEVEL` | `INFO` | Minimum log level: `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE` |
| `LOG_FORMAT` | `JSON` | Output format: `JSON` (production) or `TEXT` (development) |
| `LOG_FILE_PATH` | `/var/log/efti-gate/application.log` | Log file path |
| `LOG_DATASET_CONTENT` | `false` | If `true`, log full XML dataset at TRACE (never use in production) |
| `LOG_SAMPLING_RATE` | `1.0` | Sampling rate for high-volume events (0.0–1.0) |
| `LOG_ASYNC_QUEUE_SIZE` | `512` | Logback AsyncAppender queue size |

---

## 11. Testing & Validation

### 11.1 JSON Schema Validation

```bash
# Extract all JSON blocks and validate
grep -A 60 '```json' docs/specs/logging-spec.md | jq . > /dev/null
# Must succeed with no errors
```

### 11.2 Required Fields Check

Every log entry must contain `@timestamp`, `log.level`, `message`, `service.name`, `service.version`, `host.hostname`.

### 11.3 Integration Test Pattern

```
1. Trigger event (e.g., POST /identifiers/... with valid XML)
2. Wait 100ms for async log flush
3. Query Elasticsearch: event.action = "identifier.register" AND event.outcome = "success"
4. Assert: all required fields present, event.duration > 0, db.table = "consignments"
```

---

## 12. Migration from Current Gate

| Aspect | Current Gate | v2.0 |
|--------|-------------|------|
| Format | Unstructured text via `logger().info(...)` / `logger().error(...)` | ECS-compliant JSON |
| Context | Minimal (message only) | Full context: user, dataset, gate, timing |
| Audit trail | None | 7-year GDPR-compliant audit for dataset access |
| Performance | Synchronous | Async with queue (< 2ms overhead) |
| Correlation | X-Request-ID passed in code but not always logged | Consistent `http.request.id` in every log entry |

---

## Appendix A: Complete Field Reference

| Field | ECS? | Type | Required | Description | Example |
|-------|------|------|----------|-------------|---------|
| `@timestamp` | ✅ | ISO 8601 | Always | Event UTC timestamp | `"2026-04-23T10:15:30.123Z"` |
| `log.level` | ✅ | string | Always | Log level (lowercase) | `"info"` |
| `message` | ✅ | string | Always | Human-readable summary | `"Identifier registered"` |
| `event.action` | ✅ | string | Business events | Dot-separated event name | `"identifier.register"` |
| `event.outcome` | ✅ | string | Business events | `success` or `failure` | `"success"` |
| `event.duration` | ✅ | long (ns) | Timed events | Nanosecond duration | `4500000` |
| `http.request.id` | custom | UUID string | HTTP requests | X-Request-ID value | `"550e8400-..."` |
| `http.request.method` | ✅ | string | HTTP requests | HTTP verb | `"POST"` |
| `http.request.path` | custom | string | HTTP requests | Request path | `"/identifiers/..."` |
| `http.request.body.bytes` | ✅ | int | HTTP requests | Body size bytes | `1842` |
| `http.response.status_code` | ✅ | int | HTTP requests | HTTP status | `200` |
| `user.id` | ✅ | UUID string | Authenticated requests | User UUID | `"502d74a0-..."` |
| `user.roles` | custom | string[] | Authenticated requests | Assigned roles | `["PLATFORM"]` |
| `efti.dataset.id` | custom | UUID string | Dataset operations | Dataset UUID | `"550e8400-..."` |
| `efti.platform.id` | custom | string | Platform operations | Platform party ID | `"demo"` |
| `efti.gate.id` | custom | string | G2G operations | Gate party ID | `"eu-ee31"` |
| `efti.authority.id` | custom | string | Authority operations | Authority party ID | `"demo"` |
| `efti.identifier.value` | custom | string | Search operations | Identifier searched | `"123ABC"` |
| `efti.identifier.type` | custom | string | Search operations | Identifier type | `"means"` |
| `efti.mode` | custom | string | Transport operations | Mode code | `"1"` |
| `efti.dangerous_goods` | custom | boolean | Dangerous goods | DG flag | `true` |
| `efti.subsets` | custom | string[] | Dataset requests | Requested subsets | `["full"]` |
| `efti.error.code` | custom | string | Error events | Error catalog code | `"INVALID_XML"` |
| `efti.search.local_results` | custom | int | Search | Local result count | `2` |
| `efti.search.broadcast` | custom | boolean | Search | Was broadcast sent | `false` |
| `efti.search.gates_queried` | custom | string[] | Broadcast search | Gates queried | `["eu-fi01"]` |
| `efti.search.total_results` | custom | int | Search | Total results found | `3` |
| `db.table` | custom | string | DB operations | Primary table | `"consignments"` |
| `db.operation` | custom | string | DB operations | SQL operation | `"INSERT"` |
| `db.duration_ms` | custom | int | DB operations | Query duration ms | `12` |
| `g2g.source_gate` | custom | string | G2G | Originating gate | `"eu-fi01"` |
| `g2g.target_gate` | custom | string | G2G | Target gate | `"eu-de01"` |
| `g2g.response_time_ms` | custom | long | G2G | Round-trip ms | `342` |
| `error.type` | ✅ | string | Errors | Exception class | `"BadRequestException"` |
| `error.message` | ✅ | string | Errors | Exception message | `"Error parsing..."` |
| `error.stack_trace` | ✅ | string | ERROR level | First 10 frames | `"efti.EftiService..."` |
| `job.id` | custom | string | Background jobs | Job run ID | `"job-20260423-..."` |
| `job.name` | custom | string | Background jobs | Job class name | `"GatePingJob"` |
| `service.name` | ✅ | string | Always | `"efti-gate"` | `"efti-gate"` |
| `service.version` | ✅ | string | Always | Software version | `"2.0.0"` |
| `host.hostname` | ✅ | string | Always | Node hostname | `"gate-eu-ee31-node1"` |

---

## Appendix B: Logback Configuration Example

```xml
<configuration>

  <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>${LOG_FILE_PATH:-/var/log/efti-gate/application.log}</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>/var/log/efti-gate/application.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
      <maxHistory>90</maxHistory>
      <totalSizeCap>10GB</totalSizeCap>
    </rollingPolicy>
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <customFields>{"service.name":"efti-gate","service.version":"${SERVICE_VERSION:-2.0.0}"}</customFields>
      <fieldNames>
        <timestamp>@timestamp</timestamp>
        <version>[ignore]</version>
      </fieldNames>
    </encoder>
  </appender>

  <appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
    <queueSize>${LOG_ASYNC_QUEUE_SIZE:-512}</queueSize>
    <discardingThreshold>0</discardingThreshold>
    <includeCallerData>false</includeCallerData>
    <appender-ref ref="JSON_FILE"/>
  </appender>

  <root level="${LOG_LEVEL:-INFO}">
    <appender-ref ref="ASYNC"/>
  </root>

</configuration>
```

**Required dependency** (`build.gradle.kts`):
```kotlin
implementation("net.logstash.logback:logstash-logback-encoder:7.4")
```

---

## Appendix C: Example Kibana/OpenSearch Queries

### Find all failed identifier registrations (last 24h)
```
event.action: "identifier.register" AND event.outcome: "failure"
```

### Trace complete request by X-Request-ID
```
http.request.id: "550e8400-e29b-41d4-a716-446655440000"
```

### Find slow queries (> 500ms) today
```
event.action: "db.query.slow" AND db.duration_ms: [500 TO *]
```

### All dataset access by authority (GDPR audit)
```
event.action: "dataset.deliver" AND user.roles: "AUTHORITY"
```

### Gates that timed out in last hour
```
efti.error.code: "GATE_TIMEOUT" AND @timestamp: [now-1h TO now]
```
