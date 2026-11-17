# ADR-012: Denormalising hot reads — a query-level solution vs separate read-model tables

**Status: UNDER DECISION** (discussion: Sten Viljus, Rainer Türner). This is a draft, not an accepted decision.

Related issues: kemit-ee/efti-gate-ee#246 (A6), #247–#251 (DENORM-1…5; the earlier #180–#185 are closed and replaced). Related ADR: ADR-009.

## The problem

The client's requirement is denormalised tables for hot reads. Issue #246 splits this into two layers:

1. append-only source tables with monotonic revisions — **present**;
2. denormalised tables that hot reads use, so that the revision history is not scanned — **missing**.

The issue expects staleness-tolerant reads (border-check, history, the gate registry, summaries, admin lists) to run with a single PK lookup from a derivative table, while security-critical reads
(`check_authority_subsets`, `check_user_auth`, `get_platform_by_api_key`, `insert_audit_log`, verify-after-write) stay against the source log.

An issue comment says the need is "solved at the query level". This ADR must decide whether that satisfies the requirement or not.

## Current state (in the code)

- `consignments` carries the search columns directly (there are no cross-table JOINs); this is a partial fulfilment of layer 2.
- ADR-009: `get_consignments.sql` filters first and checks "latest row" with a correlated `NOT EXISTS`; 23 s on 1M rows → the measured candidate C6 (see `docs/performance/askend_perf_verification/`).
- `check_transport_means_registered.sql` (border-check, >10,000/h, sub-second): indexed candidates + a `LATERAL` latest-row lookup + `EXISTS … LIMIT 1`, without a full-table sort.
- `get_consignments_by_transport_means.sql`: a sorted index match + an anti-join up to 50 rows.
- `idx_consignments_created_latest (created_at DESC, revision DESC)` lets wide searches stop at the LIMIT.
- There are no separate read-model tables, refresh jobs or projections. The `app` role has only `SELECT, INSERT`.

## Options considered

### A. Query-level optimisation only (status quo + measurement)

The source table stays the only truth; hot queries use filter-first + anti-join/LATERAL patterns and suitable indexes.

- Plus: there is no second copy of the truth, no refresh lag, no 2× write load, no bloat or cron-overlap risk; does not need extending the `app` role's permissions.
- Minus: the cost still depends on the history size (the anti-join / LATERAL runs per candidate); does not match the wording of the client's requirement ("mandatory denormalised tables"); composite reads (gate + authority + user) stay multiple ReSql round-trips.

### B. A full read-model for all safe-list reads (DENORM-1…5)

Each safe-list category gets a derivative table (one row per key), filled via Ruuter + ReSql through CronManager (the same pattern as archiving, no DB functions).

- Plus: fulfils the requirement to the letter; a PK lookup is independent of the history size; a hot row stays in cache.
- Minus: refresh delay (must be shorter than the strictest test-fest tolerance); 2× write load; dead-tuple bloat on hot keys; cron vs burst-writes overlap; the index vs the actual query pattern may miss; `UPDATE` is forbidden, so a read-model cannot be an upsert table; it must itself be append-only (see decision 3).

### C. Hybrid (recommendation)

The query-level solution stays for DENORM-1 (border-check), if measurement proves the target is met. Read-model tables are built only where the query level is not enough: DENORM-2 (history), DENORM-4 (summaries), DENORM-5 (admin lists). DENORM-3 (the gate registry) is decided separately, because edelivery loads the party registry every 30 min anyway and ADR-011 may turn the registry into signed configuration.

## Decision (proposal: option C)

1. **Measure before building.** The current DENORM-1 solution is measured with `python3 tests/sql/regression.py --performance` on at least 1M synthetic rows (we keep the same methodology as ADR-009). Target: border-check p95 < 1 s under concurrent write load. If met, DENORM-1 is closed as a query-level solution.
2. **A read-model is derivative.** The source table stays canonical; a read-model table can be fully rebuilt from the source. No security-critical read (the list in the issue) is routed to a read-model.
3. **Write model: a read-model is itself append-only, `UPDATE` is not used.**
   - **An incremental row on the write path** (DENORM-2, history): `insert_consignment` adds a read-model row in the same transaction. There is no cron or staleness; history is append-only by nature.
   - **A generation snapshot** (DENORM-4 summaries, DENORM-5 lists): CronManager → Ruuter → ReSql adds a whole new snapshot with `generation = N` (INSERT-only). A separate small INSERT-only pointer table says the current generation (`ORDER BY generation DESC LIMIT 1`); the pointer is added only after the full snapshot is written, so a reader does not see a partial one. A read is `WHERE generation = :g AND key = :k` with a single PK lookup, independent of the history length. Old generations are deleted by CronManager (Quartz) via Ruuter + ReSql, as in archiving (the `purge-archive` pattern); only generations older than the current are deleted.
   - **Condemned:** a separate role with `UPDATE` permission and `MATERIALIZED VIEW ... REFRESH` (needs owner permission and DB-object management, which conflicts with the "no DB functions" rule).
4. **The refresh cadence** for each table is fixed in the DSL/documentation and must be shorter than the strictest consumer tolerance.
5. **Closing the requirement.** It is confirmed with the client in writing that option C (a measured query level for DENORM-1 + tables for the rest) satisfies the requirement. Without that confirmation #246 stays open.

## Consequences

- #247 is closed with the measurement results (if the target is met); #248, #250, #251 stay to be built; #249 awaits the ADR-011 decision.
- New tables are added to a Liquibase migration and `DSL/Liquibase/init.sql` (kept in sync) and covered by `tests/sql/regression.py` checks.
- `AGENTS.md` "Database rules" is extended if option C is confirmed (read-model tables are also INSERT-only; generation cleanup uses the archiving mechanism).

## Measurement result (fulfilling decision 1)

`python3 tests/sql/regression.py --performance --rows 1000000` (synthetic, one version per dataset, warm cache; baseline = `origin/dev`):

| Query | Execution time |
|---|---|
| `check_transport_means_registered` (border-check) | 0.38–0.45 ms |
| `get_consignments_by_transport_means` | 0.13–0.18 ms |
| `refresh_consignment_counts` (full snapshot) | ~1.7 s |
| `get_consignment_counts` (read-model read) | 0.2 ms |

Border-check is several orders of magnitude below the target (p95 < 1 s), so DENORM-1 stays at the query level. Limitation: the measurement does not cover concurrent write load or long version histories.

## DENORM-5 implementation

The admin registry lists (gates, platforms, authorities) read from the generation snapshot. The delay (visibility lag after a write) is removed by a refresh that each admin-write route calls immediately after the write (best effort); cron is the fallback. Consequence: each write adds a new generation (3 tables × the registry size), which cron purges. Get-by-id, routing, authentication and permissions still read from the source tables.

## DENORM-4 implementation

An authority-based summary: counts per day (the registration day) by dimensions, each dimension tied to an eFTI subset (EU02 dangerous goods, EU03 loading/unloading country, EU04 transport mode/type/registration country); the subset-less row is the total allowed for everyone. The period is a manual range (`from`/`to`, inclusive). The authority and its `subsets` are always read from the source table `authorities`, not from the read-model; the read-model gives only the count. For EU01, EU06 and EU07 there are no denormalised columns on `consignments`, so there is no summary for them.

## DENORM-3 solution

DENORM-3 (the gate registry resolved with the certificate) is closed by ADR-011 and no separate read-model is built. The registry is `registry/gates/<id>.yml`, a build-time generated static JSON (`/gates/<id>.json`), with the gate's current certificate in it: routing reads it with a single lookup, the projection is named and the source is canonical. `edelivery` refreshes the party map every `REGISTRY_REFRESH_SECONDS` (default 10 s, matching the issue's 10-second tolerance).

## Open questions

- Does a versioned INSERT-only read-model (without generations) give enough benefit compared to `LATERAL`? Presumably not.
- What is the client's acceptance criterion: the existence of the tables or measured performance?
- What are the official test-fest tolerances for each safe-list read?
- Does the `check_authority_subsets` list in the issue match the code (there is no SQL file by that name; the subset check goes through `get_authority_by_*` + the guard)?
- The issue refers to a local `acceptance-testing/1/A6-read-model-cqrs/` folder, which is not in this repo.
