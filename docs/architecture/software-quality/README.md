# Architecture: Software Quality

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Theme-wide architectural rules. Every sub-area below — and every Acceptance Criterion (AC) it carries — must derive from or at minimum **not conflict with** the rules stated here. AC live in the corresponding sub-area files under [`docs/cfr/software-quality/`](../../cfr/software-quality/); this document describes the *contract those AC implement*.

**System-wide reference:** [eFTI Gate Reference Architecture](../eFTI-Gate-Reference-Architecture.md). This document narrows the system-wide rules to the Software Quality surface.

**Sub-architectures in this theme** (each is the architectural surface for the AC tracked in the linked epic):

- [Test Coverage and Quality](test_coverage_and_quality.md) — AC: [`docs/cfr/software-quality/test_coverage_and_quality.md`](../../cfr/software-quality/test_coverage_and_quality.md)
- [API Standardisation](api_standardisation.md) — AC: [`docs/cfr/software-quality/api_standardisation.md`](../../cfr/software-quality/api_standardisation.md)
- [CI/CD and Supply Chain Security](ci_cd_and_supply_chain.md) — AC: [`docs/cfr/software-quality/ci_cd_and_supply_chain.md`](../../cfr/software-quality/ci_cd_and_supply_chain.md)

---

## Overarching rules

These are the cross-cutting invariants every sub-area in this theme derives from. AC bullets in the CFR files specialise them to specific endpoints, error codes, or DB state.

### 1.1 OpenAPI is the single source of truth for the wire contract

`docs/specs/openapi.yaml` is the only authoritative description of the gate's HTTP surface. Request / response shapes, status codes, error-body schemas (RFC 7807), and authentication schemes all live there. Any code that describes the same surface (controller annotations, generated clients, mocks) is derived from OpenAPI, not maintained in parallel. The contract test suite enforces that the implementation matches OpenAPI — divergence is a build failure, not a runtime surprise.

### 1.2 Integration tests run against real PostgreSQL — no in-memory mocks

Integration-level tests connect to a real PostgreSQL instance (Testcontainers or a CI-managed Postgres service). H2 / SQLite mocks are forbidden: the append-only model relies on Postgres-specific behaviours (`DISTINCT ON`, `LISTEN`/`NOTIFY`, partial indexes, `CITEXT`, `pg_trgm`) that mock databases do not faithfully reproduce. Tests that pass on H2 but fail on Postgres are a recurring class of bug that this rule eliminates.

### 1.3 RFC 7807 problem-details on every error

Every error response is `application/problem+json` per RFC 7807, carrying `type`, `code`, `title`, `status`, `detail`, `instance`. The `code` field is bound to the catalogue in `docs/specs/errors.json` (currently 36 codes). The error catalogue is authoritative; ad-hoc error codes are not allowed. Adding a new code requires adding a row to `errors.json` and a contract test asserting the gate emits it.

### 1.4 CI enforces every gate before merge

The CI pipeline runs (and fails on) at least: lint, unit tests, integration tests (against real Postgres), contract tests (against OpenAPI), license-header scan, container-image build, SBOM emission, container-image signing. No PR merges without all gates green. A failing CI is treated as a code defect, not a flake — flakes are quarantined and fixed, not retry-looped.

### 1.5 SBOM and signed images for supply-chain transparency

Every release container image carries an SBOM (CycloneDX) and a cosign signature. Operators verify the signature at deploy time against KeMIT's public key. SBOMs make CVE patching tractable; signatures make supply-chain compromise detectable. Both are mandatory deliverables of the release pipeline; an unsigned or SBOM-less image is not a release artefact.

### 1.6 Backwards-compatibility window for API consumers

Once a wire shape is published in a tagged release, breaking changes go through deprecation: at least one minor release with the new shape live alongside the old, with the old shape carrying a `Deprecation` header. Internal refactors that don't change the wire shape don't require this window. The OpenAPI changelog tracks deprecations explicitly.
