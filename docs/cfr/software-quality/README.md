# Theme: Software Quality

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Architecture: [`../../architecture/software-quality/README.md`](../../architecture/software-quality/README.md). The overarching rules are defined there; the AC below verify the gate honours those rules end-to-end.

<!-- issue-body:begin -->

**Objective:** Ensure every change is automatically tested, documented, securely packaged, and deployed in an auditable way. This is the foundation of KeMIT NFR (Non-Functional Requirements) compliance.

## Business value

- Automated tests catch regressions before production — increases release confidence
- CI/CD automation reduces deployment risk and enables fast rollback (within minutes)
- SonarQube quality gates, SBOM, and Trivy scanning are KeMIT project supply chain security requirements
- OpenAPI specification and API versioning allows partners to integrate without direct technical support
- Semantic versioning with CHANGELOG provides a traceable release history

## Acceptance Criteria

**Theme done when:**
- [ ] EPIC 18 (Tests): unit coverage ≥80%, E2E gate-to-gate flow passes in CI
- [ ] EPIC 19 (API docs): OpenAPI 3.0 spec published, Swagger UI live, versioning `/v1/` in place
- [ ] EPIC 20 (CI/CD): every PR builds + tests + scans; `main` → staging auto-deploy; git tag → production

<!-- issue-body:end -->

## Sub-areas

- [Test Coverage and Quality](test_coverage_and_quality.md)
- [API Standardisation](api_standardisation.md)
- [CI/CD and Supply Chain Security](ci_cd_and_supply_chain.md)
