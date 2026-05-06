# PROMPT-04: Generate Logging Specification for eFTI Gate v2.0

## Context

You are helping create a **complete logging specification** for eFTI Gate v2.0, a production system for electronic freight transport information exchange under EU Regulation 2024/2024.

This specification will be used by external developers during procurement to implement consistent, production-ready logging across the entire application.

## Your Task

Generate a **complete logging specification document** (`specs/logging-spec.md`) that defines:
- JSON logging format (Elastic Common Schema compliant)
- 30+ specific logging scenarios with exact field names and values
- Log levels, retention policies, sensitive data handling
- Performance logging requirements
- Audit trail requirements for GDPR compliance

## Input Materials Required

Before starting, you must have access to:

1. **Current Gate Reference**: `{CURRENT_GATE_SOURCE}/`
   - **IMPORTANT**: Current Gate is a **reference for understanding logging context**, NOT a template to copy
   - Use it to understand: What events are logged? What context is important for troubleshooting?
   - Do NOT copy limitations - improve logging where Current Gate is insufficient (missing context, inconsistent format)
   - Current logging patterns: `gate/src/efti/EftiService.kt` (lines with `logger.info()`, `logger.error()`)
   - Performance logging: `gate/src/efti/performance/`
   - Audit logging: Look for patterns in Route handlers

2. **Epic Documentation**: `docs/Askend/efti_full_epics_en.md`
   - Epic 1.1: Identifier search (requires performance logging)
   - Epic 1.4: Identifier expiration (requires audit logging)
   - Epic 2.x: Platform management (requires admin audit trail)
   - Epic 3.x: Authority management (requires audit trail)

3. **OpenAPI Specification**: `specs/openapi.yaml` (from PROMPT-01)
   - All endpoint paths (used in `http.path` field)
   - All error responses (used in error logging scenarios)

4. **Database Schema**: `specs/db/schema.sql` (from PROMPT-02)
   - Table names (used in `db.table` field)
   - User roles (used in `user.role` field)

5. **Feedback Document**: `docs/Askend/feedback/CRITICAL-SPECIFICATION-GAPS.md`
   - Section 1.4: "Missing Specification File: Logging Specification"
   - Contains detailed examples of ECS-compliant JSON logging format

## Specification Requirements

### 1. Format Requirements

**Mandatory**: Elastic Common Schema (ECS) 8.x compliant JSON logging

Example structure:
```json
{
  "@timestamp": "2026-04-22T10:15:30.123Z",
  "log.level": "info",
  "message": "Identifier registered successfully",
  "event.action": "identifier.register",
  "event.outcome": "success",
  "event.duration": 4500000,
  "http": {
    "request.id": "550e8400-e29b-41d4-a716-446655440000",
    "request.method": "POST",
    "request.path": "/v1/platform/identifiers",
    "response.status_code": 201
  },
  "user": {
    "id": "plt-123",
    "type": "platform",
    "role": "platform_operator"
  },
  "efti": {
    "identifier.id": "https://plt-123.efti.com/550e8400-e29b-41d4-a716-446655440000",
    "identifier.type": "EU07",
    "vehicle.plate": "123ABC",
    "vehicle.country": "EE",
    "dataset.size_bytes": 15234
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

### 2. Logging Scenarios to Cover

You must provide **exact JSON examples** for at least these 30+ scenarios:

#### Platform API Scenarios
1. Identifier registration (success)
2. Identifier registration (validation error: missing required field)
3. Identifier registration (duplicate identifier)
4. Identifier update (success)
5. Identifier search (local-only, results found)
6. Identifier search (broadcast to gates, results found)
7. Identifier search (no results, timeout after 8s)
8. Identifier expiration (scheduled job)
9. Dataset upload (success, with file size)
10. Dataset download (success, with timing)

#### Authority API Scenarios
11. Authority identifier search request (success)
12. Authority identifier search (results returned via SSE)
13. Authority dataset request (success)
14. Authority dataset request (platform denies access)
15. Authority dataset request (timeout - platform not responding)

#### Admin API Scenarios
16. Platform registration (success)
17. Platform update (admin changes certificate)
18. Platform deletion (audit trail)
19. Authority registration (success)
20. User login (success)
21. User login (failed authentication)
22. Permission denied (user lacks role)

#### Gate-to-Gate Communication
23. Outgoing gate ping (success)
24. Incoming identifier search request from gate
25. Outgoing identifier search request to gate (broadcast)
26. Incoming search response from gate
27. Gate marked as unreachable (timeout)

#### System Events
28. Application startup (log configuration summary)
29. Database connection pool event (exhaustion warning)
30. XML parsing error (malformed consignment data)
31. Circuit breaker opened (too many failures to external gate)
32. Background job execution (registry sync, gate ping scheduler)

#### Performance Logging
33. Slow query warning (database query > 500ms)
34. High memory usage warning (> 80% heap)
35. SSE stream opened/closed (connection duration)

### 3. Log Level Definitions

Specify exact criteria for each level:

- **ERROR**: System failures, unhandled exceptions, critical business rule violations
- **WARN**: Degraded performance, recoverable errors, circuit breaker state changes
- **INFO**: Business events (identifier registered, search completed, admin actions)
- **DEBUG**: Detailed flow (XML transformations, SQL queries, gate communication details)
- **TRACE**: Full request/response bodies (disabled in production by default)

### 4. Sensitive Data Handling

Document which fields must be:
- **Redacted entirely**: Passwords, API keys, certificates (private keys)
- **Partially redacted**: Vehicle plates (show first 2 chars: "12***C"), dataset content
- **Hashed**: User IDs in certain contexts (GDPR pseudonymization)
- **Never logged**: Full dataset XML unless explicitly requested via query param

### 5. Performance Requirements

- **Latency impact**: JSON serialization must not add > 2ms per log entry
- **Buffering**: Use async appenders (recommended: Logback AsyncAppender with queue size 512)
- **Sampling**: High-volume events (identifier search results) may be sampled at 10% in production
- **Metrics**: Log aggregation must support 1000 log entries/second per Gate node

### 6. Retention Policies

Specify retention for different log types:
- **Audit logs** (admin actions, dataset access): 7 years (GDPR requirement)
- **Business events** (identifier operations): 90 days
- **Performance logs**: 30 days
- **Debug logs**: 7 days (disabled in production)
- **Trace logs**: 1 day (disabled in production)

### 7. Storage & Aggregation

Recommended infrastructure (document for reference):
- **Log shipping**: Filebeat or Fluentd to Elasticsearch/OpenSearch
- **Index strategy**: Daily indices `efti-gate-2026.04.22` with rollover
- **Query optimization**: Index `event.action`, `user.id`, `http.request.id`, `efti.identifier.id`

### 8. Correlation IDs

Document flow of request IDs:
- **HTTP API calls**: `X-Request-ID` header → `http.request.id` field (if not provided, generate UUID v4)
- **Gate-to-gate**: Propagate original request ID through SOAP headers
- **Background jobs**: Generate job-specific ID `job-{timestamp}-{uuid}`
- **Database operations**: Log request ID in all SQL-related logs for tracing

### 9. Structured Error Logging

When logging errors, include:
- **Exception type**: `error.type` (e.g., `java.sql.SQLException`)
- **Exception message**: `error.message`
- **Stack trace**: `error.stack_trace` (first 10 frames, full trace in DEBUG)
- **HTTP status**: `http.response.status_code` (400, 404, 500, etc.)
- **Error code**: `efti.error.code` (from error catalog, e.g., `ERR_IDENTIFIER_NOT_FOUND`)

### 10. Compliance & Audit

For GDPR audit trail, include:
- **Data subject**: Vehicle plate or identifier (if applicable)
- **Data access**: Who (user.id), what (event.action), when (@timestamp), why (purpose, if available)
- **Legal basis**: Document when dataset access is based on eFTI regulation authority request
- **Data retention**: Log when identifiers expire and datasets are purged

## Document Structure

Your generated `specs/logging-spec.md` should follow this structure:

```markdown
# eFTI Gate v2.0 Logging Specification

**Version**: 1.0
**Date**: 2026-04-22
**Status**: Development-ready specification

## 1. Overview
- Purpose of logging in eFTI Gate
- Compliance requirements (GDPR, eFTI regulation)
- High-level architecture (application → log files → aggregation → storage)

## 2. JSON Logging Format
- Elastic Common Schema (ECS) 8.x compliance
- Field naming conventions
- Standard fields (always present)
- Optional fields (context-specific)

## 3. Log Levels
- ERROR criteria with examples
- WARN criteria with examples
- INFO criteria with examples
- DEBUG criteria with examples
- TRACE criteria with examples
- Configuration: environment variable `LOG_LEVEL` (default: INFO)

## 4. Logging Scenarios
### 4.1 Platform API Operations
[30+ scenarios with complete JSON examples]

Example:
#### 4.1.1 Identifier Registration (Success)

**Trigger**: POST /v1/platform/identifiers returns 201

**Log entry**:
```json
{
  "@timestamp": "2026-04-22T10:15:30.123Z",
  "log.level": "info",
  "message": "Identifier registered successfully",
  ...
}
```

### 4.2 Authority API Operations
[Scenarios 11-15 with examples]

### 4.3 Admin API Operations
[Scenarios 16-22 with examples]

### 4.4 Gate-to-Gate Communication
[Scenarios 23-27 with examples]

### 4.5 System Events
[Scenarios 28-32 with examples]

### 4.6 Performance Logging
[Scenarios 33-35 with examples]

## 5. Sensitive Data Handling
- Redaction rules with code examples
- Fields requiring hashing
- Dataset content logging (disabled by default, enabled via `LOG_DATASET_CONTENT=true`)

## 6. Correlation & Tracing
- Request ID propagation (HTTP, SOAP, background jobs)
- Example: Trace identifier search from authority request → broadcast → gate responses → final result

## 7. Performance Requirements
- Async logging configuration (Logback example)
- Sampling configuration for high-volume events
- Benchmarks: < 2ms overhead per log entry

## 8. Retention Policies
- Table: Log type → Retention period → Legal basis
- Implementation: Log rotation configuration (e.g., logrotate, Elasticsearch ILM)

## 9. Log Storage & Aggregation
- Recommended ELK stack configuration
- Index strategy and naming
- Query optimization (indexed fields)
- Kibana dashboard examples (optional)

## 10. Configuration
- Environment variables:
  - `LOG_LEVEL` (ERROR|WARN|INFO|DEBUG|TRACE, default: INFO)
  - `LOG_FORMAT` (JSON|TEXT, default: JSON)
  - `LOG_FILE_PATH` (default: /var/log/efti-gate/application.log)
  - `LOG_DATASET_CONTENT` (true|false, default: false)
  - `LOG_SAMPLING_RATE` (0.0-1.0, default: 1.0 = no sampling)
- Logback XML configuration example (reference implementation)

## 11. Testing & Validation
- How to validate JSON schema of log entries
- Example log parsing test (verify all required fields present)
- Integration test: Trigger event → verify log entry created

## 12. Migration from Current Gate
- Current Gate logging patterns (text-based, less structured)
- Migration strategy: Keep text logs during transition period
- Comparison: Current vs. v2.0 logging for same event

## Appendix A: Complete Field Reference
- Table of all ECS fields used in eFTI Gate
- Custom `efti.*` namespace fields
- Data types and examples

## Appendix B: Example Logback Configuration
```xml
<configuration>
  <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    ...
  </appender>
  ...
</configuration>
```

## Appendix C: Example Kibana Queries
- Find all failed identifier registrations
- Trace request by X-Request-ID
- Performance: Queries > 500ms
```

## Quality Requirements

### Zero Tolerance
- ❌ No placeholders: "TBD", "TODO", "example value", "lorem ipsum"
- ❌ No generic examples: "user123", "test@example.com", "localhost:8080"
- ❌ No incomplete scenarios: All 30+ scenarios must have COMPLETE JSON examples

### Realistic Data Requirements
Use realistic Estonian/EU data in all examples:
- **Timestamps**: "2026-04-22T10:15:30.123Z" (current year, valid ISO 8601)
- **Vehicle plates**: "123ABC", "456XYZ", "789DEF" (Estonian format)
- **Gate IDs**: "eu-ee31", "eu-fi01", "eu-de01" (pattern: `eu-{country}{number}`)
- **Platform IDs**: "plt-123", "plt-456" (pattern: `plt-{number}`)
- **Authority IDs**: "aut-001", "aut-002" (pattern: `aut-{number}`)
- **Request IDs**: Valid UUID v4 from `uuidgen` (not 00000000-0000-0000-0000-000000000000)
- **Identifiers**: "https://plt-123.efti.com/550e8400-e29b-41d4-a716-446655440000"
- **Dataset types**: "EU01" (road transport), "EU07" (dangerous goods), etc.
- **HTTP paths**: Exact paths from OpenAPI spec (e.g., "/v1/platform/identifiers", not "/api/identifiers")
- **Error codes**: From error catalog (e.g., "ERR_IDENTIFIER_NOT_FOUND", not "ERROR_001")

### Language Requirements
- **Unambiguous**: "60 seconds" not "timeout period"
- **With units**: "500ms" not "slow query threshold"
- **With defaults**: "default: INFO, configurable via LOG_LEVEL env var"
- **Implementation hints**: "Recommended: Logback AsyncAppender with queue size 512"

### Consistency Requirements
- **Terminology**: Use exact terms from epics/OpenAPI (dataset, identifier, platform, authority, gate)
- **Field names**: ECS standard names (e.g., `@timestamp`, `log.level`, `http.request.id`)
- **Format**: All timestamps ISO 8601, all UUIDs lowercase with hyphens

### Completeness Requirements
- ✅ All 30+ scenarios covered with full JSON examples (not just "see section X")
- ✅ Every JSON example is valid JSON (can be parsed without errors)
- ✅ All fields documented in Appendix A (name, type, description, example)
- ✅ External developer can implement logging by copy-pasting examples (no guesswork)

## Validation Criteria

Before submitting the generated `logging-spec.md`, verify:

### 1. JSON Validity
```bash
# Extract all JSON blocks from markdown and validate
grep -A 50 '```json' specs/logging-spec.md | jq . > /dev/null
# Must succeed with no errors
```

### 2. Completeness Check
- [ ] All 30+ scenarios have complete JSON examples
- [ ] All scenarios reference correct OpenAPI paths (compare with `specs/openapi.yaml`)
- [ ] All scenarios reference correct database tables (compare with `specs/db/schema.sql`)
- [ ] All error scenarios reference error codes (compare with `specs/errors.json`)

### 3. ECS Compliance
- [ ] All log entries include required ECS fields: `@timestamp`, `log.level`, `message`
- [ ] All field names follow ECS conventions (lowercase, dot-separated namespaces)
- [ ] Custom fields use `efti.*` namespace (not mixed with ECS root)

### 4. Realistic Data
- [ ] Zero instances of: "example", "test", "lorem", "TBD", "TODO", "placeholder"
- [ ] All UUIDs are valid v4 format (can generate with `uuidgen` and replace)
- [ ] All timestamps are ISO 8601 format with timezone
- [ ] All vehicle plates use Estonian format

### 5. Cross-Reference Validation
- [ ] HTTP paths match OpenAPI spec exactly
- [ ] User roles match database schema (platforms, authorities, admins)
- [ ] Error codes match error catalog
- [ ] Database table names match schema.sql

### 6. Implementability Test
Give the spec to an independent developer and ask:
- "Can you implement JSON logging for identifier registration without asking questions?"
- Expected answer: YES (must include Logback config, exact field names, example code)

## Output Format

**File**: `specs/logging-spec.md`

**Expected size**: 40-60 pages (A4)

**Format**: GitHub-flavored Markdown with:
- Code blocks for JSON examples (use ```json)
- Code blocks for XML config (use ```xml)
- Tables for field references
- Clear section numbering

## Success Criteria

Your generated specification is complete when:

✅ **All 30+ scenarios documented** with complete, valid JSON examples
✅ **Zero placeholders** (TBD, TODO, example, test)
✅ **All JSON validates** (can be parsed)
✅ **ECS compliant** (all required fields present, correct naming)
✅ **Realistic data** (Estonian plates, valid UUIDs, real timestamps)
✅ **Cross-references correct** (OpenAPI paths, DB tables, error codes match other specs)
✅ **Implementable** (external developer can copy-paste examples and start coding)
✅ **Configuration provided** (Logback XML example, env vars documented)

## Example Output Structure (First 3 Scenarios)

Here's what the first 3 scenarios should look like in your output:

```markdown
## 4. Logging Scenarios

### 4.1 Platform API Operations

#### 4.1.1 Identifier Registration (Success)

**Trigger**: POST /v1/platform/identifiers returns HTTP 201

**Business context**: Platform registers new consignment identifier in local registry. This is a critical business event requiring audit trail.

**Log level**: INFO

**Log entry**:
```json
{
  "@timestamp": "2026-04-22T10:15:30.123Z",
  "log.level": "info",
  "message": "Identifier registered successfully",
  "event.action": "identifier.register",
  "event.outcome": "success",
  "event.duration": 4500000,
  "http": {
    "request.id": "550e8400-e29b-41d4-a716-446655440000",
    "request.method": "POST",
    "request.path": "/v1/platform/identifiers",
    "response.status_code": 201
  },
  "user": {
    "id": "plt-123",
    "type": "platform",
    "role": "platform_operator"
  },
  "efti": {
    "identifier.id": "https://plt-123.efti.com/550e8400-e29b-41d4-a716-446655440000",
    "identifier.type": "EU07",
    "vehicle.plate": "123ABC",
    "vehicle.country": "EE",
    "dataset.size_bytes": 15234,
    "consignment.mode": "ROAD"
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

**Retention**: 90 days (business event)

**Indexed fields**: `event.action`, `user.id`, `efti.identifier.id`, `http.request.id`

---

#### 4.1.2 Identifier Registration (Validation Error)

**Trigger**: POST /v1/platform/identifiers returns HTTP 400 (missing required field)

**Business context**: Platform attempted to register identifier but request failed validation.

**Log level**: WARN

**Log entry**:
```json
{
  "@timestamp": "2026-04-22T10:16:45.789Z",
  "log.level": "warn",
  "message": "Identifier registration failed: missing required field",
  "event.action": "identifier.register",
  "event.outcome": "failure",
  "event.duration": 1200000,
  "http": {
    "request.id": "660f9511-f39c-42e5-b827-557766551111",
    "request.method": "POST",
    "request.path": "/v1/platform/identifiers",
    "request.body.bytes": 542,
    "response.status_code": 400
  },
  "user": {
    "id": "plt-456",
    "type": "platform",
    "role": "platform_operator"
  },
  "error": {
    "type": "ValidationException",
    "message": "Missing required field: datasetId",
    "code": "ERR_VALIDATION_FAILED"
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node2"
}
```

**Retention**: 30 days (error log)

**Indexed fields**: `event.action`, `error.code`, `http.request.id`

---

#### 4.1.3 Identifier Registration (Duplicate Error)

**Trigger**: POST /v1/platform/identifiers returns HTTP 409 (identifier already exists)

**Business context**: Platform attempted to register identifier that already exists in local registry.

**Log level**: WARN

**Log entry**:
```json
{
  "@timestamp": "2026-04-22T10:17:12.456Z",
  "log.level": "warn",
  "message": "Identifier registration failed: duplicate identifier",
  "event.action": "identifier.register",
  "event.outcome": "failure",
  "event.duration": 3200000,
  "http": {
    "request.id": "770fa622-g49d-53f6-c938-668877662222",
    "request.method": "POST",
    "request.path": "/v1/platform/identifiers",
    "response.status_code": 409
  },
  "user": {
    "id": "plt-123",
    "type": "platform",
    "role": "platform_operator"
  },
  "efti": {
    "identifier.id": "https://plt-123.efti.com/550e8400-e29b-41d4-a716-446655440000",
    "identifier.existing_since": "2026-04-22T09:30:00.000Z"
  },
  "error": {
    "type": "ConflictException",
    "message": "Identifier already exists in registry",
    "code": "ERR_IDENTIFIER_DUPLICATE"
  },
  "db": {
    "table": "consignments",
    "operation": "INSERT",
    "constraint_violated": "consignments_identifier_id_key"
  },
  "service.name": "efti-gate",
  "service.version": "2.0.0",
  "host.hostname": "gate-eu-ee31-node1"
}
```

**Retention**: 30 days (error log)

**Indexed fields**: `event.action`, `error.code`, `efti.identifier.id`, `http.request.id`

---

[Continue with remaining 27+ scenarios following same pattern...]
```

## Important Notes

1. **Current Gate patterns**: Review Current Gate source code to understand existing logging. Preserve business logic context (e.g., when broadcast happens, when local-only search).

2. **Realistic context**: Don't just log technical details. Include business context ("broadcast to 12 gates", "local-only search", "admin user modified platform certificate").

3. **Performance context**: For slow operations, include what was slow (query, network call, XML parsing).

4. **GDPR compliance**: Clearly mark which logs are audit trail (7-year retention) vs. operational (30-90 days).

5. **Developer experience**: A developer should be able to:
   - Copy-paste Logback config and start logging
   - Copy-paste JSON structure into their code
   - Understand which fields are required vs. optional
   - Know when to use INFO vs. WARN vs. ERROR

## Final Checklist

Before submitting, verify:
- [ ] File created: `specs/logging-spec.md`
- [ ] Size: 40-60 pages (8,000-12,000 words)
- [ ] All 30+ scenarios: Complete JSON examples
- [ ] JSON validation: All examples parse successfully
- [ ] Zero placeholders: No TBD/TODO/example
- [ ] Realistic data: Estonian plates, valid UUIDs, real timestamps
- [ ] ECS compliance: All required fields present
- [ ] Cross-references: OpenAPI paths, DB tables, error codes match other specs
- [ ] Configuration: Logback XML example provided
- [ ] Implementability: External developer can start coding without questions

---

**Ready to generate?** Provide the input materials and start creating the specification.
