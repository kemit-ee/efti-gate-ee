# EPIC 18 — Test Coverage and Quality

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Part of [Theme: Software Quality](README.md). Architecture: [software-quality/README.md](../../architecture/software-quality/README.md) (theme-wide rules) + [software-quality/test_coverage_and_quality.md](../../architecture/software-quality/test_coverage_and_quality.md) (sub-architecture).

<!-- issue-body:begin -->

**AS A** developer
**I WANT** automated tests covering the core business logic
**SO THAT** regressions are caught before reaching production.

## Spec anchors

| Contract surface | Reference |
|---|---|
| **API contract under test** | [`openapi.yaml`](../../specs/openapi.yaml) |
| **Schema contract under test** | [`db/schema.sql`](../../specs/db/schema.sql); design rules: [`db/README.md`](../../specs/db/README.md) |
| **Error contract under test** | [`errors.json`](../../specs/errors.json) |
| **Access-check rules under test** | [`permissions-matrix.md`](../../specs/permissions-matrix.md) |
| **Performance budgets** | Per-surface SLO p95 latency; capacity model: [`non-functional.md`](../../specs/non-functional.md) §1, §2 |
| **Test framework** | Implementer's choice — the spec doesn't pin a test framework; the contract is coverage + the scenarios below |
| **Architecture** | [../../architecture/software-quality/README.md](../../architecture/software-quality/README.md) (theme rules) + [../../architecture/software-quality/test_coverage_and_quality.md](../../architecture/software-quality/test_coverage_and_quality.md) (sub-architecture) |

## Acceptance Criteria

### Coverage targets

- [ ] Business-logic layer: **≥ 80 %** line coverage.
- [ ] Access-check layer: **100 %** of (role × endpoint × subset / scope) decision-table rows from `permissions-matrix.md` are covered by at least one passing test.
- [ ] Error catalog: every code in `errors.json` triggered by at least one test exercising its canonical scenario.

### Unit tests (must-cover scenarios)

- [ ] **Local-vs-remote routing.** Authority `GET /v1/identifiers/{identifier}` with a local hit doesn't broadcast; with zero local hits, does.
- [ ] **Broadcast-only-when-empty.** Verifies broadcast is **not** triggered when local results > 0 unless `forceBroadcast=true`.
- [ ] **Broadcast parallelism + partial failure.** Some peers return, some time out → caller gets a `200` with `failures[]` populated, not a 5xx.
- [ ] **Access control matrix.** Every cell of role × endpoint from `permissions-matrix.md`: ADMIN-only routes reject AUTHORITY, Authority routes reject ADMIN-only-scoped users, mTLS routes reject JWT, etc.
- [ ] **Write-access scope.** Admin attempting to write to an entity outside `users.roles[ADMIN]` scope-IDs → `FORBIDDEN_WRITE_ACCESS`.
- [ ] **User-management edges.** Super-Admin-only role grant; self-delete refused; subset-of-authority enforcement; `taraSub` uniqueness conflict.
- [ ] **`X-Request-ID` dedup.** Replay within `request_id_cache` TTL → `DUPLICATE_REQUEST_ID`. Same id at two nodes within 1 ms → exactly one succeeds.
- [ ] **eDelivery parser.** All inbound `eb:Action` types; unknown `Action` returns AS4 fault; unknown `CompressionType` returns fault; invalid signature rejected.
- [ ] **Multi-platform user.** mTLS cert resolving to >1 active platforms row → `FORBIDDEN_MULTI_PLATFORM`; resolving to 0 → `FORBIDDEN_NO_PLATFORM`.
- [ ] **Cabotage expiry boundary.** `mode='road'`, `transport_date + 14 days` boundary: row at T-1 stays `active`; row at T+1 becomes `inactive`.

### Integration tests (must-cover scenarios)

- [ ] **Platform client.** REST-only platform vs eDelivery-capable platform → correct channel selected; timeouts produce `502`-class; platform always performs subsetting (ADR-003).
- [ ] **Repository latest-row reads.** `DISTINCT ON (logical_id) ORDER BY logical_id, created_at DESC` returns only the latest row per id; previous rows are silently retained.
- [ ] **CronManager mutex.** Two concurrent calls to `/api/v1/admin/archive` → one succeeds, one returns `ARCHIVE_IN_PROGRESS`.
- [ ] **`LISTEN/NOTIFY` cluster sync.** Write on node A propagates to node B's in-memory cache within ≤ 500 ms.

### End-to-end tests

- [ ] **Gate-to-gate identifier search.** Run two gate instances; trigger a search that produces a peer-broadcast; verify SSE stream contains both local and peer results.
- [ ] **Full registration → search → dataset → follow-up flow.** Platform POSTs identifiers; authority searches; authority pulls dataset; authority sends follow-up.
- [ ] **Follow-up routing.** Local destination vs remote destination → correct forwarder used.

### Performance + safety tests

- [ ] **Subsetter heap.** Streaming subset filter on a 10 MB dataset XML → peak heap < 256 MB (no DOM-load OOM).
- [ ] **Local search SLO.** `consignments` trigram + denormalised-column path: p95 < 50 ms at the §2 steady-state load.
- [ ] **Broadcast SLO.** Broadcast to N gates with one slow peer → caller gets a response within the §1 p95 SLO; slow peer surfaces in `failures[]`.

### CI gate

- [ ] Coverage report published as a CI artefact on every build.
- [ ] SLO-regression performance tests gate the build (a regression fails CI).

<!-- issue-body:end -->
