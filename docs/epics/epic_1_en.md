# EPIC 1 — User Management and RBAC

> Part of [Theme 1](theme_1_en.md)

**AS A** system administrator  
**I WANT** role-based access control with resource-level filtering  
**SO THAT** each user can only see and manage the resources they are permitted to access

**Reference:** [Permissions Matrix](../specs/permissions-matrix.md) — Complete authorization model and role-based access control specification

#### Acceptance Criteria

##### Role management

**Happy path:**
- [ ] `POST /api/users` — admin creates user; new user receives only creator's roles (except Super Admin); response `201 Created` with user ID
- [ ] `GET /api/users` — Super Admin sees all users; regular admin sees only users within their own roles; response paginated (`limit`, `offset`, `X-Total-Count`)
- [ ] `DELETE /api/users/:userId` — admin deletes another user visible to them; response `204 No Content`
- [ ] A user can be assigned multiple roles and multiple Party IDs under a single role
- [ ] Creating authority user with `subsets` that are subset of Authority's `subsets` → `201 Created`

**Edge cases:**
- [ ] Admin attempts to assign Super Admin role → `403 Forbidden` with `"detail": "Super Admin role cannot be assigned by regular admin"`
- [ ] Admin attempts to delete own account → `409 Conflict` with `"detail": "Cannot delete your own account"`
- [ ] Creating authority user with `subsets` not in Authority's allowed list → `400 Bad Request` with `"detail": "Subset 'EU04' not permitted for authority 'mta@mta.ee'"`
- [ ] `POST /api/users` with duplicate email → `409 Conflict`

**Error handling:**
- [ ] `POST /api/users` with missing required field (e.g. no `roles`) → `400 Bad Request` RFC 7807 with field-level detail
- [ ] All authorisation denials logged: user ID, endpoint, reason, IP address, timestamp

**Technical constraints:**
- [ ] Passwords hashed with bcrypt, salt = user UUID — plaintext never written to database or logs
- [ ] `generateSecret=true` creates new JWT; token value returned **only once** at creation ("Show Once" policy)
- [ ] Bearer token: RFC 7519 JWT signed RS256, claims: `sub`, `roles`, `subsets`, `is_admin`, `exp`, `iss`
- [ ] API tokens expire after 1 hour (configurable via `JWT_EXPIRY_SECONDS`)

**Technical artifacts:**
- [ ] OpenAPI: `POST /api/users`, `GET /api/users`, `DELETE /api/users/{userId}`
- [ ] DB schema: `users`, `user_roles`, `party_ids` tables with FK indexes and English column comments

##### Access control

**Happy path:**
- [ ] Endpoints requiring `ADMIN` role accessible only to admin users → `200 OK`
- [ ] Endpoints requiring `PLATFORM` role accessible only to platform users → `200 OK`
- [ ] Endpoints requiring `AUTHORITY` role accessible only to authority users → `200 OK`
- [ ] Write-access validates both Party ID presence **and** role type

**Edge cases:**
- [ ] GATE user attempts PLATFORM write → `403 Forbidden` with `"detail": "Role type GATE cannot access PLATFORM resource"`
- [ ] Request without Bearer token → `401 Unauthorized` RFC 7807
- [ ] Expired JWT → `401 Unauthorized` with `"detail": "Token expired"`
- [ ] Tampered JWT signature → `401 Unauthorized` with `"detail": "Invalid token signature"` — no internal detail exposed

**Rationale:** `checkWriteAccess()` current bug — does not check role type, allowing GATE user to write to PLATFORM resource. Fix: add role-type assertion before Party ID check.
