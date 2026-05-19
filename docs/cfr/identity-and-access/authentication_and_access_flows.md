# EPIC 23 — Authentication and Access Flows

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme 1](README.md). Architecture: [identity-and-access/README.md](../../architecture/identity-and-access/README.md) (theme-wide rules) + [identity-and-access/authentication_and_access_flows.md](../../architecture/identity-and-access/authentication_and_access_flows.md) (the four canonical flow diagrams).

<!-- issue-body:begin -->

**AS A** technical architect<br>
**I WANT** documented authentication and access flows with sequence diagrams<br>
**SO THAT** integration partners and developers understand exactly how authentication works in each channel type.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **Auth contract** | Epic 1 (RBAC) and Epic 2 (Authentication) — the AC source of truth. This epic provides the **visual** companion. |
| **Access-check rules** | Path-prefix → credential-type routing: [`permissions-matrix.md`](../../specs/permissions-matrix.md) §1.1, §8.1 |
| **Error codes** | `TOKEN_INVALID`, `FORBIDDEN`, `FORBIDDEN_SUBSET`, `FORBIDDEN_NO_PLATFORM`, `FORBIDDEN_MULTI_PLATFORM`, `FORBIDDEN_WRITE_ACCESS` — see [`errors.json`](../../specs/errors.json) |
| **Companion diagrams** | [`seq-12-user-authentication.mmd`](../../specs/diagrams/seq-12-user-authentication.mmd) |
| | [`flow-02-authorization-check.mmd`](../../specs/diagrams/flow-02-authorization-check.mmd) |
| | [`seq-16-mtls-fast-protocol.mmd`](../../specs/diagrams/seq-16-mtls-fast-protocol.mmd) |
| **Architecture** | [identity-and-access/README.md](../../architecture/identity-and-access/README.md) (theme rules) + [identity-and-access/authentication_and_access_flows.md](../../architecture/identity-and-access/authentication_and_access_flows.md) (the four canonical flow diagrams) |

## Acceptance Criteria

- [ ] Every authentication channel is documented with a sequence diagram that covers: credential presentation, validation steps, DB lookups (if any), allow / deny branches, and error codes.
- [ ] The diagrams stay in sync with Epic 1 / Epic 2 AC — any change to the auth contract there must be reflected in the architecture diagrams in the same PR.
- [ ] Flow 1 (Admin UI login, UI-side OIDC → JWT to gate) is documented in [`identity-and-access/authentication_and_access_flows.md`](../../architecture/identity-and-access/authentication_and_access_flows.md) §2.
- [ ] Flow 2 (Authority / Admin API, TARA OIDC JWT) is documented in [`identity-and-access/authentication_and_access_flows.md`](../../architecture/identity-and-access/authentication_and_access_flows.md) §3.
- [ ] Flow 2b (Platform API, mTLS) is documented in [`identity-and-access/authentication_and_access_flows.md`](../../architecture/identity-and-access/authentication_and_access_flows.md) §4.
- [ ] Flow 3 (Gate-to-gate fast protocol, mTLS) is documented in [`identity-and-access/authentication_and_access_flows.md`](../../architecture/identity-and-access/authentication_and_access_flows.md) §5.

<!-- issue-body:end -->
