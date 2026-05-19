# Architecture: Test Coverage and Quality

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Test Coverage and Quality surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/software-quality/test_coverage_and_quality.md`](../../cfr/software-quality/test_coverage_and_quality.md).

## Rationale

The spec already encodes the contract — coverage targets just make the contract executable. The minimum scenario list above is the floor: any test suite that covers all of these can be trusted to catch the high-value regressions. Framework choice is left to the implementer because the spec is stack-open; what's pinned is **what** must be tested, not **how**.

