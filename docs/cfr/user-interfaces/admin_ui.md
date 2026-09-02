# EPIC 22 — Admin UI

## Changes

- **v1.1** — RBAC is two `users` booleans (`is_admin`, `is_authority`), not a role set. The "Role
  selection and navigation" criteria below (multi-role users, role-switch, role in chrome) no
  longer apply. AC text is issue-synced; correct it in the GitHub issue.
- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: User Interfaces](README.md). Architecture: [user-interfaces/README.md](../../architecture/user-interfaces/README.md) (theme-wide rules) + [user-interfaces/admin_ui.md](../../architecture/user-interfaces/admin_ui.md) (sub-architecture).

<!-- issue-body:begin -->



## Spec anchors

| Contract surface | Reference |
|---|---|
| **API operations consumed** | All Admin routes under `/api/v1/...`: `users`, `gates`, `platforms`, `authorities`, `consignments`, `audit`, plus `POST /api/v1/auth/logout` and `POST /api/js-error` |
| | Full request / response shapes: [`openapi.yaml`](../../specs/openapi.yaml) |
| **Access-check rules** | Admin role + Party-ID scope-IDs enforcement: [`permissions-matrix.md`](../../specs/permissions-matrix.md) |
| **Auth flow** | TARA OIDC (Authority/Admin path) — Epic 2 |
| **Environment** | `DRAFT_SAVE_INTERVAL_SECONDS` (default 30): [`non-functional.md`](../../specs/non-functional.md) §4.1 |
| **Architecture** | [../../architecture/user-interfaces/README.md](../../architecture/user-interfaces/README.md) (theme rules) + [../../architecture/user-interfaces/admin_ui.md](../../architecture/user-interfaces/admin_ui.md) (sub-architecture) |
| | [RA §7.1 Logical Component Layers](../../architecture/eFTI-Gate-Reference-Architecture.md#71-logical-component-layers) |

## Acceptance Criteria

### Authentication

**Business rules:**
- [ ] Browser auth: TARA OIDC (ID-card, Mobile-ID, Smart-ID). HTTP-Basic email/password is **disabled** in production (allowed in dev only via Epic 1 break-glass).
- [ ] Logout calls `POST /api/v1/auth/logout` (denylist the JWT) and triggers the TARA-side logout endpoint.
- [ ] Repeated login failures trigger a temporary lockout — the UI presents a friendly message ("Account temporarily locked. Try again in 15 minutes."), never an error code.
- [ ] Idle-timeout policy is configurable.

### Role selection and navigation

**Business rules:**
- [ ] A user resolving to multiple roles is shown a role-selection screen after login.
- [ ] The active role is visible in the chrome throughout the session.
- [ ] Role can be switched without re-authenticating.
- [ ] A user with exactly one role skips the selection screen and lands on the main view.

### Forms and drafts

**Business rules:**
- [ ] Forms validate inline before submission (no full-page round-trip for trivially-bad input).
- [ ] Long forms auto-save drafts every `DRAFT_SAVE_INTERVAL_SECONDS` (default 30 s). Draft is restored on return.
- [ ] Draft-save failure surfaces a non-blocking warning ("Draft save failed — your data is not lost, but will not be restored on refresh"). It never blocks the user.

### Design and accessibility

**Business rules:**
- [ ] UI uses the **TEDI (Tehik) design system** (https://tedi.tehik.ee/).
- [ ] Default language Estonian; full i18n with a language selector.
- [ ] WCAG 2.2 AA: icon-only buttons carry `aria-label`; modals use `aria-labelledby`; skip-navigation link present; minimum 4.5 : 1 contrast.
- [ ] Accessibility scan (e.g. axe-core) runs in CI on every PR.

### Error handling

**Business rules:**
- [ ] Front-end JS errors are reported to the server via `POST /api/js-error` so they are visible in central logging.
- [ ] Errors shown to the user are friendly messages — never raw stack traces.
- [ ] Error pages include the `requestId` for support correlation.

<!-- issue-body:end -->
