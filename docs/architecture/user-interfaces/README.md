# Architecture: User Interfaces

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Theme-wide architectural rules. Every sub-area below — and every Acceptance Criterion (AC) it carries — must derive from or at minimum **not conflict with** the rules stated here. AC live in the corresponding sub-area files under [`docs/cfr/user-interfaces/`](../../cfr/user-interfaces/); this document describes the *contract those AC implement*.

**System-wide reference:** [eFTI Gate Reference Architecture](../eFTI-Gate-Reference-Architecture.md). This document narrows the system-wide rules to the User Interfaces surface.

**Sub-architectures in this theme** (each is the architectural surface for the AC tracked in the linked epic):

- [Authority UI (AAP — H2M Interface)](authority_ui.md) — AC: [`docs/cfr/user-interfaces/authority_ui.md`](../../cfr/user-interfaces/authority_ui.md)
- [Admin UI](admin_ui.md) — AC: [`docs/cfr/user-interfaces/admin_ui.md`](../../cfr/user-interfaces/admin_ui.md)

---

## Overarching rules

These are the cross-cutting invariants every sub-area in this theme derives from. AC bullets in the CFR files specialise them to specific endpoints, error codes, or DB state.

### 1.1 UI is a thin client; business logic lives in the gate

Both the Authority UI and the Admin UI are pure presentation surfaces over the gate's HTTP API. They hold no business rules, no permission logic, no data-validation logic beyond client-side ergonomics (e.g. required-field hints). Anything that affects state-of-the-world goes through the gate's API and is re-validated server-side. A UI bug never leads to a corrupted gate state — at worst it leads to a confusing screen.

### 1.2 OIDC code-exchange happens in the browser, not in the gate

For TARA login, the OIDC `authorize` redirect and code-exchange happen in the UI (browser-side, public client). The gate receives only the resulting ID token from the UI and exchanges it for a gate-issued JWT (see [Identity & Access §2 TARA OIDC pipeline](../identity-and-access/authentication.md)). The gate is a Resource Server, not an OAuth client — it never holds the `client_secret` on the request hot path.

### 1.3 JWT is stored in `sessionStorage`, never in a cookie

The UI persists the gate-issued JWT in `sessionStorage` and sends it as `Authorization: Bearer ...` on every API call. There is no `session_id` cookie, no CSRF token. This matches the [Identity & Access §1.2 stateless Resource Server rule](../identity-and-access/README.md). Logout drops the local copy and calls `POST /api/v1/auth/logout` to add the JWT to the denylist for refresh purposes.

### 1.4 Accessibility = WCAG 2.1 AA

Both UIs target **WCAG 2.1 level AA** compliance. Specifically: keyboard-only navigation works on every interactive path; screen-reader semantic markup is used (no `<div onClick>` for buttons); colour contrast meets the AA contrast ratios; form errors are programmatically associated with their fields. The accessibility audit is part of the release gate, not a post-launch checklist.

### 1.5 Static assets are versioned and immutable

Every static asset (JS bundle, CSS, font, icon) is published with a content-hash in its filename and a `Cache-Control: public, max-age=31536000, immutable` header. The HTML entrypoint is served with `Cache-Control: no-cache`. This means a new release flips the entrypoint instantly; old asset URLs continue to serve correctly until clients refresh. Static-asset hosting is operator-owned (the gate process doesn't serve them) — typically a CDN or object store fronted by the same TLS proxy that fronts the API.

### 1.6 Front-end JS errors go to the gate, then to central logging

Unhandled JS errors are caught and reported via `POST /api/js-error` so they show up in central observability storage alongside server-side errors (see [Observability §1.6](../observability/README.md)). The user-visible message is friendly (no raw stack traces); the diagnostic payload (stack trace, browser info, current route, `X-Request-ID`) is included in the body for support correlation.
