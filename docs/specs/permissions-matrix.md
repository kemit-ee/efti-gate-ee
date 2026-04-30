# eFTI Gate v2.0 Permissions Matrix

**Version**: 1.0  
**Date**: 2026-04-23  
**Status**: Development-ready specification  

---

## 1. Overview

### 1.1 Purpose

This document defines the complete authorization model for eFTI Gate v2.0:
- Who can access which endpoints
- What data each role can see (row-level security)
- How authorization checks are implemented

### 1.2 Compliance Requirements

- **eFTI Regulation 2024/1942**: Competent authorities must have unrestricted search access to identifier data; platforms must respond to dataset requests from recognized authorities
- **GDPR Art. 30**: All data access by authorities must be logged with user identity and legal basis (7-year retention)
- **EU gate-to-gate**: Any EU eFTI gate can query any other gate (eFTI regulation requirement)

### 1.3 Authorization Flow

```mermaid
graph TD
    REQ[HTTP Request] --> AC[AccessChecker.before]
    AC --> AUTH{Authorization header?}
    AUTH --No--> PUB{Public endpoint?}
    PUB --Yes--> ALLOW[Allow]
    PUB --No--> UNAUTH[401 Unauthorized]
    AUTH --Yes--> CRED[Validate credentials\nUserRepository.byCredentials]
    CRED --Invalid--> FORBIDDEN[403 Forbidden → 401 challenge]
    CRED --Valid--> ROLE{isAdmin?}
    ROLE --Yes--> ALLOW
    ROLE --No--> ANNOTATION{Access annotation?}
    ANNOTATION --Missing--> ERROR[500 Internal Error\n@Access required]
    ANNOTATION --Present--> MATCH{User role in\nallowed roles?}
    MATCH --No--> FORBIDDEN2[403 Forbidden]
    MATCH --Yes--> RLS[Row-Level Security\ncheck in route handler]
    RLS --Fail--> FORBIDDEN3[403 Forbidden]
    RLS --Pass--> ALLOW
```

---

## 2. User Roles

### 2.1 Role Enum

Roles are defined in `users.Role` enum:

```kotlin
enum class Role {
  ADMIN, GATE, PLATFORM, AUTHORITY
}
```

Each `User` has a `roles: Map<Role, Set<PartyId<*>>>` — a role mapped to one or more party IDs (platform IDs, authority IDs, or gate IDs).

### 2.2 ADMIN

| Attribute | Value |
|-----------|-------|
| **Description** | Gate system administrator. Manages platforms, authorities, users, gates. |
| **`isAdmin` flag** | `true` (bypasses `@Access` role check entirely) |
| **Super Admin** | `isAdmin = true` AND `roles = {}` — unrestricted access to all data |
| **Regular Admin** | `isAdmin = true` AND `roles = {ADMIN: {"eu-ee31"}}` — scoped to gate |
| **Authentication** | Basic Auth (email:password) or Bearer (UUID:secret) |
| **Database** | `users` table, `isAdmin = true` |
| **Typical use cases** | Register platforms/authorities, manage users, view all identifiers, troubleshoot |

### 2.3 PLATFORM

| Attribute | Value |
|-----------|-------|
| **Description** | Platform operator user. Registers identifier metadata in the gate. |
| **Party IDs** | One or more `PlatformId` values in `roles[PLATFORM]` |
| **Authentication** | Bearer token: `Authorization: Bearer {userId}:{secret}` |
| **Database** | `users` table with `roles JSONB` containing `{"PLATFORM": ["demo"]}` |
| **Typical use cases** | POST identifier XML, later: respond to dataset requests |
| **Restriction** | If `roles[PLATFORM].size > 1` → **cannot send identifiers** (multi-platform restriction, `PlatformRoutes.before()`) |

### 2.4 AUTHORITY

| Attribute | Value |
|-----------|-------|
| **Description** | Competent authority inspector. Searches identifiers, requests datasets. |
| **Party IDs** | One or more `AuthorityId` values in `roles[AUTHORITY]` |
| **Authentication** | Bearer token: `Authorization: Bearer {userId}:{secret}` |
| **Database** | `users` table with `roles JSONB` containing `{"AUTHORITY": ["demo"]}` |
| **Subsets** | `users.subsets` — the specific eFTI data subsets this user may request |
| **Typical use cases** | GET /identifiers/:id, GET /dataset/... |

### 2.5 GATE

| Attribute | Value |
|-----------|-------|
| **Description** | Gate-to-gate system user. Used in the fast HTTP protocol (when eDelivery AS4 is not used). |
| **Party IDs** | One or more `GateId` values in `roles[GATE]` |
| **Authentication** | Bearer token (gate system user) or mTLS (AS4 eDelivery) |
| **Database** | `users` table with `roles JSONB` containing `{"GATE": ["eu-fi01"]}` |
| **Typical use cases** | G2G identifier search, G2G dataset request |
| **Restriction** | Cannot write to PLATFORM resources (`User.checkWriteAccess()` validates party ID type) |

---

## 3. Permissions Matrix

### 3.1 Platform API Endpoints

| Endpoint | Method | ADMIN | PLATFORM | AUTHORITY | GATE | Unauthenticated |
|----------|--------|-------|----------|-----------|------|-----------------|
| `/identifiers/:datasetId` | POST | ✅ All | ✅ Own platform only | ❌ | ❌ | ❌ |

**Note**: The Gate v1.x has only one platform-facing endpoint. v2.0 will expand this — all new platform endpoints must require `@Access(PLATFORM)`.

**Row-level security for PLATFORM**:
- User must have exactly 1 platform in `roles[PLATFORM]` (multi-platform → 403)
- The registered consignment's `platformId` is set to `roles[PLATFORM].first()` — platform users can only register under their own platform ID

---

### 3.2 Authority API Endpoints

| Endpoint | Method | ADMIN | PLATFORM | AUTHORITY | GATE | Unauthenticated |
|----------|--------|-------|----------|-----------|------|-----------------|
| `/identifiers/:identifier` | GET | ✅ All data | ❌ | ✅ All data (audit logged) | ❌ | ❌ |
| `/dataset/:gateId/:platformId/:datasetId` | GET | ✅ All data | ❌ | ✅ Own subsets only | ❌ | ❌ |
| `/follow-up/:gateId/:platformId/:datasetId/:datasetRequestId` | POST | ✅ | ❌ | ✅ | ❌ | ❌ |

**Row-level security notes**:

- **`/identifiers/:identifier`**: No data filter — AUTHORITY may search all registered identifiers (local + broadcast). GDPR audit log required.
- **`/dataset/...`**: AUTHORITY subset restriction — `user.subsets` must cover the requested subsets. Gate validates before forwarding to platform.
- **`/follow-up/...`**: AUTHORITY may send follow-ups for any previously-retrieved dataset. No ownership filter.

**`identifierCountryOfOrigin`** field in search results: Set to this gate's country code (`Config.countryCode`). Authorities can see which country's gate returned each result.

---

### 3.3 Admin API Endpoints

| Endpoint | Method | ADMIN | PLATFORM | AUTHORITY | GATE | Unauthenticated |
|----------|--------|-------|----------|-----------|------|-----------------|
| `/admin/user` | GET | ✅ Own user | ❌ | ❌ | ❌ | ❌ |
| `/admin/switch` | GET | ✅ | ❌ | ❌ | ❌ | ❌ |
| `/admin/platforms` (list) | GET/POST | ✅ | ❌ | ❌ | ❌ | ❌ |
| `/admin/platforms/:id` | GET/PUT/DELETE | ✅ | ❌ | ❌ | ❌ | ❌ |
| `/admin/authorities` (list) | GET/POST | ✅ | ❌ | ❌ | ❌ | ❌ |
| `/admin/authorities/:id` | GET/PUT/DELETE | ✅ | ❌ | ❌ | ❌ | ❌ |
| `/admin/gates` (list) | GET/POST | ✅ | ❌ | ❌ | ❌ | ❌ |
| `/admin/gates/:id` | GET/PUT/DELETE | ✅ | ❌ | ❌ | ❌ | ❌ |
| `/admin/users` (list) | GET/POST | ✅ | ❌ | ❌ | ❌ | ❌ |
| `/admin/users/:id` | GET/DELETE | ✅ | ❌ | ❌ | ❌ | ❌ |
| `/admin/consignments` | GET | ✅ | ❌ | ❌ | ❌ | ❌ |
| `/admin/consignments/:id` | GET/DELETE | ✅ | ❌ | ❌ | ❌ | ❌ |

**Row-level security for ADMIN**:
- **Super Admin** (`isAdmin=true`, `roles={}`): Full access to all records
- **Regular Admin** (`isAdmin=true`, `roles={ADMIN: {gateId}}`): Access scoped to gate's resources
- User management: An admin can only create/delete users they can see (same gate scope)
- Admin cannot delete themselves (`DELETE /admin/users/:id` where `:id = current user`)

---

### 3.4 Health & System Endpoints

| Endpoint | Method | ADMIN | PLATFORM | AUTHORITY | GATE | Unauthenticated |
|----------|--------|-------|----------|-----------|------|-----------------|
| `/health` or similar | GET | ✅ | ✅ | ✅ | ✅ | ✅ (Public) |
| OpenAPI/Swagger UI | GET | ✅ | ✅ | ✅ | ✅ | ✅ (Public) |

---

## 4. Row-Level Security Rules

### 4.1 PLATFORM Role

#### Identifier Registration (POST `/identifiers/:datasetId`)

```sql
-- Platform user registers identifier under their own platform ID
-- Gate automatically sets platformId = roles[PLATFORM].first()
INSERT INTO consignments (datasetId, platformId, gateId, xml, ...)
VALUES (:datasetId, :currentUserPlatformId, :thisGateId, :xml, ...);
```

**SQL-level enforcement**: The platform ID is taken from the authenticated user's token — it cannot be overridden by the client.

#### Multi-Platform User Block

```
if user.roles[PLATFORM].size > 1:
    → 403 FORBIDDEN_MULTI_PLATFORM
    → "Create a dedicated single-platform system user"
```

**Rationale**: Current Gate enforces this explicitly in `PlatformRoutes.before()`. v2.0 must maintain this restriction until multi-platform support is fully designed.

---

### 4.2 AUTHORITY Role

#### Identifier Search — No Ownership Filter

```sql
-- Authority can search ALL identifiers across all platforms
SELECT i.id, i.datasetId, i.type, i.countryCode, c.mode, c.dangerousGoods
FROM identifiers i
JOIN consignments c ON i.datasetId = c.datasetId
WHERE i.id = :searchValue
  AND (:type IS NULL OR i.type = :type)
  AND (:countryCode IS NULL OR i.countryCode = :countryCode)
  AND (:mode IS NULL OR c.mode = :mode)
  AND (:dangerousGoods IS NULL OR c.dangerousGoods = :dangerousGoods);
-- No platform_id filter
```

**GDPR requirement**: Log every search with `user.id`, `authority.id`, `identifier.value`, `@timestamp`.

#### Dataset Access — Subset Restriction

```
authority_subsets = users.subsets  (loaded at authentication time)
requested_subsets = query param subsetId[]

for each requested_subset:
    if requested_subset not in authority_subsets:
        → 403 FORBIDDEN_SUBSET
```

**SQL**: No dataset content is stored in the Gate. Gate forwards subset filter to the platform via HTTP query parameter.

---

### 4.3 ADMIN Role

#### All Data Access (Read)

```sql
-- Super Admin: no WHERE filter
SELECT * FROM consignments;
SELECT * FROM identifiers;
SELECT * FROM platforms;
SELECT * FROM authorities;
SELECT * FROM gates;
SELECT * FROM users;
```

#### Write Access Check

```kotlin
// User.checkWriteAccess(entityId: PartyId<*>)
fun checkWriteAccess(entityId: PartyId<*>) {
    if (isSuperAdmin) return  // Super Admin bypasses all checks
    if (!roles.values.flatten().contains(entityId)) throw ForbiddenException("No access to $entityId")
}
```

This ensures that even admin users cannot modify resources of a platform/authority/gate they are not associated with, unless they are Super Admin.

#### User Management Scoping

```sql
-- Regular Admin sees users within their own gate scope
SELECT * FROM users
WHERE EXISTS (
  SELECT 1 FROM jsonb_each_text(roles) role_entry
  WHERE role_entry.value::jsonb ? :admin_gate_id
);
```

---

### 4.4 GATE Role

#### Gate-to-Gate Search Authorization

Gate role users can call authority endpoints (`@Access(AUTHORITY, ADMIN)` in `AuthorityRoutes`). The gate user's `authorityId` is derived from their email if no AUTHORITY role:

```kotlin
// AuthorityRoutes.before()
val authorityId = exchange.user?.run { roles[AUTHORITY]?.firstOrNull() ?: email }
exchange.attr("client", authorityId)
```

**Data filter**: Same as AUTHORITY — no ownership filter on search results.

---

## 5. Authorization Check Pseudocode

### 5.1 Global Authorization Middleware

```
function AccessChecker.before(exchange):
    if exchange.method == OPTIONS:
        return ALLOW  // CORS preflight

    auth = exchange.header("Authorization")

    if auth != null:
        try:
            (username, secret) = base64decode(auth.substringAfter(" ")).split(":", limit=2)
            
            if auth.startsWith("Basic "):
                user = userRepository.byCredentials(Email(username), Password(secret))
            elif auth.startsWith("Bearer "):
                user = userRepository.byCredentials(UUID(username), Password(secret))
            else:
                throw ForbiddenException("Unsupported authorization method")
            
            if user != null:
                checkAccess(exchange, user)
                exchange.attr("user", user)
                userRepository.setAppUser(user)  // Set PostgreSQL app user context
                return ALLOW
        catch Exception e:
            log.error(e)
            throw ForbiddenException("Invalid authorization")
    
    // No auth header
    try:
        checkAccess(exchange, null)
    catch ForbiddenException:
        exchange.header("WWW-Authenticate", "Basic realm=\"eFTI Gate Admin\"")
        throw UnauthorizedException()

function checkAccess(exchange, user):
    access = exchange.route.findAnnotation<Access>()  // @Access(PLATFORM), etc.
    isPublic = (access == null && route.hasAnnotation<Public>()) || route is NotFoundRoute

    if user == null && !isPublic:
        throw ForbiddenException()
    
    if user != null && !isPublic:
        if user.isAdmin:
            return  // Admin bypasses all role checks
        
        if access == null:
            error("@Access annotation required for non-@Public routes")  // Programming error
        
        if access.roles.none { user.roles.containsKey(it) }:
            throw ForbiddenException()
```

---

### 5.2 POST `/identifiers/:datasetId` — Platform Identifier Registration

```
function PlatformRoutes.before(exchange):
    // Runs after AccessChecker (user already validated as PLATFORM role)
    
    platformIds = exchange.user.roles[PLATFORM]
    
    if platformIds == null || platformIds.isEmpty():
        throw UnauthorizedException("User has no platform access")
    
    if platformIds.size > 1:
        throw UnauthorizedException(
            "User has more than one platform registered. " +
            "This user cannot be used as a sender of eFTI data. " +
            "Please create a new system user."
        )
        // Error code: FORBIDDEN_MULTI_PLATFORM
    
    platformId = platformIds.first()
    exchange.attr("client", platformId)
    exchange.attr("platform", platformRegistry[platformId])
    // Platform object injected into route handler via @AttrParam

function postIdentifiers(datasetId: UUID, platform: Platform, body: String):
    // Platform ID comes from authenticated user — client cannot override
    uil = UIL(platform.id, datasetId)
    eftiService.saveIdentifiers(uil, body)
    // 200 OK on success
```

---

### 5.3 GET `/identifiers/:identifier` — Authority Identifier Search

```
function AuthorityRoutes.before(exchange):
    // Runs after AccessChecker (user validated as AUTHORITY or ADMIN)
    
    authorityId = exchange.user?.roles[AUTHORITY]?.firstOrNull() ?: exchange.user?.email
    exchange.attr("client", authorityId)
    // authorityId used for audit logging

function getIdentifiers(identifier: String, modeCode?: Mode, identifierTypes?: String,
                        registrationCountryCode?: CountryCode, dangerousGoodsIndicator?: Boolean,
                        forceBroadcast: Boolean = false, requestId: String, e: HttpExchange):
    
    // No ownership filter — authority can search all identifiers
    query = IdentifiersQuery(Id(identifier, identifierTypes), 
                             registrationCountryCode, modeCode, 
                             dangerousGoodsIndicator, requestId, forceBroadcast)
    
    flow = eftiService.getIdentifiers(query)
    
    // Audit log: who searched, what, when
    auditLog.record(event="identifier.search", user=authorityId, 
                    identifier=identifier, timestamp=now())
    
    if e.accept(MimeTypes.eventStream):
        // SSE response
        e.startEventStream()
        flow.collect { gateResponse ->
            e.send(Event(gateResponse.copy(consignments = null), name = "gate"))
            gateResponse.consignments?.forEach { c -> e.send(Event(c, id = c.uil)) }
        }
        e.send(Event(name = "complete"))
        return emptyList()
    else:
        return flow.toList().flatMap { it.consignments ?: emptyList() }
```

---

### 5.4 GET `/dataset/:gateId/:platformId/:datasetId` — Authority Dataset Request

```
function getDataset(gateId: GateId, platformId: PlatformId, datasetId: UUID,
                    subsetId: List<Subset>, requestId: String, e: HttpExchange):
    
    // 1. Validate subsets against user's permitted subsets
    user = exchange.user
    if user.roles.containsKey(AUTHORITY):
        userSubsets = user.subsets ?: emptySet()
        for subset in subsetId:
            if subset not in userSubsets:
                throw ForbiddenException("Authority not permitted to access subset '$subset'")
                // Error code: FORBIDDEN_SUBSET
    
    // 2. Build UIL and delegate to EftiService
    uil = UIL(platformId, datasetId, gateId)
    (status, payload) = eftiService.getDataset(uil, subsetId, requestId)
    
    // 3. Echo X-Request-ID in response
    e.header(REQUEST_ID_HEADER, requestId)
    e.send(status, payload, MimeTypes.xml)
    
    // Audit log
    auditLog.record(event="dataset.deliver", user=authorityId,
                    datasetId=datasetId, platformId=platformId, 
                    subsets=subsetId, legalBasis="eFTI Regulation 2024/1942",
                    timestamp=now())
```

---

### 5.5 Admin Write Access Check

```
function adminUpdatePlatform(id: PlatformId, request: PlatformUpdate, user: User):
    // 1. Admin role already verified by AccessChecker
    
    // 2. Check write access (not Super Admin = must own the party ID)
    user.checkWriteAccess(id)
    // → throws ForbiddenException("No access to {id}") if user.roles don't include id
    // Error code: FORBIDDEN_WRITE_ACCESS
    
    // 3. Proceed with update
    platformRepository.update(id, request)
    auditLog.record(event="platform.update", admin=user.id, platformId=id)
```

---

### 5.6 Gate-to-Gate Authorization (AS4 eDelivery)

```
// eDelivery AS4 provides transport-level authentication via:
// - Mutual TLS with gate certificates (from gates.eDeliveryCert / gates.tlsCert)
// - AS4 message signing/encryption

function handleIncomingAS4Message(sourceGateId: String, payload: String):
    // 1. Certificate already verified by eDelivery access point (transport layer)
    
    // 2. Verify source gate is registered and active
    gate = gateRegistry[GateId(sourceGateId)]
    if gate == null:
        throw ForbiddenException("Unknown gate: $sourceGateId")
        // Error code: FORBIDDEN
    
    if gate.status != "ONLINE":
        throw ForbiddenException("Gate $sourceGateId is not active")
    
    // 3. Route to appropriate handler based on message type
    when (messageType):
        "identifierQuery" → eftiService.handleIdentifierQuery(payload)
        "uilQuery"        → eftiService.handleUilQuery(payload)
        "followUpRequest" → eftiService.handlePostFollowUpRequest(payload)
        else              → throw BadRequestException("Unknown message type")
    
    // No rate limiting in current Gate — v2.0 should add 100 req/min per source gate
```

---

## 6. Multi-Platform Users

### 6.1 Current State (Gate v1.x)

```kotlin
// PlatformRoutes.before() — lines 33-34
if (platformIds.size > 1) throw UnauthorizedException(
    "User has more than one platform registered. This user cannot be used as a sender of efti data. " +
    "Please create a new system user to be able to send identifiers"
)
```

### 6.2 v2.0 Requirement

Multi-platform users (associated with 2+ platforms) are **blocked from sending identifier data**. They must use a dedicated single-platform system user per platform.

**Database**: The `users.roles JSONB` column already supports multiple platforms:
```json
{"PLATFORM": ["demo", "plt-456"]}
```

**API limitation**: When `roles[PLATFORM].size > 1`, the Gate cannot determine which platform to associate the identifier with. Future v2.x could add a `?platformId=` query parameter to resolve this ambiguity.

### 6.3 Recommended v2.0 Solution

Create one system user per platform (M2M credential):

```sql
-- Platform "demo" system user
INSERT INTO users (name, email, isAdmin, roles, secretHash)
VALUES ('Demo Platform Sender', NULL, false, '{"PLATFORM": ["demo"]}', :hashedSecret);

-- Platform "plt-456" system user  
INSERT INTO users (name, email, isAdmin, roles, secretHash)
VALUES ('Plt-456 Platform Sender', NULL, false, '{"PLATFORM": ["plt-456"]}', :hashedSecret2);
```

---

## 7. Special Permissions

### 7.1 Platform Identifier Scope

When a platform user posts identifier XML:
- The `platformId` in `consignments` table is **always** the authenticated user's platform ID
- The client cannot set a different platform ID — it is derived from the Bearer token
- This prevents one platform from registering identifiers under another platform's name

### 7.2 Authority Subset Restriction

Authorities are registered with a set of permitted eFTI subsets (`authorities.subsets` + `users.subsets`):

```sql
-- Authority 'demo' is permitted all subsets
INSERT INTO authorities (id, subsets, countryCode, name)
VALUES ('demo', array['full'], 'EE', 'Estonian Transport Authority');

-- User linked to authority, with same or narrower subset
INSERT INTO users (name, roles, subsets, secretHash)
VALUES ('Inspector Mägi', '{"AUTHORITY": ["demo"]}', array['full'], :hash);
```

When creating an authority user, their `subsets` must be a **subset** of the authority's `subsets` — otherwise `400 Bad Request`.

### 7.3 Admin Cannot Delete Self

```
function deleteUser(userId: UUID, currentUser: User):
    if userId == currentUser.id:
        throw BadRequestException("Admin cannot delete themselves")
    // Error code: BAD_REQUEST_GENERAL
```

### 7.4 Admin User Visibility

- **Super Admin** (`roles = {}`): Sees all users across all gates
- **Regular Admin** (`roles = {ADMIN: {gateId}}`): Sees only users belonging to their gate scope

### 7.5 `setAppUser()` — PostgreSQL Row Security

After authentication, the Gate sets the PostgreSQL application user context:

```kotlin
userRepository.setAppUser(user)
// Executes: SET LOCAL app.user_id = ':userId'
// Enables PostgreSQL Row Level Security policies if configured
```

This allows database-level enforcement of row-level security as an additional layer.

---

## 8. Error Responses

### 8.1 401 Unauthorized — No Credentials

```json
{
  "type": "https://efti.eu/errors/unauthorized",
  "title": "Authentication Required",
  "status": 401,
  "detail": "Authentication required. Provide credentials via 'Authorization: Bearer {userId}:{secret}' header.",
  "instance": "/identifiers/123ABC"
}
```

**When**: No `Authorization` header on a protected endpoint.  
**HTTP response also includes**: `WWW-Authenticate: Basic realm="eFTI Gate Admin"` header.

### 8.2 401 Unauthorized — Invalid Credentials

```json
{
  "type": "https://efti.eu/errors/unauthorized",
  "title": "Authentication Required",
  "status": 401,
  "detail": "Invalid authorization (must be valid Basic or Bearer token)",
  "instance": "/identifiers/123ABC"
}
```

**When**: Credentials provided but cannot be decoded or do not match any user.  
**Error code**: `TOKEN_INVALID`

### 8.3 403 Forbidden — Wrong Role

```json
{
  "type": "https://efti.eu/errors/forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "Access denied: endpoint requires PLATFORM role",
  "instance": "/identifiers/550e8400-e29b-41d4-a716-446655440000",
  "errorCode": "FORBIDDEN"
}
```

**When**: User is authenticated but `checkAccess()` finds no matching role.

### 8.4 403 Forbidden — No Platform Access

```json
{
  "type": "https://efti.eu/errors/forbidden-no-platform",
  "title": "No Platform Access",
  "status": 403,
  "detail": "User has no platform access. Assign a PLATFORM party ID to this user.",
  "instance": "/identifiers/550e8400-e29b-41d4-a716-446655440000",
  "errorCode": "FORBIDDEN_NO_PLATFORM"
}
```

**When**: User has PLATFORM role in `@Access` but `roles[PLATFORM]` is empty.

### 8.5 403 Forbidden — Multi-Platform User

```json
{
  "type": "https://efti.eu/errors/forbidden-multi-platform",
  "title": "Multi-Platform User Cannot Send",
  "status": 403,
  "detail": "User has more than one platform registered. This user cannot be used as a sender of eFTI data. Please create a new system user.",
  "instance": "/identifiers/550e8400-e29b-41d4-a716-446655440000",
  "errorCode": "FORBIDDEN_MULTI_PLATFORM"
}
```

**When**: `roles[PLATFORM].size > 1`.

### 8.6 403 Forbidden — Write Access Denied

```json
{
  "type": "https://efti.eu/errors/forbidden-write-access",
  "title": "Write Access Denied",
  "status": 403,
  "detail": "No access to plt-456",
  "instance": "/admin/platforms/plt-456",
  "errorCode": "FORBIDDEN_WRITE_ACCESS"
}
```

**When**: `User.checkWriteAccess(entityId)` fails — user's roles don't include the target party ID.

### 8.7 403 Forbidden — Subset Access Denied

```json
{
  "type": "https://efti.eu/errors/forbidden-subset",
  "title": "Subset Access Denied",
  "status": 403,
  "detail": "Authority 'demo' is not permitted to access subset 'full'. Permitted subsets: identifier, dangerous-goods",
  "instance": "/dataset/eu-ee31/demo/550e8400-e29b-41d4-a716-446655440000",
  "errorCode": "FORBIDDEN_SUBSET"
}
```

**When**: Authority requests a subset not in `user.subsets`.

---

## 9. Authentication Implementation

### 9.1 Bearer Token Format

```
Authorization: Bearer {userId}:{secret}

Where:
  userId = UUID v4 (user.id from users table)
  secret = Plain-text secret (hashed in database as secretHash)
  
Example:
  Authorization: Bearer 502d74a0-eb03-11f0-b86c-3c9c0f2eb459:MyPlatformSecret123
```

**Base64 encoding**: The entire `{userId}:{secret}` string is base64-encoded in the header value (same as HTTP Basic Auth format, but using `Bearer` prefix).

### 9.2 Basic Auth Format (Admin Only)

```
Authorization: Basic {base64(email:password)}

Example:
  Authorization: Basic YWRtaW5AZWZpdS5ldTpzZWNyZXQ=
  (base64 of "admin@efti.eu:secret")
```

**Production**: Basic Auth with email/password should be **disabled** in production (to be replaced by TARA in EE extension module).

### 9.3 Password Hashing

```kotlin
// From AccessChecker / UserRepository
// Hash = SHA-256(password + userId as salt)
// secretHash stored in users.secretHash
```

Users created during demo setup:
```sql
-- Admin: password derived from secretHash in users:demo-admin changeset
-- Platform: password derived from secretHash in users:demo-platform changeset
-- Authority: password derived from secretHash in users:demo-authority changeset
```

### 9.4 Role Loading

```sql
-- Get user and their roles from database
SELECT id, name, email, isAdmin, roles, subsets, secretHash
FROM users
WHERE id = :userId::uuid
  AND secretHash = :secretHash;

-- roles column example (JSONB):
-- {"PLATFORM": ["demo"]}
-- {"AUTHORITY": ["demo"]}
-- {"ADMIN": ["eu-ee31"]}
-- {} (Super Admin when isAdmin=true)
```

---

## 10. Testing Strategy

### 10.1 Unit Tests — Authorization Logic

```kotlin
@Test fun `platform user can register identifier`() {
    val user = User(name="Demo Platform", roles=mapOf(PLATFORM to setOf(PlatformId("demo"))))
    val exchange = mockExchange(POST, "/identifiers/550e8400-e29b-41d4-a716-446655440000")
    exchange.route.access = @Access(PLATFORM)
    accessChecker.checkAccess(exchange, user)
    // No exception = pass
}

@Test fun `authority user cannot register identifier`() {
    val user = User(name="Demo Authority", roles=mapOf(AUTHORITY to setOf(AuthorityId("demo"))))
    val exchange = mockExchange(POST, "/identifiers/550e8400-e29b-41d4-a716-446655440000")
    exchange.route.access = @Access(PLATFORM)
    assertThrows<ForbiddenException> { accessChecker.checkAccess(exchange, user) }
}

@Test fun `admin bypasses all role checks`() {
    val user = User(name="Admin", isAdmin=true, roles=emptyMap())
    val exchange = mockExchange(POST, "/identifiers/550e8400-e29b-41d4-a716-446655440000")
    exchange.route.access = @Access(PLATFORM)
    accessChecker.checkAccess(exchange, user)
    // Admin bypasses PLATFORM check — no exception
}

@Test fun `multi-platform user blocked from posting identifiers`() {
    val user = User(name="Multi", roles=mapOf(PLATFORM to setOf(PlatformId("demo"), PlatformId("plt-456"))))
    assertThrows<UnauthorizedException> { platformRoutes.before(mockExchange()) }
}
```

### 10.2 Integration Tests

```kotlin
@Test fun `platform user can only register under own platform ID`() {
    val response = POST("/identifiers/550e8400-e29b-41d4-a716-446655440000",
        auth = "502d74a0-eb03-11f0-b86c-3c9c0f2eb459:secret",
        body = validIdentifierXml)
    assertEquals(200, response.status)
    
    val row = db.query("SELECT platformId FROM consignments WHERE datasetId = ?", "550e8400-...")
    assertEquals("demo", row["platformId"])  // Always set from auth token, not from XML
}

@Test fun `authority can search all platforms`() {
    // Setup: identifiers from two different platforms
    insertConsignment(platformId="demo",    datasetId="550e8400-...")
    insertConsignment(platformId="plt-456", datasetId="660f9511-...")
    insertIdentifier(id="123ABC", datasetId="550e8400-...", type="means")
    insertIdentifier(id="123ABC", datasetId="660f9511-...", type="means")
    
    val response = GET("/identifiers/123ABC", auth = authority_token)
    val results = parseJson(response.body)
    
    assertEquals(2, results.size)  // Both platforms' results returned
}

@Test fun `authority cannot access subset beyond their permissions`() {
    // Authority user permitted only ["identifier"] subset
    val response = GET("/dataset/eu-ee31/demo/550e8400-...?subsetId=full", 
                       auth = limited_authority_token)
    assertEquals(403, response.status)
}
```

### 10.3 Security Tests

```kotlin
@Test fun `request without Authorization header returns 401`() {
    val response = GET("/identifiers/123ABC")
    assertEquals(401, response.status)
    assertNotNull(response.header("WWW-Authenticate"))
}

@Test fun `invalid Bearer token returns 401`() {
    val response = GET("/identifiers/123ABC", 
        headers = mapOf("Authorization" to "Bearer invalid-token"))
    assertEquals(401, response.status)
}

@Test fun `GATE user cannot access PLATFORM endpoint`() {
    val gateUser = createUser(roles = mapOf(GATE to setOf(GateId("eu-fi01"))))
    val response = POST("/identifiers/550e8400-...", auth = gateUser)
    assertEquals(403, response.status)
}
```

---

## 11. Audit Logging

All authorization decisions relevant to GDPR must be logged. See `docs/specs/logging-spec.md` Section 4.3 for full JSON examples.

| Event | Log level | Retention |
|-------|-----------|-----------|
| Authentication success | INFO | 7 years |
| Authentication failure | WARN | 7 years |
| Authorization denied | WARN | 7 years |
| Authority identifier search | INFO | 7 years |
| Authority dataset access | INFO | 7 years |
| Admin creates platform | INFO | 7 years |
| Admin deletes platform | INFO | 7 years |
| Admin creates authority | INFO | 7 years |
| Admin creates user | INFO | 7 years |

---

## Appendix A: Database Schema — Authorization-Relevant Tables

### `users` table

```sql
CREATE TABLE users (
  id         uuid    DEFAULT gen_random_uuid() PRIMARY KEY,
  name       text    NOT NULL,
  email      text    UNIQUE,           -- Used for Basic Auth (admin only)
  isAdmin    boolean NOT NULL DEFAULT false,
  roles      jsonb   NOT NULL DEFAULT '{}',
  -- Example: {"PLATFORM": ["demo"]}, {"AUTHORITY": ["demo"]}, {"GATE": ["eu-fi01"]}
  subsets    text[], -- Permitted eFTI subsets for AUTHORITY users
  secretHash text    NOT NULL,         -- SHA-256(password + userId as salt)
  createdAt  timestamptz NOT NULL DEFAULT now(),
  createdBy  uuid    REFERENCES users(id)
);
```

### `platforms` table

```sql
CREATE TABLE platforms (
  id                 citext  PRIMARY KEY,       -- e.g., "demo", "plt-456"
  baseUrl            text    NOT NULL,          -- Platform HTTP endpoint
  headers            jsonb   DEFAULT '{}',      -- e.g., {"X-Api-Key": "secret"}
  supportsSubsetting boolean NOT NULL DEFAULT true,
  eDeliveryCert      text,                      -- PEM certificate for AS4
  tlsCert            text,                      -- TLS client cert
  createdAt          timestamptz NOT NULL DEFAULT now()
);
```

### `authorities` table

```sql
CREATE TABLE authorities (
  id          citext      PRIMARY KEY,           -- e.g., "demo", "aut-001"
  subsets     text[]      NOT NULL,             -- e.g., ARRAY['full', 'dangerous-goods']
  countryCode varchar(2)  NOT NULL,             -- ISO 3166-1 alpha-2, e.g., "EE"
  name        text                              -- Human-readable name
);
```

### `gates` table

```sql
CREATE TABLE gates (
  id            citext      PRIMARY KEY,          -- e.g., "eu-ee31", "eu-fi01"
  countryCode   varchar(2)  NOT NULL,
  eDeliveryUrl  text        NOT NULL,
  eDeliveryCert text        NOT NULL,
  tlsCert       text,
  status        text        NOT NULL DEFAULT 'ONLINE',
  createdAt     timestamptz NOT NULL DEFAULT now()
);
```

---

## Appendix B: Role Assignment Examples

### Create Single-Platform System User (Recommended)

```sql
-- Create dedicated M2M user for platform "demo"
INSERT INTO users (name, isAdmin, roles, secretHash)
VALUES (
  'Demo Platform M2M User',
  false,
  '{"PLATFORM": ["demo"]}',
  -- secretHash = SHA-256("MySecret" + userId_as_salt)
  'UA7SAxKbE14pBjoRoSpPvV3U2bSn6KPu2dlWz4RIoYQ='
);
```

### Create Authority User

```sql
-- Create authority user linked to authority "demo"
INSERT INTO users (name, email, isAdmin, roles, subsets, secretHash)
VALUES (
  'Inspector Mägi',
  'magi@transiit.ee',
  false,
  '{"AUTHORITY": ["demo"]}',
  ARRAY['full', 'dangerous-goods'],
  'SYKPH8XcG6HGxFVyZX6xLxDUViJldNbNbYtqtvM2pO4='
);
```

### Create Admin User

```sql
-- Regular admin for gate eu-ee31
INSERT INTO users (name, email, isAdmin, roles, secretHash)
VALUES (
  'Gate Admin',
  'admin@efti.ee',
  true,
  '{"ADMIN": ["eu-ee31"]}',
  'UA7SAxKbE14pBjoRoSpPvV3U2bSn6KPu2dlWz4RIoYQ='
);

-- Super Admin (no role restrictions)
INSERT INTO users (name, email, isAdmin, roles, secretHash)
VALUES (
  'Super Admin',
  'superadmin@efti.ee',
  true,
  '{}',
  'UA7SAxKbE14pBjoRoSpPvV3U2bSn6KPu2dlWz4RIoYQ='
);
```

---

## Appendix C: Endpoint-to-Annotation Mapping

| Route class | `@Access` annotation | Roles allowed |
|------------|---------------------|--------------|
| `AdminAuthRoutes` | `@Access(ADMIN)` | ADMIN |
| `AuthorityAdminRoutes` | `@Access(ADMIN)` | ADMIN |
| `ConsignmentAdminRoutes` | `@Access(ADMIN)` | ADMIN |
| `GateAdminRoutes` | `@Access(ADMIN)` | ADMIN |
| `PlatformAdminRoutes` | `@Access(ADMIN)` | ADMIN |
| `UserAdminRoutes` | `@Access(ADMIN)` | ADMIN |
| `PlatformRoutes` (class) | `@Access(PLATFORM)` | PLATFORM only |
| `AuthorityRoutes` (class) | `@Access(AUTHORITY, ADMIN)` | AUTHORITY or ADMIN |

**Important**: `isAdmin = true` bypasses all `@Access` role checks in `AccessChecker.checkAccess()`. Admin users can access any endpoint regardless of the `@Access` annotation.
