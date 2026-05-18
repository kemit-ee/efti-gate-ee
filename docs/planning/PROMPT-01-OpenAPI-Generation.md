# LLM Prompt: Generate Complete OpenAPI 3.0 Specification for eFTI Gate v2.0

> [!IMPORTANT]
> **Background prompt — not authoritative.** See [`PROMPT-00-INDEX.md`](PROMPT-00-INDEX.md) for historical context, including how stack references here (Kotlin / Klite / Digilogistika Keskus PoC paths) relate to the v2 spec's stack-open position.

## Context

You are tasked with creating a **complete, production-ready OpenAPI 3.0 specification** for the European Freight Transport Information (eFTI) Gate system. This specification will be used to:
1. Generate mock servers for parallel frontend/backend development
2. Validate API implementations
3. Generate client SDKs
4. Serve as the single source of truth for all API contracts

## Required Input Materials - CHECKLIST

**⚠️ BEFORE STARTING**: Verify you have ALL required inputs below. If any are missing, **STOP** and request them.

### ✅ Mandatory Inputs - Verify Each One

- [ ] **Current Gate Source Code**: `{CURRENT_GATE_SOURCE}/`
  - **Purpose**: Reference for understanding API behavior (NOT to copy blindly)
  - **What to check**:
    - `gate/src/efti/platforms/PlatformRoutes.kt` - Platform API endpoints
    - `gate/src/efti/authorities/AuthorityRoutes.kt` - Authority API endpoints
    - `gate/src/admin/*AdminRoutes.kt` - Admin API endpoints
  - **How to use**: Extract parameter names, understand query patterns, learn error scenarios
  - **Do NOT**: Copy incomplete validation, poor error messages, inconsistent naming

- [ ] **Epic Documentation**: `docs/epics/` (per-epic files)
  - **Purpose**: Business requirements for all endpoints
  - **Must include**: All 22 epics with acceptance criteria
  - **What to extract**: Endpoint requirements, business rules, user roles

- [ ] **Technical Analysis Documents**:
  - [ ] `efti-gate-deep-dive-analysis.md` - Deep dive code analysis
    - **What to extract**: Security patterns, performance considerations, known issues to avoid
  - [ ] `comparison-analysis.md` - Analysis comparison
    - **What to extract**: Consensus on critical patterns, areas of disagreement
  - [ ] `gap-analysis-askend-vs-my-analysis.md` - Production readiness gaps
    - **What to extract**: What's missing, what needs to be added
  - [ ] `comparison-existing-vs-v2.0-spec.md` - Strategic direction
    - **What to extract**: v2.0 goals, technology decisions

- [ ] **Technical Requirements**: Procurement specification "Tarkvara tehnilise analüüsi nõuded"
  - **Purpose**: Mandatory technical constraints
  - **What to extract**: Error format (RFC 7807), authentication (JWT), required headers

- [ ] **EU Regulation 2024/2024**: eFTI subset definitions
  - **Purpose**: Understand EU01-EU07 data subsets
  - **What to extract**: Subset descriptions, compliance requirements

- [ ] **Askend's Business Analysis**: Your own detailed functional requirements
  - **Purpose**: Detailed functional specifications beyond epics
  - **Note**: NOT provided by KeMIT - you must provide your own business analysis
  - **What to include**: User workflows, edge cases, business rules

### ⚠️ Cross-Prompt Dependencies

None - This is the first prompt in the sequence.

### ❌ If Missing Inputs

**DO NOT PROCEED** if any mandatory input is missing. The OpenAPI spec will be incomplete and unusable.

**Action Required**:
1. Request missing analysis documents from KeMIT
2. Prepare your own business analysis if not ready
3. Only start generation when ALL inputs are available

## Your Task

Create file: `openapi.yaml` (2000-3000 lines)

**Design Philosophy**:
- **Design the best possible API** that meets business requirements
- **Learn from Current Gate**: Understand what works and what doesn't
- **Improve** where Current Gate has issues (add missing validation, better error responses, consistent naming)
- **Preserve** business logic (e.g., SSE streaming format, broadcast logic, subset filtering)
- **Document** any changes from Current Gate with rationale in OpenAPI descriptions

## Specification Structure

```yaml
openapi: 3.0.3
info:
  title: eFTI Gate API
  version: 2.0.0
  description: |
    European Freight Transport Information Gate - Complete API Specification

    This API implements:
    - Platform API: For freight platforms to register consignment identifiers
    - Authority API: For competent authorities to search and query datasets
    - Admin API: For gate administrators to manage gates, platforms, authorities, users
    - eDelivery API: For gate-to-gate AS4/SOAP communication

    **Authentication**: All endpoints require JWT Bearer token except /health endpoints

    **EU Regulation Compliance**: EU Regulation 2024/2024 (eFTI data subsets)
  contact:
    name: KeMIT/MKM
    email: efti@mkm.ee
    url: https://github.com/kemit-ee/efti-gate
  license:
    name: MIT
    url: https://opensource.org/licenses/MIT

servers:
  - url: https://api.dev.efti.ee
    description: Development environment
  - url: https://api.staging.efti.ee
    description: Staging environment
  - url: https://api.efti.ee
    description: Production environment

# CONTINUE WITH ALL SECTIONS BELOW
```

## Required Endpoints (50+ total)

### Platform API (7 endpoints) - Tag: "Platform"

**Base path**: `/v1`

1. **POST /v1/identifiers/{datasetId}** - Register consignment identifiers
   - Security: bearerAuth
   - Path param: `datasetId` (UUID, example: "550e8400-e29b-41d4-a716-446655440000")
   - Header: `X-Request-ID` (UUID, required)
   - Request body: `application/xml` (consignment identifier XML per XSD schema)
   - Responses: 200, 400 (invalid XML), 401 (unauthorized), 409 (duplicate request ID), 500
   - Example request body: Estonian truck consignment (plate "123ABC", mode road, dangerous goods false)

2. **DELETE /v1/identifiers/{datasetId}** - Delete consignment (platform initiated)
3. **GET /v1/follow-up/{datasetId}/{requestId}** - Receive follow-up messages from authorities
4. **POST /v1/ping** - Platform availability check
5. **GET /v1/status/{datasetId}** - Check consignment registration status
6. **PUT /v1/identifiers/{datasetId}** - Update consignment metadata
7. **GET /v1/datasets/{datasetId}** - Platform's own dataset retrieval (for subset filtering test)

### Authority API (10 endpoints) - Tag: "Authority"

**Base path**: `/v1`

1. **GET /v1/identifiers/{identifier}** - Search consignments by identifier
   - Query params: `modeCode` (enum: 1,2,3,4,5), `registrationCountryCode`, `dangerousGoodsIndicator`, `forceBroadcast`
   - Header: `Accept` (application/json OR text/event-stream for SSE)
   - Response: JSON array of consignments OR SSE stream with events: "gate", "consignment", "complete"
   - SSE event examples:
     ```
     event: gate
     data: {"gateId":"eu-ee31","responseTimeMs":12}

     event: consignment
     id: https://eu-ee31.efti.ee/https://plt-123.com/550e8400-e29b-41d4-a716-446655440000
     data: {"gateId":"eu-ee31","platformId":"plt-123","datasetId":"550e8400...","vehicle":{"plate":"123ABC","country":"EE"},"mode":3}

     event: complete
     ```

2. **GET /v1/dataset/{gateId}/{platformId}/{datasetId}** - Retrieve full dataset
   - Path params: `gateId` (pattern: "eu-[a-z]{2}[0-9]{2}"), `platformId`, `datasetId` (UUID)
   - Query param: `subsetId` (array, required, enum: EU01-EU07, minItems: 1)
   - Response: 200 (XML dataset), 403 (forbidden subset), 502 (gate/platform unavailable), 504 (timeout)

3. **POST /v1/follow-up/{gateId}/{platformId}/{datasetId}/{datasetRequestId}** - Send follow-up message
4. **GET /v1/search/advanced** - Advanced multi-criteria search
5. **GET /v1/search/dangerous-goods** - Search dangerous goods transports
6. **GET /v1/search/cabotage** - Search cabotage operations (ROAD mode, 14-day retention)
7. **GET /v1/history/{identifier}** - Historical search (inactive consignments)
8. **GET /v1/statistics** - Authority query statistics
9. **POST /v1/bulk-search** - Bulk identifier search (batch processing)
10. **GET /v1/export** - Export search results (CSV/Excel)

### Admin API - Gates (6 endpoints) - Tag: "Admin - Gates"

**Base path**: `/api/v1/gates`

1. **GET /api/v1/gates** - List gates (filtered by user's admin scope)
   - Response example: `[{"id":"eu-ee31","country":"EE","status":"ONLINE","lastPingAt":"2026-04-22T10:20:00Z"}]`

2. **POST /api/v1/gates** - Create gate
3. **GET /api/v1/gates/{gateId}** - Get gate details
4. **PUT /api/v1/gates/{gateId}** - Update gate
5. **DELETE /api/v1/gates/{gateId}** - Delete gate (cannot delete self)
6. **POST /api/v1/gates/{gateId}/ping** - Manual ping trigger

### Admin API - Platforms (5 endpoints) - Tag: "Admin - Platforms"

1. **GET /api/v1/platforms**
2. **POST /api/v1/platforms**
3. **GET /api/v1/platforms/{platformId}**
4. **PUT /api/v1/platforms/{platformId}**
5. **DELETE /api/v1/platforms/{platformId}**

### Admin API - Authorities (5 endpoints) - Tag: "Admin - Authorities"

Similar CRUD operations with subset management

### Admin API - Users (6 endpoints) - Tag: "Admin - Users"

1. **GET /api/v1/users**
2. **POST /api/v1/users** - Create user with role assignment
3. **GET /api/v1/users/{userId}**
4. **PUT /api/v1/users/{userId}**
5. **DELETE /api/v1/users/{userId}** - Cannot delete self
6. **PUT /api/v1/users/{userId}/roles** - Update user roles

### Admin API - Consignments (4 endpoints) - Tag: "Admin - Consignments"

1. **GET /api/v1/consignments** - List consignments (admin view with filters)
2. **GET /api/v1/consignments/{datasetId}**
3. **DELETE /api/v1/consignments/{datasetId}** - Force delete (SUPER_ADMIN only)
4. **GET /api/v1/consignments/statistics** - System statistics

### Admin API - Audit (2 endpoints) - Tag: "Admin - Audit"

1. **GET /api/v1/audit** - Audit log query (SUPER_ADMIN only)
2. **GET /api/v1/audit/export** - Export audit log

### Authentication (2 endpoints) - Tag: "Authentication"

1. **POST /api/v1/auth/token** - Obtain JWT token
   - Request: `{"email":"user@example.com","password":"***"}`
   - Response: `{"token":"eyJhbGc...","expiresAt":"2026-04-22T11:20:00Z"}`

2. **POST /api/v1/auth/logout** - Invalidate token (blacklist)

### Health & Monitoring (3 endpoints) - Tag: "Health", Security: NONE

1. **GET /health/live** - Liveness probe (returns 200 OK if app running)
2. **GET /health/ready** - Readiness probe (returns 200 OK if DB connected)
3. **GET /metrics** - Prometheus metrics (requires ADMIN role)

## Required Components

### Security Schemes

```yaml
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: |
        RFC 7519 JWT token obtained via POST /api/v1/auth/token

        Token MUST contain claims:
        - sub: User ID (UUID)
        - roles: Object mapping role names to party IDs, e.g., {"ADMIN":["eu-ee31"],"PLATFORM":["plt-123"]}
        - subsets: Array of permitted subset IDs (for AUTHORITY role), e.g., ["EU01","EU02","EU05"]
        - exp: Expiration timestamp (Unix epoch, token valid for 1 hour)
        - iss: Issuer (gate ID)

        Example decoded JWT payload:
        {
          "sub": "550e8400-e29b-41d4-a716-446655440000",
          "email": "mta@mta.ee",
          "roles": {"AUTHORITY": ["auth-mta"]},
          "subsets": ["EU01", "EU02", "EU05"],
          "exp": 1714648892,
          "iss": "eu-ee31"
        }
```

### Schemas (30+ required)

**Must include**:
- `ProblemDetails` (RFC 7807 error format)
- `Consignment`, `ConsignmentIdentifier`, `ConsignmentFull`
- `Gate`, `GateCreate`, `GateUpdate`
- `Platform`, `PlatformCreate`, `PlatformUpdate`
- `Authority`, `AuthorityCreate`, `AuthorityUpdate`
- `User`, `UserCreate`, `UserUpdate`
- `UIL` (Unique Identifier Locator)
- `IdentifiersQuery`, `DatasetQuery`, `FollowUpRequest`
- `Subset` (enum: EU01-EU07 with descriptions from EU Regulation 2024/2024)
- `Mode` (enum: maritime=1, rail=2, road=3, air=4, multimodal=5)
- `GateStatus` (enum: ONLINE, OFFLINE, DISABLED)
- `ConsignmentStatus` (enum: active, inactive, deleted)
- `AuditLogEntry`
- `Statistics`

### Responses (Reusable)

```yaml
components:
  responses:
    BadRequest:
      description: Bad Request (client error)
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/ProblemDetails'
          example:
            type: "https://api.efti.ee/errors/bad-request"
            title: "Bad Request"
            status: 400
            detail: "Missing required header: X-Request-ID"
            instance: "/v1/identifiers/123ABC"
            requestId: "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    Unauthorized:
      # ... (similar structure for 401, 403, 404, 409, 429, 500, 502, 503, 504)
```

## Critical Requirements

### 1. Realistic Examples

**MUST use actual Estonian/EU data**:
- ✅ License plates: "123ABC", "456XYZ" (Estonian format)
- ✅ Gate IDs: "eu-ee31", "eu-fi01", "eu-de01"
- ✅ UUIDs: Valid v4 format (use `uuidgen` to generate)
- ✅ Timestamps: "2026-04-22T10:20:35Z" (ISO 8601)
- ✅ UN numbers: 1203 (gasoline), 1950 (aerosols), 1965 (propane)

**DO NOT use**:
- ❌ "string", "number", "test123", "example"
- ❌ "user@example.com", "localhost", "example.com"
- ❌ 00000000-0000-0000-0000-000000000000

### 2. Complete Error Responses

Every endpoint MUST define ALL applicable error codes:
- 400: Bad Request (invalid params, malformed XML, missing headers)
- 401: Unauthorized (missing token, invalid token, expired token)
- 403: Forbidden (insufficient permissions, forbidden subset)
- 404: Not Found (resource doesn't exist)
- 409: Conflict (duplicate request ID, resource already exists)
- 429: Too Many Requests (rate limit exceeded)
- 500: Internal Server Error
- 502: Bad Gateway (upstream gate/platform unreachable)
- 503: Service Unavailable (maintenance mode)
- 504: Gateway Timeout (upstream timeout)

### 3. Reference Current Implementation

Extract exact parameter names, types, and formats from:
- `AuthorityRoutes.kt:42-69` for identifier search endpoint
- `AuthorityRoutes.kt:71-86` for dataset retrieval endpoint
- `PlatformRoutes.kt:41-54` for identifier registration endpoint

Example extraction from `AuthorityRoutes.kt:42`:
```kotlin
@GET("/identifiers/:identifier") @NoTransaction
suspend fun getIdentifiers(
  @PathParam identifier: String,
  @QueryParam modeCode: Mode?,
  @QueryParam identifierTypes: String?,
  @QueryParam registrationCountryCode: CountryCode?,
  @QueryParam dangerousGoodsIndicator: Boolean?,
  @QueryParam forceBroadcast: Boolean = false,
  ...
```

This maps to OpenAPI:
```yaml
/v1/identifiers/{identifier}:
  get:
    parameters:
      - name: identifier
        in: path
        required: true
        schema:
          type: string
          example: "123ABC"
      - name: modeCode
        in: query
        required: false
        schema:
          type: integer
          enum: [1, 2, 3, 4, 5]
          example: 3
      # ... (continue for all parameters)
```

### 4. SSE Streaming Format

For `Accept: text/event-stream` responses, document exact event format:

```yaml
text/event-stream:
  schema:
    type: string
    format: sse
  examples:
    broadcastSearch:
      summary: Identifier search with broadcast to 2 gates
      value: |
        event: gate
        data: {"gateId":"eu-ee31","responseTimeMs":5}

        event: consignment
        id: https://eu-ee31.efti.ee/https://plt-123.com/550e8400-e29b-41d4-a716-446655440000
        data: {"gateId":"eu-ee31","platformId":"plt-123","datasetId":"550e8400-e29b-41d4-a716-446655440000","vehicle":{"plate":"123ABC","country":"EE"},"mode":3,"dangerousGoods":false}

        event: gate
        data: {"gateId":"eu-fi01","responseTimeMs":45}

        event: consignment
        id: https://eu-fi01.efti.fi/https://plt-456.fi/abc-def-...
        data: {"gateId":"eu-fi01","platformId":"plt-456","datasetId":"abc-def-...","vehicle":{"plate":"123ABC","country":"FI"},"mode":3}

        event: gate
        data: {"gateId":"eu-de01","failure":"Connection timeout after 30s"}

        event: complete
```

### 5. Subset Definitions

Include EU Regulation 2024/2024 subset descriptions:

```yaml
Subset:
  type: string
  enum: [EU01, EU02, EU03, EU04, EU05, EU06, EU07]
  description: |
    eFTI data subsets per EU Regulation 2024/2024:
    - EU01: Consignment identification (CMR, AWB, B/L numbers)
    - EU02: Means of transport (vehicle plate, container number)
    - EU03: Transported goods (commodity codes, weight, volume)
    - EU04: Locations (loading/unloading addresses)
    - EU05: Dangerous goods (UN number, ADR class, packaging)
    - EU06: Waste shipment data (Basel Convention)
    - EU07: Consignee/consignor commercial data (names, addresses)
  example: "EU05"
```

## Validation Requirements

Before considering the specification complete:

1. **Swagger UI Test**: Open `openapi.yaml` in Swagger Editor (https://editor.swagger.io)
   - MUST render without errors or warnings
   - All `$ref` links must resolve
   - "Try it out" for each endpoint must show realistic examples

2. **Mock Server Test**: Use Prism (`npx @stoplight/prism-cli mock openapi.yaml`)
   - All endpoints must return mock responses
   - Error responses must follow RFC 7807 format

3. **Coverage Check**:
   - ✅ All 50+ endpoints from epic documentation included
   - ✅ All HTTP methods: GET, POST, PUT, DELETE
   - ✅ All status codes: 200, 201, 400, 401, 403, 404, 409, 429, 500, 502, 503, 504
   - ✅ All security requirements: public (health) vs authenticated (all others)

## Output Format

Create file: `openapi.yaml`

Structure:
```yaml
openapi: 3.0.3
info: { ... }
servers: [ ... ]
tags: [ ... ]
paths:
  # Platform API (7 endpoints)
  /v1/identifiers/{datasetId}:
    post: { ... }
  # Authority API (10 endpoints)
  /v1/identifiers/{identifier}:
    get: { ... }
  # Admin APIs (20+ endpoints)
  # Auth (2 endpoints)
  # Health (3 endpoints)

components:
  securitySchemes: { ... }
  schemas: { ... } # 30+ schemas
  responses: { ... } # 10+ reusable responses
  parameters: { ... } # Reusable path/query params
  examples: { ... } # Named examples

security:
  - bearerAuth: []  # Default for all endpoints except /health
```

## Success Criteria

Your OpenAPI specification is complete when:

✅ Developer can generate working mock server
✅ Swagger UI renders all endpoints with realistic examples
✅ All error responses use RFC 7807 format
✅ All Current Gate endpoints preserved
✅ Zero placeholders ("TBD", "example", "string")
✅ All examples use realistic Estonian/EU data
✅ File size: 2000-3000 lines

## Reference Files to Read

1. `{CURRENT_GATE_SOURCE}/gate/src/efti/platforms/PlatformRoutes.kt`
2. `{CURRENT_GATE_SOURCE}/gate/src/efti/authorities/AuthorityRoutes.kt`
3. `{CURRENT_GATE_SOURCE}/gate/src/admin/*.kt`
4. `docs/epics/` (business requirements)

Generate the complete OpenAPI specification now.
