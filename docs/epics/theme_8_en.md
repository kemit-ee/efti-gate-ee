# THEME 8 — Software Quality


**Objective:** Ensure every change is automatically tested, documented, securely packaged, and deployed in an auditable way. This is the foundation of KeMIT NFR (Non-Functional Requirements) compliance.

**Theme done when:**
- [ ] EPIC 18 (Tests): unit coverage ≥80%, E2E gate-to-gate flow passes in CI
- [ ] EPIC 19 (API docs): OpenAPI 3.0 spec published, Swagger UI live, versioning `/v1/` in place
- [ ] EPIC 20 (CI/CD): every PR builds + tests + scans; `main` → staging auto-deploy; git tag → production

**Business value:**
- Automated tests catch regressions before production — increases release confidence
- CI/CD automation reduces deployment risk and enables fast rollback (within minutes)
- SonarQube quality gates, SBOM, and Trivy scanning are KeMIT project supply chain security requirements
- OpenAPI specification and API versioning allows partners to integrate without direct technical support
- Semantic versioning with CHANGELOG provides a traceable release history


## Epics

- [EPIC 18 — Test Coverage and Quality](epic_18_en.md)
- [EPIC 19 — API Standardisation](epic_19_en.md)
- [EPIC 20 — CI/CD and Supply Chain Security](epic_20_en.md)
