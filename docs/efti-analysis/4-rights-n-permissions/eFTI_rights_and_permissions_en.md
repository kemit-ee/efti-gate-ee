# eFTI Gate — Rights and Access Control Document

| | |
|---|---|
| **Author** | Sten Viljus |
| **Company** | Askend Estonia OÜ |
| **Contact** | sten.viljus@askend.com |

> **v2.0 spetsifikatsioon:** [`../../specs/permissions-matrix.md`](../../specs/permissions-matrix.md) — täielik õiguste maatriks mermaid voodiagrammiga, endpoint-tabelite ja RLS reeglitega.

## 1. Overview

eFTI Gate uses Role-Based Access Control (RBAC) with resource-based filtering. Authentication is performed via the HTTP Authorization header (Basic or Bearer), and access control is annotation-based (`@Access`, `@Public`).

Implementation: `AccessChecker.kt` (Klite `Before` handler).

## 2. Role Definitions

Roles are defined as an enum in `User.kt`:

```kotlin
enum class Role {
  ADMIN, GATE, PLATFORM, AUTHORITY
}
```

### 2.1 ADMIN

| Property | Value |
|----------|-------|
| Description | System administrator |
| Access | All Admin API and eFTI API endpoints |
| Filtering | Super Admin sees all resources; regular Admin sees only resources related to their own roles |
| Restrictions | Consignment deletion only for Super Admin |

**Super Admin** = `isAdmin == true && roles.isEmpty()`. Can view and manage all resources without restrictions.

**Regular Admin** = `isAdmin == true && roles.isNotEmpty()`. Can only see resources related to their own roles (`listFor()` filtering). Write access only to their own Party IDs (`checkWriteAccess()`).

### 2.2 GATE

| Property | Value |
|----------|-------|
| Description | Gate operator |
| Access | Via Admin API — gate management only |
| Filtering | Can only see their own Gate (`GateRegistry.listFor()`) |
| Restrictions | Write/delete only for their own Gate (`checkWriteAccess()`) |

**NB:** The Gate role does not grant direct access to the eFTI API (`/v1/`). Gate-to-gate communication takes place via eDelivery (`/services/`).

### 2.3 PLATFORM

| Property | Value |
|----------|-------|
| Description | Platform operator |
| Access | Via Admin API — platform management; via eFTI API — identifier registration |
| Filtering | Can only see their own Platform (`PlatformRegistry.listFor()`) |
| Restrictions | In the eFTI API, the user must have exactly one Platform role (multiple platforms → error) |

### 2.4 AUTHORITY

| Property | Value |
|----------|-------|
| Description | Competent Authority operator |
| Access | Via Admin API — authority management; via eFTI API — data queries and follow-up |
| Filtering | Can only see their own Authority (`AuthorityRegistry.listFor()`) |
| Restrictions | Dataset subsets are limited by the user's subsets field |

## 2b. Resource List

| Resource | Description | Identifier | Storage |
|----------|-------------|------------|---------|
| **Gate** | Network node (another eFTI gate) | `PartyId<Gate>` (text, e.g. `"POC"`) | `GateRegistry` (in-memory + DB) |
| **Platform** | Data platform that stores datasets | `PartyId<Platform>` (text) | `PlatformRegistry` (in-memory + DB) |
| **Authority** | Competent Authority | `PartyId<Authority>` (text) | `AuthorityRegistry` (in-memory + DB) |
| **User** | System user | `UUID` | `UserRepository` (DB) |
| **Consignment** | Freight consignment data (metadata) | `UUID` (datasetId) | `ConsignmentRepository` (DB) |
| **Identifier** | Transport identifier | `(id, datasetId)` composite | `ConsignmentRepository` (DB) |
| **Dataset** | Full freight consignment data (XML) | UIL: `gateId/platformId/datasetId` | On the platform (gate does not store) |

## 2c. resource.action Permission List

| Permission | Description | Who has it |
|------------|-------------|------------|
| `gate.read` | View gate list and info | ADMIN (filtered) |
| `gate.write` | Add and modify gate | ADMIN + `checkWriteAccess` |
| `gate.delete` | Delete gate | ADMIN + `checkWriteAccess` |
| `gate.ping` | Test gate connectivity | ADMIN + `checkWriteAccess` |
| `platform.read` | View platform list and info | ADMIN (filtered) |
| `platform.write` | Add and modify platform | ADMIN + `checkWriteAccess` |
| `platform.delete` | Delete platform | ADMIN + `checkWriteAccess` |
| `platform.ping` | Test platform connectivity | ADMIN + `checkWriteAccess` |
| `authority.read` | View authority list and info | ADMIN (filtered) |
| `authority.write` | Add and modify authority | ADMIN + `checkWriteAccess` |
| `authority.delete` | Delete authority | ADMIN + `checkWriteAccess` |
| `user.read` | View user list | ADMIN (filtered by own roles) |
| `user.write` | Create/modify user | ADMIN (only within own roles) |
| `user.delete` | Delete user | ADMIN (cannot delete self) |
| `consignment.read` | View consignment list | ADMIN (filtered by platform) |
| `consignment.delete` | Delete consignment | Super Admin |
| `identifier.write` | Register identifiers | PLATFORM (exactly 1 platform) |
| `identifier.read` | Search identifiers (broadcast) | AUTHORITY, ADMIN |
| `dataset.read` | Query dataset (by UIL) | AUTHORITY, ADMIN |
| `followup.write` | Send follow-up message | AUTHORITY, ADMIN |

**Note:** These permissions are derived from code, not configured in the system. Currently, permissions are fixed via roles and `@Access` annotations — there is no separate permissions table in the database.

## 3. User Model

User data structure (`User.kt`):

```kotlin
data class User(
  val name: String,
  val email: Email? = null,
  val subsets: Set<Subset>? = null,
  val isAdmin: Boolean = false,
  val roles: Map<Role, Set<PartyId<*>>> = emptyMap(),
  val id: UUID = randomUUID(),
)
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Unique user ID |
| `name` | String | Display name |
| `email` | Email? | Email address (Basic Auth username) |
| `isAdmin` | Boolean | Admin flag |
| `roles` | Map<Role, Set<PartyId>> | Role → Party ID mapping |
| `subsets` | Set<Subset>? | Allowed eFTI subsets (for AUTHORITY users) |

### 3.1 Access Decision Logic

```
isSuperAdmin = isAdmin && roles.isEmpty()
```

Resource access control:
1. `isSuperAdmin` → access to all
2. `isAdmin && roles.isNotEmpty()` → access only to own Party IDs
3. `!isAdmin` → access only to endpoints matching the `@Access` annotation where the user has the corresponding role

Write access control (`checkWriteAccess()`):
1. `isSuperAdmin` → allowed
2. Otherwise → `entityId` must be in the user's `roles.values.flatten()` set

## 4. Authentication Methods

Implementation: `AccessChecker.before()`.

### 4.1 Basic Auth

```
Authorization: Basic base64(email:password)
```

- Username: email address
- Password: plaintext, checked against hash
- Used by: Admin UI (Svelte) via browser's native authentication dialog
- Lookup: `UserRepository.byCredentials(Email, Password)`

### 4.2 Bearer Auth

```
Authorization: Bearer base64(userId:password)
```

- Username: user UUID
- Password: plaintext, checked against hash
- Used by: API clients (platforms, authorities, gates)
- Lookup: `UserRepository.byCredentials(UUID, Password)`

**NB:** This is not JWT. The token is `base64(userId:password)` — a non-standard format.

### 4.3 X-API-Key (eDelivery)

```
X-API-Key: gateId
```

- Used by: `/services/fast` endpoint (gate-to-gate fast protocol)
- AccessChecker does not check — endpoint has no `@Access` annotation
- **TODO:** API key security needs improvement (see code comment)

### 4.4 Unauthenticated Endpoints

| Endpoint | Annotation | Description |
|----------|-----------|-------------|
| `/health` | none | Health check |
| `/services/msh` | none | eDelivery AS4 MSH (TLS certificate-based) |
| `/services/fast` | none | Gate-to-gate fast protocol (X-API-Key) |
| OpenAPI `/api/openapi`, `/v1/openapi` | `@Public` | API documentation |

## 5. Password Management

- Hashing: `KeyGenerator.hash(password, userId)` → Base64
- Salt: user UUID
- Storage: `secretHash` field in `users` table
- New password: generated via `UserAdminRoutes.save()` (`generateSecret=true`)
- Bearer token issuance: `base64(userId:password)` returned on user creation

## 6. Endpoint ↔ Permission Matrix

### 6.1 Admin API (`/api`)

AccessChecker is registered as a `before` handler on the entire `/api` context.

| Endpoint | Method | Annotation | Who can access | Filtering |
|----------|--------|-----------|----------------|-----------|
| `/api/user` | GET | `@Access(ADMIN)` | Admin | Returns current user |
| `/api/switch` | GET | `@Access(ADMIN)` | Admin | User switching (Basic Auth realm) |
| `/api/users` | GET | `@Access(ADMIN)` | Admin | Super Admin: all; Admin: users with own roles |
| `/api/users` | POST | `@Access(ADMIN)` | Admin | New user can only get creator's roles (except Super Admin) |
| `/api/users/:userId` | DELETE | `@Access(ADMIN)` | Admin | Cannot delete self |
| `/api/gates` | GET | `@Access(ADMIN)` | Admin | Super Admin: all; Admin: own Gates |
| `/api/gates` | POST | `@Access(ADMIN)` | Admin | `checkWriteAccess(gate.id)` |
| `/api/gates/:gateId` | DELETE | `@Access(ADMIN)` | Admin | `checkWriteAccess(gateId)` |
| `/api/gates/:gateId/ping` | POST | `@Access(ADMIN)` | Admin | `checkWriteAccess(gateId)` |
| `/api/gates/own` | GET | `@Access(ADMIN)` | Admin | Own Gate configuration |
| `/api/platforms` | GET | `@Access(ADMIN)` | Admin | Super Admin: all; Admin: own Platforms |
| `/api/platforms` | POST | `@Access(ADMIN)` | Admin | `checkWriteAccess(platform.id)` |
| `/api/platforms/:platformId` | DELETE | `@Access(ADMIN)` | Admin | `checkWriteAccess(platformId)` |
| `/api/platforms/:platformId/ping` | POST | `@Access(ADMIN)` | Admin | `checkWriteAccess(platformId)` |
| `/api/authorities` | GET | `@Access(ADMIN)` | Admin | Super Admin: all; Admin: own Authorities |
| `/api/authorities/:authorityId` | GET | `@Access(ADMIN)` | Admin | `checkWriteAccess(authorityId)` |
| `/api/authorities` | POST | `@Access(ADMIN)` | Admin | `checkWriteAccess(authority.id)` |
| `/api/authorities/:authorityId` | DELETE | `@Access(ADMIN)` | Admin | `checkWriteAccess(authorityId)` |
| `/api/consignments` | GET | `@Access(ADMIN)` | Admin | Super Admin: all; Admin: own platform consignments |
| `/api/consignments/:datasetId` | DELETE | `@Access(ADMIN)` | Super Admin | Only `isSuperAdmin` |

**Important:** All Admin API endpoints are `@Access(ADMIN)`. GATE, PLATFORM, AUTHORITY roles cannot access the Admin API directly. An Admin user who has e.g. `roles = {GATE: ["POC"]}` can only see and manage that Gate — filtering is done via `listFor()` and `checkWriteAccess()`.

### 6.2 eFTI API (`/v1`)

AccessChecker and RequestIdValidator are registered as `before` handlers on the entire `/v1` context.

| Endpoint | Method | Annotation | Who can access | Restrictions |
|----------|--------|-----------|----------------|-------------|
| `/v1/identifiers/:datasetId` | POST | `@Access(PLATFORM)` | Platform | User must have exactly 1 Platform role |
| `/v1/identifiers/:identifier` | GET | `@Access(AUTHORITY, ADMIN)` | Authority, Admin | Identifier search, broadcast to other gates |
| `/v1/dataset/:gateId/:platformId/:datasetId` | GET | `@Access(AUTHORITY, ADMIN)` | Authority, Admin | Dataset query by subsets |
| `/v1/follow-up/:gateId/:platformId/:datasetId/:datasetRequestId` | POST | `@Access(AUTHORITY, ADMIN)` | Authority, Admin | Follow-up message to platform |

### 6.3 eDelivery (`/services`)

AccessChecker is **not** registered on the `/services` context. Security is based on TLS certificates and the X-API-Key header.

| Endpoint | Method | Security | Description |
|----------|--------|----------|-------------|
| `/services/msh` | POST | TLS certificate | eDelivery AS4 message exchange |
| `/services/fast` | POST | `X-API-Key` header | Gate-to-gate fast protocol |

### 6.4 Other

| Endpoint | Method | Security | Description |
|----------|--------|----------|-------------|
| `/health` | GET | none | Health check |
| `/` | GET | none | Admin UI (Svelte SPA) |
| `/api/js-error` | POST | none (`@Hidden`) | Frontend error reports |

## 7. Resource-Based Filtering

Each resource type's registry has a `listFor(user)` method that filters results by the user's roles:

| Registry Class | Logic |
|---------------|-------|
| `GateRegistry.listFor()` | Super Admin: all; otherwise: `roles[GATE].contains(gate.id)` |
| `PlatformRegistry.listFor()` | Super Admin: all; otherwise: `roles[PLATFORM].contains(platform.id)` |
| `AuthorityRegistry.listFor()` | Super Admin: all; otherwise: `roles[AUTHORITY].contains(authority.id)` |
| `ConsignmentRepository.listFor()` | Super Admin: all; otherwise: `platformId` must be in user's PLATFORM roles |
| `UserRepository.byRoles()` | Super Admin: all (filterable); otherwise: users with the user's own roles |

## 8. User Management Rules

User management is done via `UserAdminRoutes`:

1. **Role restriction:** Admin can only create users with their own roles (except Super Admin, who can assign any roles). See `ensureAllowedRoles()`.
2. **Subset validation:** Authority user subsets must be a subset of the Authority's own subsets. See `checkAuthorityUserSubsets()`.
3. **Deletion:** Admin cannot delete themselves. Can only delete users returned by `list()` (i.e., within their own roles).

## 9. Request ID Duplicate Control

`RequestIdValidator` (only in `/v1` context):

- Checks `X-Request-ID` header uniqueness
- Cache: 600 seconds (10 minutes)
- Duplicate → `400 Bad Request`
- Internal request ID format: `internalUUID/externalRequestId`

## 10. Token Structure

### 10.1 Current State (Bearer Token)

The system **does not use JWT**. The current Bearer token uses a non-standard format:

```
Authorization: Bearer base64(userId:password)
```

The token **lacks**:
- Expiration (expiry)
- Issuer
- Signature
- Revoke mechanism
- Scope/claims

The token is valid until the user's password is changed or the user is deleted.

### 10.2 Planned JWT Claims Structure

If Bearer Auth is standardized to JWT (see [Improvement Proposals](eFTI_improvements_en.md) proposal 1.5), the token structure should be:

```json
{
  "header": {
    "alg": "RS256",
    "typ": "JWT",
    "kid": "efti-gate-key-1"
  },
  "payload": {
    "sub": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Platform API User",
    "email": "api@platform.example",
    "iss": "efti-gate-poc",
    "aud": "efti-gate",
    "iat": 1700000000,
    "exp": 1700003600,
    "roles": {
      "PLATFORM": ["platform-ee1"]
    },
    "subsets": null,
    "is_admin": false
  }
}
```

| Claim | Type | Description |
|-------|------|-------------|
| `sub` | UUID | Unique user ID |
| `name` | string | Display name |
| `email` | string? | Email address |
| `iss` | string | Issuer (gate identifier) |
| `aud` | string | Audience |
| `iat` | number | Issued at (Unix timestamp) |
| `exp` | number | Expiration time (Unix timestamp) |
| `roles` | object | `Map<Role, Set<PartyId>>` — same structure as `User.roles` |
| `subsets` | array? | Allowed eFTI subsets (for AUTHORITY users) |
| `is_admin` | boolean | Admin flag |

**Recommendations:**
- Signing: RS256 (asymmetric, so other services can validate without the private key)
- Expiration: 1h (API tokens), 8h (Admin UI session)
- Refresh token: separate opaque token for renewal
- Revoke: token blacklist in Redis (or short `exp` + refresh token revoke)

---

## 11. Security Aspects and Known Deficiencies

### Implemented
- Role-based access control (`@Access` annotations)
- Resource-based filtering (`listFor()`, `checkWriteAccess()`)
- Password hashing (salt = userId)
- Request ID duplicate control (replay protection)
- OPTIONS requests unauthenticated (CORS)

### Deficiencies and TODOs
1. **Bearer Auth format** — non-standard `base64(userId:password)`, not JWT. Token expiration is missing.
2. **X-API-Key security** — `/services/fast` endpoint uses gate ID as API key, validation logic is insufficient (see TODO in code).
3. **checkWriteAccess type check** — `roles.values.flatten().contains(entityId)` does not check role type, only Party ID presence (see TODO in code: "maybe check for type of entityId").
4. **No session management** — each request is authenticated separately (stateless). For Admin UI, this means a Basic Auth dialog on every visit.
5. **Rate limiting** — missing (except for Request ID duplicate control).
6. **Authorization logging** — ForbiddenException throw does not log the denial (only log.error in catch clause).
