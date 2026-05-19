# EPIC 20 — CI/CD and Supply Chain Security

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Software Quality](README.md). Architecture: [software-quality/README.md](../../architecture/software-quality/README.md) (theme-wide rules) + [software-quality/ci_cd_and_supply_chain.md](../../architecture/software-quality/ci_cd_and_supply_chain.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** DevOps engineer
**I WANT** automated build, test, security analysis, and deployment pipelines
**SO THAT** every release is repeatable, auditable, and secure.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **Test contract** | Coverage targets and minimum scenarios: Epic 18 + [`openapi.yaml`](../../specs/openapi.yaml) |
| **Security scanning** | Vuln-scanner: implementer's choice (Trivy / Grype / Snyk / equivalent); block threshold is the contract |
| **SBOM format** | CycloneDX |
| **Versioning** | SemVer MAJOR.MINOR.PATCH; deprecation window in Epic 19 |
| **Deployment topology** | Rolling update, two-replica floor, HPA: [`non-functional.md`](../../specs/non-functional.md) §3, §3.1 |
| **Architecture** | [../../architecture/software-quality/README.md](../../architecture/software-quality/README.md) (theme rules) + [../../architecture/software-quality/ci_cd_and_supply_chain.md](../../architecture/software-quality/ci_cd_and_supply_chain.md) (sub-architecture) |

## Acceptance Criteria

### CI (per PR)

**Business rules:**
- [ ] Build and unit tests must pass.
- [ ] Static analysis gate: **0 critical/high** issues; coverage **≥ 80 %** (per Epic 18 floor).
- [ ] Container image vulnerability scan blocks CVEs at **CRITICAL** or **HIGH** severity. A PR introducing such a CVE is blocked and the report linked from the CI run.
- [ ] XSD validation: every XML sample file in the repo validates against the schemas in `docs/efti-analysis/xsd/`.
- [ ] **SBOM** (CycloneDX format) is generated for every build artefact and attached to the CI run.

### CD

**Business rules:**
- [ ] `main` branch update → automatic deployment to the staging environment.
- [ ] A version tag (`vX.Y.Z`) → automatic deployment to production.
- [ ] Container image is tagged with: the commit hash, the SemVer string, and `latest`. All three tags are pushed to the registry.
- [ ] Production rollout is **rolling** (new replicas start before old replicas are removed) — zero downtime. Coordinates with `/health/ready` per Epic 13.
- [ ] Rollback to the previous version is a **single action** (e.g. `kubectl rollout undo deployment/efti-gate` on Kubernetes; equivalent on other orchestrators) and completes within ≤ 2 minutes.

### Versioning + change tracking

**Business rules:**
- [ ] SemVer MAJOR.MINOR.PATCH is used for every production release. Breaking API changes require a MAJOR bump and a deprecation window of the previous MAJOR (see Epic 19, 6-month minimum).
- [ ] `CHANGELOG.md` follows **Keep a Changelog 1.1.0**.
- [ ] Every production release is tagged in git as `vX.Y.Z`.

<!-- issue-body:end -->
