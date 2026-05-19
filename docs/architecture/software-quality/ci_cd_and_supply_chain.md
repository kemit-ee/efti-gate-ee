# Architecture: CI/CD and Supply Chain Security

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the CI/CD and Supply Chain Security surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/software-quality/ci_cd_and_supply_chain.md`](../../cfr/software-quality/ci_cd_and_supply_chain.md).

## Pipeline at a glance

```mermaid
flowchart LR
    Commit[git push / PR] --> Build[Build + unit tests]
    Build --> Static[Static analysis<br/>0 critical/high, coverage ≥ 80%]
    Static --> Scan[CVE scan<br/>block CRITICAL/HIGH]
    Scan --> SBOM[CycloneDX SBOM]
    SBOM --> Image[Container image<br/>tags: commit, vX.Y.Z, latest]
    Image --> Stage{branch?}
    Stage -- main --> Staging[auto-deploy staging]
    Stage -- vX.Y.Z tag --> Prod[auto-deploy prod<br/>rolling update, zero downtime]
    Prod --> Rollback[rolling rollback<br/>≤ 2 min]
```

## Rationale

Supply-chain security and reproducible deploys are non-optional for a regulator-facing service. CycloneDX SBOM + CVE scan + signed-image release process give the EU Trust Service auditors what they need without inventing a custom process. SemVer + a published `CHANGELOG.md` give integration partners the predictability they need to plan migrations against deprecation windows from Epic 19.

