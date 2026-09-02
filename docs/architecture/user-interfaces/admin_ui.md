# Architecture: Admin UI

## Changes

- **v1.1** — No role-selection step. RBAC is two `users` booleans (`is_admin`, `is_authority`);
  the former `roles TEXT[]` is gone. A caller is an admin or is not — nothing to select or switch.
  The user form carries an "Admin" and an "Authority" checkbox.
- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Admin UI surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/user-interfaces/admin_ui.md`](../../cfr/user-interfaces/admin_ui.md).

## Admin journey at a glance

```mermaid
flowchart LR
    Login[TARA OIDC login<br/>Basic Auth disabled in prod] --> Home[Main view]
    Home --> Manage{Manage what?}
    Manage --> Users[Users<br/>/api/v1/users]
    Manage --> Gates[Gates<br/>/api/v1/gates]
    Manage --> Platforms[Platforms<br/>/api/v1/platforms]
    Manage --> Authorities[Authorities<br/>/api/v1/authorities]
    Manage --> Cons[Consignments<br/>/api/v1/consignments]
    Manage --> Audit[Audit log<br/>/api/v1/audit]
```

UI uses TEDI (Tehik) design system; WCAG 2.2 AA verified in CI; draft auto-save every 30 s.

## Rationale

The Admin UI is the operational control surface — every registry mutation, user creation, and audit-log review goes through it. TARA OIDC reuses the same identity primitive as the Authority UI (Epic 21). Disabling Basic Auth in production removes the only non-federated entry point. TEDI + WCAG 2.2 AA are Estonian e-government baselines; the spec inherits them rather than re-litigating.

---

