# Consignments search — load-test follow-up (summary)

Follow-up to the eFTI Gate load test (`docs/performance/performance-report.md`). The
reported "20–120× slower than the PoC" turned out to be a mix of a **real query bug**, a
**broken test seed**, and **config caps**. All fixed and re-measured. Full detail:
[`askend_performance_testing_and_tuning.md`](askend_performance_testing_and_tuning.md) /
[`analysis.md`](analysis.md).

## What was wrong

1. **Test seed did not match the query.** `bulk-insert-consignments.sql` used
   `gate_id = 'EE'`, but `authority/search` filters `gate_id = 'EU-EE'`; and it searched
   `VESSEL-001`, absent from the bulk data. The 1M-row test measured "scan 1M rows → find
   nothing → fall through to the multiplexer", not "find one row among a million".
2. **`authority/search` is not a single DB query.** On a local miss the DSL continues to
   the multiplexer with a **65 s timeout**, and `stop_in_case_of_exception` turned the
   timeout into **HTTP 500** — 65–77 s later. One number mixed four services plus a timeout.
3. **Config below target.** ReSql pool = 10, Ruuter `cpus: 0.5`. At `-c 250` the p99 was
   mostly connection-queue wait.
4. **Real query bug.** `get_consignments.sql`'s inner `SELECT DISTINCT ON (platform_id,
   dataset_id) * FROM consignments ORDER BY …` (no `WHERE`) materialises the latest-per-dataset
   set over the **whole table** on every search. 1M rows → 245 MB external-merge sort →
   **23 337 ms** for one row.
5. **`ab -c 250`** without ramp-up / think-time measures saturation, not latency.

## What changed

| Change | Ref |
|---|---|
| Query: whole-table `DISTINCT ON` sort → filter-first + self-correlated `NOT EXISTS` "no newer row" anti-join. Same result, same append-only model. | PR #144, ADR-009 |
| `authority/search` non-blocking: local hit returned at once; no local hit → `[]` immediately + broadcast in the background (client polls for peer results). Multiplexer `/first` → `/search` + bounded long-poll `/rest`. | PR #145, ADR-010 |
| Ruuter 0.9.12-rc → 0.9.14-rc; duplicate authority guard removed. | PR #145 |
| Config: `ruuter` + `database` `cpus: 2.0`; ReSql pool 75. Corrected test seed. | PR #145 |

## Results (same machine)

**`get_consignments` @ 1,000,001 rows:** 23 337 ms → **0.22 ms** (Nested Loop Anti Join +
two index lookups). ReSql direct: 0.14 → ~5 900 req/s.

**`ab` sweep (server ceiling):** `authority/search` ~37 → **~550 req/s** (now scales);
p99 under load 1 800–4 500 ms → ~330–420 ms.

**`k6` (ramp to 100 VUs, think-time 0.5–1.5 s, ~30 000 requests, 0 failures):**

| scenario | p50 | p95 | p99 |
|---|---:|---:|---:|
| `authority/search`, 1 row | 6 ms | 16 ms | 30 ms |
| `authority/search`, 1M rows | 7 ms | 21 ms | 57 ms |
| ReSql direct | 2 ms | 6 ms | 40 ms |

Search latency is essentially flat from 1 row to 1M rows — **the database is no longer a
bottleneck at any volume**. Residual: the DSL engine adds ~2.5 ms/request (~2×, down from
~30–50×); it is a Ruuter property and scales with concurrency.

## Recommendations for load testing

1. Seed must satisfy the query (`gate_id`, a searchable identifier present in the data).
2. Measure per layer: `ab` at ReSql; `ab` at `authority/search`; `pgbench` at the DB.
3. Realistic profile with `k6` / `wrk`: ramp + think-time, full p50/p90/p95/p99 distribution.
4. Record the environment: Ruuter version, container `cpus`/`mem`, ReSql pool, PostgreSQL
   `work_mem` / `shared_buffers`, which compose file.
