# THEME 1 — Identity and Access

> Architecture: [identity-and-access/README.md](../architecture/identity-and-access/README.md). The overarching rules — authorisation snapshot in DB (never JWT), stateless OAuth 2.0 Resource Server, append-only revocation, channel routing, secret loading — are defined there; the AC below verify the gate honours those rules end-to-end.

<!-- issue-body:begin -->

**Objective:** Ensure that all parties interacting with the gate (admins, platforms, authorities, other gates) are authenticated securely and can only access resources they are permitted to access.

## Business value

- TARA authentication eliminates password management overhead and meets e-government standards (required for production).
- Enables centralised identity management.
- GDPR Art. 30 compliance — record of processing with audit log.

## Acceptance Criteria

**Theme done when:**
- [ ] EPIC 1 (RBAC): all roles enforced, write-access type check fixed.
- [ ] EPIC 2 (Authentication): TARA login works, Basic Auth disabled in production, mTLS for G2G.
- [ ] EPIC 23 (Auth flows): all four auth sequence diagrams documented (Flow 1, Flow 2, Flow 2b, Flow 3).

<!-- issue-body:end -->

## Epics

- [EPIC 1 — User Management and RBAC](epic_1_en.md)
- [EPIC 2 — Authentication](epic_2_en.md)
- [EPIC 23 — Authentication and Access Flows](epic_23_en.md)
