# EPIC 21 — Authority UI (AAP — H2M Interface)

> Part of [Theme 9](theme_9_en.md)

**AS A** competent authority officer  
**I WANT** a web interface for searching identifiers and viewing datasets  
**SO THAT** I can conduct roadside inspections without a separate IT system

**References:**
- [Permissions Matrix](../specs/permissions-matrix.md) — Authority subset access permissions
- [RA §9.2 Authority API (AAP)](../architecture/eFTI-Gate-Reference-Architecture.md#92-authority-api-aap) — AAP endpoint reference — H2M and M2M interface

#### Acceptance Criteria

##### Authentication

**Happy path:**
- [ ] Authority UI uses OIDC via TARA; supported: ID card, Mobile-ID, Smart-ID
- [ ] TARA personal identification code mapped to authority user account (e.g. PPA officer → PPA Authority role)
- [ ] M2M access uses Bearer token (JWT RFC 7519) — OIDC does not apply to API clients
- [ ] Session expires after configurable period of inactivity
- [ ] Logout invalidates session and notifies TARA

**Edge cases:**
- [ ] Authority officer's TARA identity not mapped to any authority account → `403 Forbidden` with `"detail": "Your identity is not registered as an authority user. Contact your administrator."` — not an error stack trace

##### Design and language

**Happy path:**
- [ ] UI uses TEDI (Tehik) design system components (https://tedi.tehik.ee/)
- [ ] i18n translation files; default language Estonian; language selector available
- [ ] WCAG 2.2 AA compliance verified by automated accessibility scan in CI
- [ ] Mobile device support: touch-friendly controls, minimum touch target 44×44 px

##### Functionality

**Happy path:**
- [ ] Search view: enter identifier (e.g. registration plate), select filters (mode, country, DGI), view results in real time (SSE)
- [ ] Identifier can be entered manually, by QR code scan, or NFC reading
- [ ] Clicking result allows requesting dataset — subset selection per user's permitted subsets
- [ ] Dataset displayed in human-readable form (XML rendered as structured table)
- [ ] Follow-up message can be sent directly to a UIL
- [ ] AAP provides both H2M (web interface) and M2M (REST API) — same backend endpoint
- [ ] When multiple UILs returned, all displayed — officer selects most relevant
- [ ] Search results paginated

**Edge cases:**
- [ ] SSE stream takes > 30 seconds → UI shows progress indicator; partial results displayed as they arrive
- [ ] Dataset XML rendering fails (malformed XML from platform) → UI shows raw XML with warning; does not crash

**Technical artifacts:**
- [ ] UI component: plate search with real-time SSE result display
- [ ] Accessibility: automated scan (axe-core) in CI
