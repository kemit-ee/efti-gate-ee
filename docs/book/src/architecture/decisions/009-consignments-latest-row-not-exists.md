# ADR-009: Selecting the latest `consignments` version with `NOT EXISTS`, not `DISTINCT ON`

**Decision (Sten Viljus, 09.09.2026):**

## Decision

In `consignments` read queries (`get_consignments.sql` and other `FROM consignments`
reads), the pattern

```sql
FROM (
  SELECT DISTINCT ON (platform_id, dataset_id) *
  FROM consignments
  ORDER BY platform_id, dataset_id, created_at DESC
) latest
WHERE <criteria>
```

is replaced with a pattern where the criteria are filtered **before** the latest-version check, and
"is this row the latest" is done with a correlated `NOT EXISTS` (analysis candidate **C6**):

```sql
SELECT <columns>
FROM consignments c
WHERE c.status != 'DELETED'
  AND (:gateId IS NULL OR c.gate_id = :gateId)
  AND <criteria blocks against c.*>
  AND NOT EXISTS (
    SELECT 1 FROM consignments c2
    WHERE c2.platform_id = c.platform_id
      AND c2.dataset_id  = c.dataset_id
      AND c2.created_at   > c.created_at
  )
ORDER BY c.created_at DESC
LIMIT COALESCE(:limit, 100) OFFSET COALESCE(:offset, 0);
```

## Rationale

### The problem with the current query

`DISTINCT ON (platform_id, dataset_id)` without a `WHERE` in the inner subquery forces
PostgreSQL to **materialise the whole table**'s latest-per-dataset rows before any criterion
applies. On 1,000,001 rows (measured, `docs/performance/askend_perf_verification/explain-1m-current.txt`):

- `Parallel Seq Scan on consignments` — the whole table, ~4.1 s
- `Sort` `(platform_id, dataset_id, created_at DESC)` — `external merge Disk`, ~245 MB to disk
  (`work_mem = 4MB`), ~17.6 s — **the bottleneck**
- `Subquery Scan` — the criteria filter runs **last**, materialised over 1M rows,
  `Rows Removed by Filter: 1 000 000`; `idx_consignments_main_transport_id` stays unused
- **Execution Time: 23,337 ms to return a single row**

The query is linear in table size and uses no criterion index.

### Why C6

Measured candidates (200,000 rows, warm cache, `work_mem = 4MB`;
`docs/performance/askend_perf_verification/explain-candidates-1m.txt`):

| Candidate | Plan | Time |
|---|---|---|
| C0 current (`DISTINCT ON` + subquery) | Parallel Seq Scan + Sort external merge Disk + Unique + Filter | 2775 ms (~23 s @ 1M) |
| C1 filter-first (criterion index only, without the latest-row check) | Index Scan → Sort(1) → Unique | 1.2 ms |
| **C6 filter-first + `NOT EXISTS`** | **Nested Loop Anti Join (Index Scan + Index Only Scan `idx_consignments_dataset_latest`)** | **0.09 ms** |
| C3 `is_latest` flag column + trigger | Index Scan + Filter `is_latest` | 0.14 ms |

- **C1 is semantically wrong.** The semantic test (`docs/performance/askend_perf_verification/semantic-test.sql`):
  a dataset is re-uploaded `AAA` → `BBB`. A search by `AAA`:
  - C0 → empty ✓ (correct — `AAA` is no longer the latest version)
  - C1 → `AAA` ✗ (returns a stale row, because the latest-version check is missing)
  - C6 → empty ✓
  - A search by `BBB` + `CCC` (single-version): C6 → both ✓
- **C3 requires an UPDATE.** An `is_latest` cache column means one `UPDATE` to the previous row per
  `INSERT` (a trigger). In an append-only base this is an extra mechanism and additional write load;
  the `app` role would then also need `UPDATE` permission or the trigger would have to run as
  `SECURITY DEFINER`. Set aside as a last option, not a first.
- **C6 preserves the append-only model fully:** adds no column, does no UPDATE, the `app` role stays
  `SELECT, INSERT`. Semantically identical to C0. The only open point was whether the planner
  realises the `NOT EXISTS` efficiently — it does, as a `Nested Loop Anti Join`, served by the
  existing `idx_consignments_dataset_latest (dataset_id, platform_id, created_at DESC)`.

### "No JOINs on the hot path" — a clarification

The AGENTS.md rule "No JOINs on hot path" is aimed at **cross-table joins** — `consignments`
is not joined with `gates` / `platforms` / other tables; the search columns are denormalised
onto `consignments`. C6's `NOT EXISTS` is a **correlated subquery against the same table**
for the append-only latest-row semantics, not the SQL `JOIN` keyword or a cross-table join.
The planner's anti-join is a single-row index probe (`Index Only Scan`), not a materialised
join. This wording of the rule will be added to AGENTS.md.

## Schema

There are **no** changes to the database schema:
- no columns added or removed
- no indexes added (C6 uses the existing `idx_consignments_dataset_latest` and the
  criterion indexes like `idx_consignments_main_transport_id`)
- role permissions unchanged: `GRANT SELECT, INSERT ON consignments TO app`

Note: `idx_consignments_dataset_latest` is `(dataset_id, platform_id, created_at DESC)` —
it suits the `NOT EXISTS` correlation (`platform_id`, `dataset_id`, `created_at`), because all
three columns are present in the index (`Index Only Scan`). If measurements show that the
`(platform_id, dataset_id, created_at DESC)` order gives a better plan, that will be done in a
separate changeset; not needed now.

## Behaviour rules

- All `FROM consignments` **reads** use the filter-first + `NOT EXISTS` pattern.
- Criteria blocks apply to the `consignments` base table (`c.*`), not to a
  materialised subset.
- `WHERE status != 'DELETED'` stays (soft delete = a new row, ADR-002 logic).
- `NOT EXISTS` compares `created_at > c.created_at` — a strict `>`, so a row does not disqualify
  itself. If two rows for the same `(platform_id, dataset_id)` share exactly the same `created_at`
  (does not happen in practice, `created_at` is `default now()` with microsecond precision), both
  would be returned — the same edge case as with `DISTINCT ON`, where the choice would be arbitrary.
- `ORDER BY` changes from `platform_id, dataset_id, created_at DESC` → `created_at DESC`
  (the subset is already per-dataset unique, there is no longer a `DISTINCT ON` ordering requirement).

## Applied changeset

A rewrite of SQL files (not Liquibase — ReSql `.sql` endpoints):

- **`DSL/Resql/efti/POST/get_consignments.sql` — DONE.** The C6 pattern; the criteria blocks
  were left unchanged (they refer to `c`, the only table in the outer scope), a `NOT EXISTS`
  anti-join added, `ORDER BY` `(platform_id, dataset_id, created_at DESC) → created_at DESC`.
  Measured live (`docs/performance/askend_perf_verification/explain-c6-live.txt`, 200k rows):
  `Nested Loop Anti Join` (`Index Scan idx_consignments_main_transport_id` +
  `Index Only Scan idx_consignments_dataset_latest`, Heap Fetches 0), **1.1 ms** vs
  the old 2775 ms + 245 MB disk sort. Semantic test green, `http-tests` 198/198.
- **Other `FROM consignments` reads — reviewed, no change needed:**
  - `get_consignment_xml.sql`, `get_consignment_by_id.sql` — `WHERE dataset_id = :id`
    right at the start, `DISTINCT ON` runs over only one dataset's rows (indexed).
  - `get_consignments_by_transport_means.sql`, `check_transport_means_registered.sql` —
    already filter-first: the inner `WHERE (dataset_id, platform_id) IN (SELECT … WHERE
    main_transport_id = :id)` limits `DISTINCT ON` to the datasets that ever carried the
    identifier, not the whole table (comments in the files explain the append-only
    semantics). The same root cause is not present here.
- `AGENTS.md` — the "No JOINs on hot path" clarification (a correlated anti-join against the
  same table for append-only semantics is allowed). **DONE.**
- The semantic regression test `docs/performance/askend_perf_verification/semantic-test.sql` under
  `tests/` as a permanent test — *open.*

Measurements and reproduction: `docs/performance/askend_perf_verification/analysis.md` §6.4/§6.11,
`docs/performance/askend_perf_verification/explain-candidates-1m.{sql,txt}`, `explain-c6-live.txt`.
