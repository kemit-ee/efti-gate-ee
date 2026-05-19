# Theme: Security and Compliance

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Architecture: [`../../architecture/security-and-compliance/README.md`](../../architecture/security-and-compliance/README.md). The overarching rules are defined there; the AC below verify the gate honours those rules end-to-end.

<!-- issue-body:begin -->

**Objective:** Meet production security requirements, regulatory obligations (GDPR Art. 30, EU Reg. 2024/1942 Art. 5(4)), and ensure an audit trail for all sensitive operations.

## Business value

- Certificate rotation is possible without restarting the application
- Gate-to-gate communication hardened against impersonation
- GDPR Art. 30 compliance (mandatory for production)
- Security incident investigation is possible via audit log

## Acceptance Criteria

**Theme done when:**
- [ ] EPIC 14 (Security): secrets in K8s Secrets, mTLS enforced, rate limiting active, RFC 7807 errors
- [ ] EPIC 15 (Audit/GDPR): audit log immutable, authority queries logged with 7-year retention

<!-- issue-body:end -->

## Sub-areas

- [Security](security.md)
- [Audit and GDPR Compliance](audit_and_gdpr.md)
