# PROMPT-06: Generate Permissions Matrix for eFTI Gate v2.0

> [!IMPORTANT]
> **Background prompt — not authoritative.** See [`PROMPT-00-INDEX.md`](PROMPT-00-INDEX.md) for historical context, including how stack references here (Kotlin / Klite / Digilogistika Keskus PoC paths) relate to the v2 spec's stack-open position.

## Context

You are helping create a **complete permissions matrix** for eFTI Gate v2.0, a production system for electronic freight transport information exchange under EU Regulation 2024/2024.

The eFTI Gate has multiple user types with different permissions:
- **Platform operators**: Register/manage identifiers, upload datasets
- **Authority users**: Search identifiers, request datasets
- **Admin users**: Manage platforms, authorities, users, system configuration
- **System users**: Background jobs, health checks, metrics

This specification will be used by external developers during procurement to implement authorization logic consistently across all endpoints.

## Your Task

Generate a **complete permissions matrix document** (`specs/permissions-matrix.md`) that defines:
- User roles and their permissions
- Role × endpoint access matrix (50+ endpoints)
- Row-level security rules (who can see which data)
- SQL filtering examples for each role
- Authorization check pseudocode

## Input Materials Required

Before starting, you must have access to:

1. **OpenAPI Specification**: `specs/openapi.yaml` (from PROMPT-01)
   - All endpoint paths (50+ endpoints)
   - Request/response schemas
   - Security schemes (API keys, OAuth, etc.)

2. **Database Schema**: `specs/db/schema.sql` (from PROMPT-02)
   - User tables: `platforms`, `authorities`, `admin_users`
   - User role enums or tables
   - Foreign key relationships (which platform owns which identifier)

3. **Epic Documentation**: `docs/Askend/efti_full_epics_en.md`
   - Epic 2.x: Platform management (admin permissions)
   - Epic 3.x: Authority management (admin permissions)
   - Epic 4.x: User management (admin assigns roles)
   - Epic 1.x: Data operations (platform/authority permissions)

4. **Current Gate Source Code**: `{CURRENT_GATE_SOURCE}/`
   - Authorization patterns: `gate/src/efti/platforms/PlatformRoutes.kt`
     - Look for user authentication/authorization checks
     - Multi-platform user handling (lines 31-34 TODO)
   - Authority permissions: `gate/src/efti/authorities/AuthorityRoutes.kt`
   - Admin permissions: `gate/src/admin/*AdminRoutes.kt`

5. **Feedback Document**: `docs/Askend/feedback/CRITICAL-SPECIFICATION-GAPS.md`
   - Section 1.7: "Missing Specification File: Permissions Matrix"

## Specification Requirements

### 1. User Roles

Define each role with:
- **Role name**: Exact name used in database and API
- **Description**: What this role represents
- **Authentication method**: API key, OAuth, certificate, etc.
- **Typical use cases**

Example roles:
- `platform_operator`: Platform company user managing identifiers/datasets
- `authority_user`: Competent authority inspector searching/requesting datasets
- `gate_admin`: System administrator managing platforms, authorities, users
- `system`: Internal system processes (background jobs, health checks)
- `anonymous`: Unauthenticated requests (public health check only)

### 2. Permissions Matrix

Create a table: **Role × Endpoint → Access**

Format:
| Endpoint | Method | platform_operator | authority_user | gate_admin | system | anonymous |
|----------|--------|-------------------|----------------|------------|--------|-----------|
| `/v1/platform/identifiers` | POST | ✅ Own platform | ❌ | ❌ | ❌ | ❌ |
| `/v1/platform/identifiers/{id}` | GET | ✅ Own identifier | ❌ | ✅ Read-only | ❌ | ❌ |
| `/v1/authority/search` | POST | ❌ | ✅ | ✅ Read-only | ❌ | ❌ |
| `/health` | GET | ✅ | ✅ | ✅ | ✅ | ✅ |

**Legend**:
- ✅ = Full access
- ✅ Own platform = Only resources owned by user's platform
- ✅ Read-only = GET requests only, no modifications
- ❌ = Access denied (403 Forbidden)

### 3. Row-Level Security Rules

For each role, define **data filtering rules**:

**Example: Platform Operator**
- **Identifiers**: Can only see/modify identifiers registered by their platform
- **SQL filter**: `WHERE platform_id = :current_user_platform_id`
- **Datasets**: Can only access datasets for their own identifiers
- **Search results**: See all results (local + broadcast), but can only request full datasets for own identifiers

**Example: Authority User**
- **Identifiers**: Can search all identifiers (local + broadcast)
- **Datasets**: Can request any dataset (legal authority under eFTI regulation)
- **Platforms/Authorities**: Cannot see platform/authority management data
- **SQL filter**: None for search (global access), audit logging required

**Example: Gate Admin**
- **All data**: Read-only access to all data for troubleshooting
- **Management**: Full CRUD on platforms, authorities, users
- **Identifiers/Datasets**: Read-only (cannot create/modify identifiers)
- **SQL filter**: None (global access)

### 4. Authorization Check Pseudocode

For each endpoint, provide authorization logic:

**Example: POST /v1/platform/identifiers**
```
function authorizeIdentifierRegistration(user, request):
  // 1. User must be authenticated
  if user == null:
    return 401 Unauthorized

  // 2. User must have platform_operator role
  if user.role != "platform_operator":
    return 403 Forbidden "Only platform operators can register identifiers"

  // 3. User's platform must be active
  platform = database.getPlatform(user.platform_id)
  if platform.status != "active":
    return 403 Forbidden "Platform is not active"

  // 4. All checks passed
  return 200 OK
```

**Example: GET /v1/platform/identifiers/{id}**
```
function authorizeIdentifierRetrieval(user, identifierId):
  // 1. User must be authenticated
  if user == null:
    return 401 Unauthorized

  // 2. Fetch identifier from database
  identifier = database.getIdentifier(identifierId)
  if identifier == null:
    return 404 Not Found

  // 3. Authorization based on role
  if user.role == "platform_operator":
    // Platform operators can only see own identifiers
    if identifier.platform_id != user.platform_id:
      return 403 Forbidden "Cannot access identifier from other platform"

  else if user.role == "gate_admin":
    // Admins can see all identifiers (read-only)
    pass

  else:
    // Other roles cannot access this endpoint
    return 403 Forbidden

  // 4. All checks passed
  return 200 OK
```

### 5. Multi-Platform Users

**Special case**: Some users may belong to multiple platforms

**From Current Gate**: `PlatformRoutes.kt` lines 31-34:
```kotlin
// TODO: Multi-platform users
// Currently assumes 1 user = 1 platform
// Need to support: 1 user = multiple platforms
```

**Required in v2.0**:
- User can be associated with multiple platforms
- When registering identifier, user must specify which platform (if multiple)
- SQL filter: `WHERE platform_id IN (:current_user_platform_ids)`
- API: Optional query parameter `?platformId=plt-123` to specify context

**Example authorization**:
```
function authorizeIdentifierRegistration(user, request):
  if user.platforms.length > 1:
    // Multi-platform user must specify which platform
    if request.platformId == null:
      return 400 Bad Request "platformId required for multi-platform users"

    if request.platformId not in user.platforms:
      return 403 Forbidden "User not associated with specified platform"

  else:
    // Single-platform user, use default
    request.platformId = user.platforms[0]

  // Continue with authorization...
```

### 6. Special Permissions

Document special cases:

**Dataset Access (Authority → Platform)**:
- Authority requests dataset via `POST /v1/authority/dataset-request`
- Gate forwards request to platform
- **Platform can deny**: If platform believes authority request is invalid
- **Audit trail**: All dataset requests logged (GDPR requirement)

**Gate-to-Gate Communication**:
- Incoming requests from other gates (via AS4/SOAP)
- **Authentication**: Mutual TLS, gate certificates
- **Authorization**: Any gate can search any gate (eFTI regulation requirement)
- **Rate limiting**: Max 100 requests/minute per gate (prevent abuse)

**Health Check / Metrics**:
- `/health`: Public, no authentication required
- `/metrics`: Requires `system` role (Prometheus scraping)

### 7. Error Responses

Document exact error responses for authorization failures:

**401 Unauthorized** (no authentication):
```json
{
  "type": "https://api.efti.ee/errors/unauthorized",
  "title": "Authentication Required",
  "status": 401,
  "detail": "Valid API key or OAuth token required",
  "instance": "/v1/platform/identifiers",
  "errorCode": "ERR_AUTHENTICATION_REQUIRED",
  "timestamp": "2026-04-22T10:30:45.123Z"
}
```

**403 Forbidden** (authenticated but no permission):
```json
{
  "type": "https://api.efti.ee/errors/forbidden",
  "title": "Insufficient Permissions",
  "status": 403,
  "detail": "Only platform operators can register identifiers",
  "instance": "/v1/platform/identifiers",
  "errorCode": "ERR_INSUFFICIENT_PERMISSIONS",
  "timestamp": "2026-04-22T10:30:45.123Z"
}
```

**403 Forbidden** (resource access denied):
```json
{
  "type": "https://api.efti.ee/errors/forbidden",
  "title": "Resource Access Denied",
  "status": 403,
  "detail": "Cannot access identifier from other platform",
  "instance": "/v1/platform/identifiers/https://plt-456.com/550e8400...",
  "errorCode": "ERR_RESOURCE_ACCESS_DENIED",
  "timestamp": "2026-04-22T10:30:45.123Z",
  "resourceId": "https://plt-456.com/550e8400-e29b-41d4-a716-446655440000",
  "requiredPermission": "platform_operator for platform plt-456"
}
```

## Document Structure

Your generated `specs/permissions-matrix.md` should follow this structure:

```markdown
# eFTI Gate v2.0 Permissions Matrix

**Version**: 1.0
**Date**: 2026-04-22
**Status**: Development-ready specification

## 1. Overview
- Purpose of authorization in eFTI Gate
- Compliance requirements (eFTI regulation, GDPR)
- High-level authorization flow diagram (Mermaid)

## 2. User Roles

### 2.1 platform_operator
- **Description**: Platform company user managing identifiers and datasets
- **Authentication**: API key (format: `plt-{id}:secret-key`) or OAuth
- **Typical use cases**: Register identifier, upload dataset, respond to authority requests
- **Database**: Linked to `platforms.id` via `platform_users.platform_id`

### 2.2 authority_user
- **Description**: Competent authority inspector searching and requesting datasets
- **Authentication**: API key (format: `aut-{id}:secret-key`) or OAuth
- **Typical use cases**: Search identifiers, request datasets, download datasets
- **Database**: Linked to `authorities.id` via `authority_users.authority_id`

### 2.3 gate_admin
- **Description**: System administrator managing platforms, authorities, users
- **Authentication**: OAuth (Google Workspace, Azure AD) or certificate
- **Typical use cases**: Register platforms, manage authorities, view logs, troubleshoot
- **Database**: `admin_users` table

### 2.4 system
- **Description**: Internal system processes (background jobs, health checks)
- **Authentication**: Internal token (not exposed externally)
- **Typical use cases**: Identifier expiration job, gate ping scheduler, registry sync
- **Database**: No user record (service account)

### 2.5 anonymous
- **Description**: Unauthenticated requests
- **Authentication**: None
- **Typical use cases**: Public health check only
- **Database**: N/A

## 3. Permissions Matrix

### 3.1 Platform API Endpoints

| Endpoint | Method | platform_operator | authority_user | gate_admin | system | anonymous |
|----------|--------|-------------------|----------------|------------|--------|-----------|
| `/v1/platform/identifiers` | POST | ✅ Own platform | ❌ | ❌ | ❌ | ❌ |
| `/v1/platform/identifiers` | GET | ✅ Own platform | ❌ | ✅ Read-only | ❌ | ❌ |
| `/v1/platform/identifiers/{id}` | GET | ✅ Own identifier | ❌ | ✅ Read-only | ❌ | ❌ |
| `/v1/platform/identifiers/{id}` | PUT | ✅ Own identifier | ❌ | ❌ | ❌ | ❌ |
| `/v1/platform/identifiers/{id}` | DELETE | ✅ Own identifier | ❌ | ❌ | ❌ | ❌ |
| `/v1/platform/identifiers/{id}/dataset` | PUT | ✅ Own identifier | ❌ | ❌ | ❌ | ❌ |
| `/v1/platform/identifiers/{id}/dataset` | GET | ✅ Own identifier | ❌ | ✅ Read-only | ❌ | ❌ |
| `/v1/platform/dataset-requests` | GET | ✅ Own platform | ❌ | ✅ Read-only | ❌ | ❌ |
| `/v1/platform/dataset-requests/{id}` | GET | ✅ Own request | ❌ | ✅ Read-only | ❌ | ❌ |
| `/v1/platform/dataset-requests/{id}/response` | POST | ✅ Own request | ❌ | ❌ | ❌ | ❌ |

### 3.2 Authority API Endpoints

| Endpoint | Method | platform_operator | authority_user | gate_admin | system | anonymous |
|----------|--------|-------------------|----------------|------------|--------|-----------|
| `/v1/authority/search` | POST | ❌ | ✅ | ✅ Read-only | ❌ | ❌ |
| `/v1/authority/dataset-requests` | POST | ❌ | ✅ | ❌ | ❌ | ❌ |
| `/v1/authority/dataset-requests` | GET | ❌ | ✅ Own authority | ✅ Read-only | ❌ | ❌ |
| `/v1/authority/dataset-requests/{id}` | GET | ❌ | ✅ Own request | ✅ Read-only | ❌ | ❌ |
| `/v1/authority/dataset-requests/{id}/dataset` | GET | ❌ | ✅ Own request | ✅ Read-only | ❌ | ❌ |

### 3.3 Admin API Endpoints

| Endpoint | Method | platform_operator | authority_user | gate_admin | system | anonymous |
|----------|--------|-------------------|----------------|------------|--------|-----------|
| `/v1/admin/platforms` | POST | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/v1/admin/platforms` | GET | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/v1/admin/platforms/{id}` | GET | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/v1/admin/platforms/{id}` | PUT | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/v1/admin/platforms/{id}` | DELETE | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/v1/admin/authorities` | POST | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/v1/admin/authorities` | GET | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/v1/admin/authorities/{id}` | GET | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/v1/admin/authorities/{id}` | PUT | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/v1/admin/authorities/{id}` | DELETE | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/v1/admin/users` | POST | ❌ | ❌ | ✅ | ❌ | ❌ |
| `/v1/admin/users` | GET | ❌ | ❌ | ✅ | ❌ | ❌ |

### 3.4 System Endpoints

| Endpoint | Method | platform_operator | authority_user | gate_admin | system | anonymous |
|----------|--------|-------------------|----------------|------------|--------|-----------|
| `/health` | GET | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/metrics` | GET | ❌ | ❌ | ✅ | ✅ | ❌ |
| `/v1/internal/gates/{id}/ping` | POST | ❌ | ❌ | ❌ | ✅ | ❌ |
| `/v1/internal/identifiers/expire` | POST | ❌ | ❌ | ❌ | ✅ | ❌ |

## 4. Row-Level Security Rules

### 4.1 platform_operator Row-Level Security

**Identifiers**:
```sql
-- Platform operator can only see identifiers from their platform(s)
SELECT * FROM consignments
WHERE platform_id IN (:current_user_platform_ids)
```

**Dataset Requests**:
```sql
-- Platform operator can only see dataset requests for their identifiers
SELECT dr.* FROM dataset_requests dr
JOIN consignments c ON dr.identifier_id = c.identifier_id
WHERE c.platform_id IN (:current_user_platform_ids)
```

**Multi-platform users**:
```sql
-- If user belongs to multiple platforms, show all
SELECT * FROM consignments
WHERE platform_id IN (
  SELECT platform_id FROM platform_users
  WHERE user_id = :current_user_id
)
```

### 4.2 authority_user Row-Level Security

**Identifiers** (search):
```sql
-- Authority can search all identifiers (no filter)
-- But must audit log all searches (GDPR requirement)
SELECT * FROM consignments
WHERE vehicle_plate LIKE :search_pattern
-- No platform_id filter
```

**Dataset Requests**:
```sql
-- Authority can only see their own dataset requests
SELECT * FROM dataset_requests
WHERE authority_id = :current_user_authority_id
```

### 4.3 gate_admin Row-Level Security

**All data** (read-only):
```sql
-- Admin has read-only access to all data (no filter)
SELECT * FROM consignments
-- No WHERE clause
```

**Management tables** (full CRUD):
```sql
-- Admin can manage platforms, authorities, users
INSERT INTO platforms (id, name, ...) VALUES (:id, :name, ...)
UPDATE platforms SET name = :name WHERE id = :id
DELETE FROM platforms WHERE id = :id
```

### 4.4 system Row-Level Security

**Background jobs**:
```sql
-- System can access all data for background jobs
SELECT * FROM consignments WHERE expires_at < NOW()
-- No user-based filtering
```

## 5. Authorization Check Pseudocode

### 5.1 POST /v1/platform/identifiers (Register Identifier)

```
function authorizeIdentifierRegistration(user, request):
  // 1. Authentication check
  if user == null:
    return 401 Unauthorized

  // 2. Role check
  if user.role != "platform_operator":
    return 403 Forbidden "Only platform operators can register identifiers"

  // 3. Platform status check
  platform = database.getPlatform(user.platform_id)
  if platform == null:
    return 500 Internal Server Error "User platform not found"

  if platform.status != "active":
    return 403 Forbidden "Platform is not active"

  // 4. Multi-platform user check
  if user.platforms.length > 1:
    if request.platformId == null:
      return 400 Bad Request "platformId required for multi-platform users"

    if request.platformId not in user.platforms:
      return 403 Forbidden "User not associated with platform " + request.platformId

  // 5. All checks passed
  return 200 OK
```

### 5.2 GET /v1/platform/identifiers/{id} (Retrieve Identifier)

```
function authorizeIdentifierRetrieval(user, identifierId):
  // 1. Authentication check
  if user == null:
    return 401 Unauthorized

  // 2. Fetch identifier
  identifier = database.getIdentifier(identifierId)
  if identifier == null:
    return 404 Not Found

  // 3. Role-based authorization
  if user.role == "platform_operator":
    // Platform operators can only see own identifiers
    if identifier.platform_id not in user.platforms:
      return 403 Forbidden "Cannot access identifier from other platform"

  else if user.role == "gate_admin":
    // Admins can see all identifiers (read-only)
    pass

  else:
    // Other roles not allowed
    return 403 Forbidden "Insufficient permissions"

  // 4. All checks passed
  return 200 OK
```

### 5.3 POST /v1/authority/search (Search Identifiers)

```
function authorizeIdentifierSearch(user, request):
  // 1. Authentication check
  if user == null:
    return 401 Unauthorized

  // 2. Role check
  if user.role not in ["authority_user", "gate_admin"]:
    return 403 Forbidden "Only authority users can search identifiers"

  // 3. Authority status check (if authority_user)
  if user.role == "authority_user":
    authority = database.getAuthority(user.authority_id)
    if authority.status != "active":
      return 403 Forbidden "Authority is not active"

  // 4. Audit logging (GDPR requirement)
  auditLog.record({
    event: "identifier.search",
    user: user.id,
    authority: user.authority_id,
    searchCriteria: request.criteria,
    timestamp: now()
  })

  // 5. All checks passed
  return 200 OK
```

### 5.4 POST /v1/authority/dataset-requests (Request Dataset)

```
function authorizeDatasetRequest(user, request):
  // 1. Authentication check
  if user == null:
    return 401 Unauthorized

  // 2. Role check
  if user.role != "authority_user":
    return 403 Forbidden "Only authority users can request datasets"

  // 3. Authority status check
  authority = database.getAuthority(user.authority_id)
  if authority.status != "active":
    return 403 Forbidden "Authority is not active"

  // 4. Verify identifier exists
  identifier = database.getIdentifier(request.identifierId)
  if identifier == null:
    return 404 Not Found "Identifier not found"

  // 5. Audit logging (GDPR requirement)
  auditLog.record({
    event: "dataset.request",
    user: user.id,
    authority: user.authority_id,
    identifierId: request.identifierId,
    legalBasis: "eFTI Regulation 2024/2024",
    timestamp: now()
  })

  // 6. All checks passed
  return 200 OK
```

### 5.5 POST /v1/admin/platforms (Register Platform)

```
function authorizePlatformRegistration(user, request):
  // 1. Authentication check
  if user == null:
    return 401 Unauthorized

  // 2. Role check
  if user.role != "gate_admin":
    return 403 Forbidden "Only gate admins can register platforms"

  // 3. Validate platform data
  if request.name == null or request.certificate == null:
    return 400 Bad Request "Platform name and certificate required"

  // 4. Audit logging
  auditLog.record({
    event: "platform.create",
    admin: user.id,
    platformName: request.name,
    timestamp: now()
  })

  // 5. All checks passed
  return 200 OK
```

## 6. Multi-Platform Users

### 6.1 Problem Statement

Some platform operators work for multiple platform companies. Example:
- User "john@logistics.com" works for both "Platform A" and "Platform B"
- When registering identifier, must specify which platform

### 6.2 Solution

**Database**:
```sql
-- Many-to-many relationship
CREATE TABLE platform_users (
  user_id UUID REFERENCES users(id),
  platform_id VARCHAR(50) REFERENCES platforms(id),
  PRIMARY KEY (user_id, platform_id)
);
```

**API Request**:
```json
POST /v1/platform/identifiers?platformId=plt-123
{
  "identifierId": "https://plt-123.com/550e8400...",
  "datasetType": "EU07",
  ...
}
```

**Authorization Logic**:
```
if user.platforms.length > 1:
  if request.query.platformId == null:
    return 400 Bad Request "platformId query parameter required for multi-platform users"

  if request.query.platformId not in user.platforms:
    return 403 Forbidden "User not associated with platform " + request.query.platformId

  // Use specified platform
  effectivePlatformId = request.query.platformId

else:
  // Single platform user, use default
  effectivePlatformId = user.platforms[0]
```

### 6.3 SQL Filtering

```sql
-- Single-platform user
SELECT * FROM consignments
WHERE platform_id = :current_user_platform_id

-- Multi-platform user (show all platforms)
SELECT * FROM consignments
WHERE platform_id IN (
  SELECT platform_id FROM platform_users
  WHERE user_id = :current_user_id
)

-- Multi-platform user with context filter (API param ?platformId=plt-123)
SELECT * FROM consignments
WHERE platform_id = :requested_platform_id
  AND :requested_platform_id IN (
    SELECT platform_id FROM platform_users
    WHERE user_id = :current_user_id
  )
```

## 7. Special Permissions

### 7.1 Dataset Access Control (Platform → Authority)

**Scenario**: Authority requests dataset from platform

**Flow**:
1. Authority: POST /v1/authority/dataset-requests
2. Gate: Forwards request to platform
3. Platform: Can approve or deny
4. If approved: Gate provides dataset to authority
5. If denied: Gate returns error to authority

**Platform authorization to deny**:
```
function platformAuthorizeDatasetAccess(datasetRequest):
  // Platform can deny if:
  // - Identifier expired
  // - Dataset deleted
  // - Authority not recognized (edge case)
  // - Platform disputes authority's legal basis (rare)

  if datasetRequest.identifier.expiresAt < now():
    return DENY "Identifier expired"

  if datasetRequest.identifier.datasetDeleted:
    return DENY "Dataset deleted"

  // Otherwise, approve (eFTI regulation requires compliance)
  return APPROVE
```

**Audit logging**: All denials must be logged

### 7.2 Gate-to-Gate Communication

**Authentication**: Mutual TLS (mTLS)
- Each gate has certificate issued by trusted CA
- Incoming requests verified against gate registry

**Authorization**:
- Any gate can search any gate (eFTI regulation requirement)
- Rate limiting: 100 requests/minute per gate

**Rate limit enforcement**:
```
function authorizeGateToGateSearch(sourceGateId, request):
  // 1. Verify gate certificate (mTLS)
  if not verifyCertificate(request.certificate):
    return 403 Forbidden "Invalid gate certificate"

  // 2. Verify gate in registry
  gate = database.getGate(sourceGateId)
  if gate == null or gate.status != "active":
    return 403 Forbidden "Unknown or inactive gate"

  // 3. Rate limiting
  requestCount = rateLimiter.getCount(sourceGateId, window=1minute)
  if requestCount > 100:
    return 429 Too Many Requests "Rate limit exceeded"

  // 4. All checks passed
  return 200 OK
```

### 7.3 Health Check / Metrics

**Health Check** (`/health`):
- Public, no authentication
- Returns: `{"status": "UP"}` or `{"status": "DOWN"}`

**Metrics** (`/metrics`):
- Requires `system` or `gate_admin` role
- Used by Prometheus for monitoring

```
function authorizeMetrics(user):
  if user == null:
    return 401 Unauthorized

  if user.role not in ["system", "gate_admin"]:
    return 403 Forbidden

  return 200 OK
```

## 8. Error Responses

### 8.1 401 Unauthorized (Authentication Required)

```json
{
  "type": "https://api.efti.ee/errors/unauthorized",
  "title": "Authentication Required",
  "status": 401,
  "detail": "Valid API key or OAuth token required. Provide via Authorization header: Bearer {token}",
  "instance": "/v1/platform/identifiers",
  "errorCode": "ERR_AUTHENTICATION_REQUIRED",
  "timestamp": "2026-04-22T10:30:45.123Z"
}
```

### 8.2 403 Forbidden (Insufficient Permissions)

```json
{
  "type": "https://api.efti.ee/errors/forbidden",
  "title": "Insufficient Permissions",
  "status": 403,
  "detail": "Only platform operators can register identifiers. Current role: authority_user",
  "instance": "/v1/platform/identifiers",
  "errorCode": "ERR_INSUFFICIENT_PERMISSIONS",
  "timestamp": "2026-04-22T10:30:45.123Z",
  "requiredRole": "platform_operator",
  "currentRole": "authority_user"
}
```

### 8.3 403 Forbidden (Resource Access Denied)

```json
{
  "type": "https://api.efti.ee/errors/forbidden",
  "title": "Resource Access Denied",
  "status": 403,
  "detail": "Cannot access identifier from other platform. This identifier belongs to platform plt-456, but you are authenticated as platform plt-123",
  "instance": "/v1/platform/identifiers/https://plt-456.com/550e8400-e29b-41d4-a716-446655440000",
  "errorCode": "ERR_RESOURCE_ACCESS_DENIED",
  "timestamp": "2026-04-22T10:30:45.123Z",
  "resourceId": "https://plt-456.com/550e8400-e29b-41d4-a716-446655440000",
  "resourcePlatform": "plt-456",
  "userPlatform": "plt-123"
}
```

### 8.4 429 Too Many Requests (Rate Limit)

```json
{
  "type": "https://api.efti.ee/errors/rate-limit-exceeded",
  "title": "Rate Limit Exceeded",
  "status": 429,
  "detail": "Maximum 100 requests per minute for gate-to-gate communication. Try again in 45 seconds",
  "instance": "/v1/internal/gates/eu-fi01/search",
  "errorCode": "ERR_RATE_LIMIT_EXCEEDED",
  "timestamp": "2026-04-22T10:30:45.123Z",
  "retryAfter": 45,
  "limit": 100,
  "window": "1 minute"
}
```

## 9. Implementation Guidelines

### 9.1 Authentication

**API Key Format**:
- Platform: `plt-{id}:secret-key` (e.g., `plt-123:a1b2c3d4...`)
- Authority: `aut-{id}:secret-key` (e.g., `aut-001:e5f6g7h8...`)

**HTTP Header**:
```
Authorization: Bearer plt-123:a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6
```

**OAuth**:
- For admin users
- Providers: Google Workspace, Azure AD
- Scopes: `gate.admin.read`, `gate.admin.write`

### 9.2 Role Assignment

**Database**:
```sql
-- Platform users
INSERT INTO platform_users (user_id, platform_id, role)
VALUES ('550e8400-...', 'plt-123', 'platform_operator');

-- Authority users
INSERT INTO authority_users (user_id, authority_id, role)
VALUES ('660f9511-...', 'aut-001', 'authority_user');

-- Admin users
INSERT INTO admin_users (user_id, role)
VALUES ('770fa622-...', 'gate_admin');
```

**Role Retrieval**:
```sql
-- Get user's role and associated entities
SELECT
  u.id,
  pu.platform_id,
  au.authority_id,
  COALESCE(pu.role, au.role, adu.role) as role
FROM users u
LEFT JOIN platform_users pu ON u.id = pu.user_id
LEFT JOIN authority_users au ON u.id = au.user_id
LEFT JOIN admin_users adu ON u.id = adu.user_id
WHERE u.api_key = :api_key
```

### 9.3 Authorization Middleware

**Pseudocode**:
```
function authorizationMiddleware(request):
  // 1. Extract authentication token
  token = request.headers["Authorization"]
  if token == null:
    if request.path == "/health":
      return ALLOW  // Public endpoint
    else:
      return 401 Unauthorized

  // 2. Validate token and get user
  user = validateToken(token)
  if user == null:
    return 401 Unauthorized

  // 3. Check endpoint permissions
  endpoint = request.path
  method = request.method

  if not hasPermission(user.role, endpoint, method):
    return 403 Forbidden "Insufficient permissions"

  // 4. Check row-level security (if applicable)
  if requiresRowLevelSecurity(endpoint):
    resourceId = request.pathParams["id"]
    if not canAccessResource(user, resourceId):
      return 403 Forbidden "Resource access denied"

  // 5. All checks passed
  request.user = user  // Attach user to request context
  return ALLOW
```

## 10. Testing Strategy

### 10.1 Unit Tests

Test authorization logic for each endpoint:
```
test_platform_operator_can_register_identifier():
  user = createUser(role="platform_operator", platform="plt-123")
  request = createRequest(POST, "/v1/platform/identifiers")
  result = authorizeIdentifierRegistration(user, request)
  assert result == 200 OK

test_authority_user_cannot_register_identifier():
  user = createUser(role="authority_user", authority="aut-001")
  request = createRequest(POST, "/v1/platform/identifiers")
  result = authorizeIdentifierRegistration(user, request)
  assert result == 403 Forbidden
```

### 10.2 Integration Tests

Test full authorization flow:
```
test_platform_operator_can_only_see_own_identifiers():
  // Setup
  platform1 = createPlatform("plt-123")
  platform2 = createPlatform("plt-456")
  user1 = createUser(role="platform_operator", platform="plt-123")
  user2 = createUser(role="platform_operator", platform="plt-456")
  identifier1 = createIdentifier(platform="plt-123")
  identifier2 = createIdentifier(platform="plt-456")

  // Test: User1 can see identifier1
  response = GET("/v1/platform/identifiers/" + identifier1.id, auth=user1)
  assert response.status == 200

  // Test: User1 cannot see identifier2
  response = GET("/v1/platform/identifiers/" + identifier2.id, auth=user1)
  assert response.status == 403
```

### 10.3 Security Tests

Test for authorization bypass vulnerabilities:
```
test_cannot_bypass_authorization_with_missing_header():
  request = createRequest(GET, "/v1/platform/identifiers")
  request.headers.remove("Authorization")
  response = sendRequest(request)
  assert response.status == 401

test_cannot_access_other_platform_identifier_by_guessing_id():
  user = createUser(role="platform_operator", platform="plt-123")
  otherPlatformIdentifier = "https://plt-456.com/550e8400-..."
  response = GET("/v1/platform/identifiers/" + otherPlatformIdentifier, auth=user)
  assert response.status == 403
```

## 11. Audit Logging

All authorization decisions must be logged for GDPR compliance:

```json
{
  "@timestamp": "2026-04-22T10:30:45.123Z",
  "event.action": "authorization.check",
  "event.outcome": "success",
  "user.id": "plt-123",
  "user.role": "platform_operator",
  "http.request.path": "/v1/platform/identifiers",
  "http.request.method": "POST",
  "authorization.decision": "allow",
  "authorization.reason": "User has platform_operator role and platform is active"
}
```

Failed authorization:
```json
{
  "@timestamp": "2026-04-22T10:31:12.456Z",
  "event.action": "authorization.check",
  "event.outcome": "failure",
  "user.id": "aut-001",
  "user.role": "authority_user",
  "http.request.path": "/v1/platform/identifiers",
  "http.request.method": "POST",
  "authorization.decision": "deny",
  "authorization.reason": "Only platform operators can register identifiers",
  "http.response.status_code": 403
}
```

---

**Document complete**. External developers can implement authorization using this specification.
```

## Quality Requirements

### Zero Tolerance
- ❌ No placeholders: "TBD", "TODO", "example"
- ❌ No generic examples: "user123", "example.com"
- ❌ No incomplete tables: All 50+ endpoints must be in permissions matrix

### Realistic Data Requirements
- **Platform IDs**: "plt-123", "plt-456"
- **Authority IDs**: "aut-001", "aut-002"
- **User IDs**: Valid UUID v4 from `uuidgen`
- **API keys**: Realistic format (not "secret123", "password")
- **Endpoints**: Exact paths from OpenAPI spec

### Language Requirements
- **Unambiguous**: "Only platform operators" not "usually platform operators"
- **With rationale**: "Rate limit 100 req/min to prevent abuse"

### Consistency Requirements
- **Role names**: Exact match between text, tables, SQL, pseudocode
- **Endpoint paths**: Exact match with OpenAPI spec
- **Error codes**: Match error catalog

### Completeness Requirements
- ✅ All 50+ endpoints in permissions matrix
- ✅ All 5 roles documented
- ✅ All authorization checks with pseudocode
- ✅ All error responses with JSON examples
- ✅ SQL filtering examples for each role

## Validation Criteria

Before submitting `permissions-matrix.md`:

### 1. Completeness Check
- [ ] All endpoints from OpenAPI spec in permissions matrix
- [ ] All 5 roles documented
- [ ] All role × endpoint combinations specified (✅/❌)
- [ ] All authorization scenarios with pseudocode

### 2. Cross-Reference Validation
- [ ] Endpoint paths match OpenAPI spec exactly
- [ ] Error codes match error catalog
- [ ] Database table/column names match schema.sql
- [ ] User roles match database schema

### 3. Consistency Check
- [ ] Same role names used throughout document
- [ ] Same endpoint paths used throughout
- [ ] Same error codes used throughout

### 4. JSON Validity
```bash
# Extract all JSON blocks and validate
grep -A 15 '```json' specs/permissions-matrix.md | jq . > /dev/null
```

### 5. SQL Validity
```bash
# Extract SQL blocks and validate syntax
grep -A 5 '```sql' specs/permissions-matrix.md | psql --dry-run
```

## Output Format

**File**: `specs/permissions-matrix.md`

**Expected size**: 25-35 pages (A4)

**Format**: GitHub-flavored Markdown with:
- Tables for permissions matrix
- Code blocks for pseudocode
- Code blocks for SQL (use ```sql)
- Code blocks for JSON errors (use ```json)

## Success Criteria

✅ **All 50+ endpoints** in permissions matrix
✅ **All 5 roles** documented with descriptions
✅ **Zero placeholders** (TBD, TODO, example)
✅ **Realistic data** (platform IDs, authority IDs, UUIDs)
✅ **Cross-references correct** (OpenAPI paths, error codes, DB schema)
✅ **Implementable** (external developer can copy-paste authorization checks)
✅ **SQL examples** for row-level security
✅ **Pseudocode** for all authorization scenarios

---

**Ready to generate?** Provide the input materials and start creating the specification.
