# THEME 1 — Identity and Access


**Objective:** Ensure that all parties interacting with the gate (admins, platforms, authorities, other gates) are authenticated securely and can only access resources they are permitted to access.

**Requirements to address:**

| Area | Current state | Requirement |
|------|--------------|-------------|
| Admin authentication | HTTP Basic Auth | TARA (ID-card, Mobile-ID, Smart-ID) |
| Password-based login | Enabled | Disabled in production |
| X-Road | Missing | Required for government authority access |
| Platform API auth | `base64(id:password)` | RFC 7519 JWT |
| Secrets management | Plain text in `.env` files | Runtime loading (K8s Secret / vault) |
| Write-access control | `checkWriteAccess()` does not check role type | Role-type check enforced |

**Business value:**
- TARA authentication eliminates password management overhead and meets e-government standards (required for production)
- Enables centralised identity management
- GDPR Art. 30 compliance — record of processing with audit log

**Theme done when:**
- [ ] EPIC 1 (RBAC): all roles enforced, write-access type check fixed
- [ ] EPIC 2 (Authentication): TARA login works, Basic Auth disabled in production, mTLS for G2G
- [ ] EPIC 23 (Auth flows): all three auth sequence diagrams documented


## Epics

- [EPIC 1 — User Management and RBAC](epic_1_en.md)
- [EPIC 2 — Authentication](epic_2_en.md)
- [EPIC 23 — Authentication and Access Flows](epic_23_en.md)
