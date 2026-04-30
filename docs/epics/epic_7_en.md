# EPIC 7 — Platform Registry Management (Admin API)

> Part of [Theme 3](theme_3_en.md)

**AS A** system administrator  
**I WANT** to manage the eFTI platform registry  
**SO THAT** platforms can register identifiers and authorities can retrieve datasets

**References:**
- [DB Schema](../specs/db/README.md) — Platform registry schema
- [RA §1 System Actors](../architecture/eFTI-Gate-Reference-Architecture.md#1-system-actors--components) — Platform actor roles and registry context

#### Acceptance Criteria

**Happy path:**
- [ ] `GET /api/platforms` — Super Admin sees all; Admin sees only platforms in their `roles[PLATFORM]` Party IDs; paginated
- [ ] `POST /api/platforms` — adds platform with `name`, `baseUrl`, `supportsSubsetting` flag, optional `eDeliveryCert` → `201 Created`
- [ ] `DELETE /api/platforms/:platformId` → `204 No Content`
- [ ] `POST /api/platforms/:platformId/ping` — checks HTTP connectivity to `baseUrl` → `200 OK` with `responseTimeMs` or `502`
- [ ] eFTI platform without `eDeliveryCert`: REST-only; with `eDeliveryCert`: also callable via eDelivery AS4
- [ ] eFTI platform with `supportsSubsetting=false`: gate applies XSLT subsetter before returning dataset

**Edge cases:**
- [ ] `POST /api/platforms` with `baseUrl` already registered → `409 Conflict`
- [ ] `DELETE` while platform has active identifiers → `409 Conflict` with `"detail": "Platform has 42 active identifiers — delete them first or use force=true"`
- [ ] Ping — platform unreachable after 10 seconds → `502 Bad Gateway` with `"detail": "Platform 'mta-platform-1' did not respond within 10 seconds"`

**Error handling:**
- [ ] Write with non-matching Party ID → `403 Forbidden`

**Technical constraints:**
- [ ] Registry changes propagated to all nodes via LISTEN/NOTIFY within 500 ms

**Technical artifacts:**
- [ ] OpenAPI: `GET /api/platforms`, `POST /api/platforms`, `DELETE /api/platforms/{platformId}`, `POST /api/platforms/{platformId}/ping`
