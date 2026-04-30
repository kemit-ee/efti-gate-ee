# THEME 6 — Security and Compliance


**Objective:** Meet production security requirements, regulatory obligations (GDPR Art. 30, EU Reg. 2024/1942 Art. 5(4)), and ensure an audit trail for all sensitive operations.

**Theme done when:**
- [ ] EPIC 14 (Security): secrets in K8s Secrets, mTLS enforced, rate limiting active, RFC 7807 errors
- [ ] EPIC 15 (Audit/GDPR): audit log immutable, authority queries logged with 7-year retention

**Requirements to address:**

| Area | Current state | Requirement |
|------|--------------|-------------|
| Secrets management | Plain text in `.env` files | Runtime loading (K8s Secret / vault) |
| TLS certificates | Baked into container images | Runtime loading, rotation without redeployment |
| Gate-to-gate auth | `X-API-Key` | Mutual TLS (mTLS) |
| Audit log | Missing | Authority queries logged — GDPR Art. 30 |
| Rate limiting | Missing | Limits at reverse proxy level |
| Write-access control | Role type not checked | Role-type check enforced |

**Business value:**
- Certificate rotation is possible without restarting the application
- Gate-to-gate communication hardened against impersonation
- GDPR Art. 30 compliance (mandatory for production)
- Security incident investigation is possible via audit log


## Epics

- [EPIC 14 — Security](epic_14_en.md)
- [EPIC 15 — Audit and GDPR Compliance](epic_15_en.md)
