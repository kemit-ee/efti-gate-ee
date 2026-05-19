# Theme: Identity and Access

## Changes

- **2026-05-19** — initial version (formerly `docs/epics/theme_1_en.md`, restructured to story + theme-done-when AC).

> Architecture: [`../../architecture/identity-and-access/README.md`](../../architecture/identity-and-access/README.md). The overarching rules — authorisation snapshot in DB (never JWT), stateless OAuth 2.0 Resource Server, append-only revocation, channel routing, secret loading — are defined there; the AC below verify the gate honours those rules end-to-end.

<!-- issue-body:begin -->

**Objective:** Ensure that all parties interacting with the gate (admins, platforms, authorities, other gates) are authenticated securely and can only access resources they are permitted to access.

## Business value

- TARA authentication eliminates password management overhead and meets e-government standards (required for production).
- Enables centralised identity management.
- GDPR Art. 30 compliance — record of processing with audit log.

## Acceptance Criteria

**Theme done when:**
- [ ] [User Management and RBAC](user_management_and_rbac.md): all roles enforced, write-access type check fixed.
- [ ] [Authentication](authentication.md): TARA login works, Basic Auth disabled in production, mTLS for G2G.
- [ ] [Authentication and Access Flows](authentication_and_access_flows.md): all four auth sequence diagrams documented (Flow 1, Flow 2, Flow 2b, Flow 3).

<!-- issue-body:end -->

## Sub-areas

- [User Management and RBAC](user_management_and_rbac.md)
- [Authentication](authentication.md)
- [Authentication and Access Flows](authentication_and_access_flows.md)
