# Architecture: Admin UI

## Changes

- **v1.2** — Gates, platforms and authorities are **read-only** in the Admin UI. The backend
  registry write routes were deleted with [ADR-014](../decisions/014-registry-as-git-folder.md):
  those registries are declared in the git folder `registry/**` and applied at startup, so the UI
  keeps only list + details views for them. `users` and `consignments` remain editable.
- **v1.1** — No role-selection step. Every authenticated user has full access;
  the former `roles TEXT[]` is gone. A caller is authenticated or is not — nothing to select or switch.
- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Admin UI surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/user-interfaces/admin_ui.md`](../../cfr/user-interfaces/admin_ui.md).

## Admin journey at a glance

```mermaid
flowchart LR
    Login[TARA OIDC login<br/>Basic Auth disabled in prod] --> Home[Main view]
    Home --> Manage{Manage what?}
    Manage --> Users[Users<br/>read + write]
    Manage --> Cons[Consignments<br/>read + write]
    Manage --> Audit[Audit log<br/>read]
    Manage --> Read{"Registry views<br/>(read-only)"}
    Read --> Gates[Gates]
    Read --> Platforms[Platforms]
    Read --> Authorities[Authorities]
```

UI uses TEDI (Tehik) design system; WCAG 2.2 AA verified in CI; draft auto-save every 30 s.

## Rationale

The Admin UI is the operational control surface for **runtime** state: users are created and revoked
here, consignments are inspected and deleted, and the audit log is reviewed. TARA OIDC reuses the
same identity primitive as the Authority UI (Epic 21). Disabling Basic Auth in production removes the
only non-federated entry point. TEDI + WCAG 2.2 AA are Estonian e-government baselines; the spec
inherits them rather than re-litigating.

The gates, platforms and authorities screens are deliberately outside that write surface. Those
registries are contractual — a wrong AS4 URL or certificate, or an extra subset on an authority,
redirects signed messages or leaks another organisation's data — so they are changed by committing to
`registry/**`, not by clicking. The UI still shows exactly what the runtime is using (same tables,
same fields, certificates included), which keeps the screens useful for verification without giving
them the power to change anything.

---

