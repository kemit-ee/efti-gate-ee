# eFTI Gate Database

Canonical schema: [`schema.sql`](./schema.sql). Apply once against an empty database; setup commands are in the file header. Every table and column carries a `COMMENT ON …` — read those for field-level documentation.

## Single design rule — append-only everywhere

**Every operational table is INSERT-only.** No `UPDATE`, no `DELETE`, anywhere. The runtime `app` role has `SELECT, INSERT` only on every table; UPDATE and DELETE are not granted.

"Editing" an entity means **INSERTing a new row** sharing the same logical identifier. Each row carries:

- `row_id UUID` — synthetic primary key, unique per row
- the previous "primary key" (e.g. `gates.id`, `consignments.dataset_id`, `users.id`) — now a **non-unique logical identifier** shared by all rows belonging to one entity
- `created_at TIMESTAMPTZ` — when the row was written
- `created_by UUID` — denormalised `users.row_id` of the actor (NULL for system events)

## Read pattern — latest row wins

Reads use `DISTINCT ON (logical_id) … ORDER BY logical_id, created_at DESC` to fetch the current state of each entity:

```sql
-- Current state of all platforms
SELECT DISTINCT ON (id) *
FROM platforms
ORDER BY id, created_at DESC;

-- Current consignment for one dataset_id
SELECT *
FROM consignments
WHERE dataset_id = '550e8400-e29b-41d4-a716-446655440001'
ORDER BY created_at DESC
LIMIT 1;

-- Active road consignments expiring today
SELECT DISTINCT ON (dataset_id) *
FROM consignments
ORDER BY dataset_id, created_at DESC
WHERE status = 'active' AND mode = 'road' AND expires_at < NOW();
```

The reads are still **single-table** — the no-`JOIN` rule holds. Search columns are denormalised onto `consignments` directly (`vehicle_plate`, `vehicle_country`, `mode`, `dangerous_goods`, `origin_country`, `destination_country`, `transport_date`).

Indexes on every operational table follow the `(logical_id, created_at DESC)` pattern for fast latest-row lookup.

## State transitions are INSERTs

| Event | What used to be an UPDATE | What it is now |
|---|---|---|
| Gate ping (every 5 min) | `UPDATE gates SET last_ping_at = NOW(), status = 'ONLINE' WHERE id = …` | `INSERT INTO gates (id, …, status, last_ping_at, created_by) VALUES (…)` |
| Identifier expiry | `UPDATE consignments SET status = 'inactive' WHERE expires_at < NOW()` | `INSERT INTO consignments (dataset_id, …, status='inactive', …) VALUES (…)` (one INSERT per affected dataset_id, copying the latest row's other columns) |
| Password reset | `UPDATE users SET secret_hash = … WHERE id = …` | `INSERT INTO users (id, …, secret_hash = new) VALUES (…)` |
| Admin disables a gate | `UPDATE gates SET status = 'DISABLED' WHERE id = …` | `INSERT INTO gates (id, …, status='DISABLED') VALUES (…)` |
| Logout / token revocation | `UPDATE sessions SET revoked_at = NOW() WHERE token_hash = …` | `INSERT INTO sessions (jti, user_id, expires_at, revoked_at, reason) VALUES (…)` (denylist; AccessChecker rejects any JWT whose `jti` is here AND whose `exp` is still in the future) |
| Async response consumed | `DELETE FROM async_responses WHERE …` | `INSERT INTO async_responses (receiver_id, request_id, …, consumed_at = NOW()) VALUES (…)` |
| Re-upload of consignment | `UPDATE consignments` | new `consignments` row + new `identifiers` rows for the same dataset_id |

## Foreign keys

Foreign keys between operational tables are **not used**, because logical-id columns are not unique. Cross-references are by the logical id (`dataset_id`, `platform_id`, `gate_id`, `user_id`); referential integrity is enforced at the application layer. The schema ships zero `REFERENCES` clauses on operational-table logical ids.

## Archival — handled by CronManager

The live database carries every event ever written; reads only need the latest row. To keep the live DB lean, **non-latest rows are archived periodically** to separate cold storage by [**CronManager**](https://github.com/Buerostack/CronManager) — a Quartz-based scheduler service deployed alongside the gate. CronManager is configured via YAML (cron expression, target URL); it calls a gate admin endpoint on schedule (e.g. nightly), and that endpoint runs the archival sweep.

The archival contract:

- All operational tables are scanned per-logical-id; every row except the latest is moved to archival storage.
- For tables with TTL (`request_id_cache`, `sessions`), entries past `expires_at` are also moved.
- Archived data must be retained per the compliance windows in [`../non-functional.md`](../non-functional.md) §5: minimum 7 years for `audit_log` on the live DB (operator may extend indefinitely; not archived), and 7 years on live DB **plus archive** for `follow_up_log` and every other operational table.
- Archive destination is implementation-defined (S3-compatible object store, secondary Postgres, etc.); the live DB does not depend on archive location.

The full contract — endpoint shape, batching strategy, idempotency rules, retention windows — lives in **Epic 26** ([`../../epics/epic_26_en.md`](../../epics/epic_26_en.md)).

## Roles — `app` vs `db_archiver`

The schema declares two PostgreSQL roles with strictly disjoint capabilities:

| Role | Grants | Connects from | Cannot |
|---|---|---|---|
| `app` | `SELECT, INSERT` on every operational table | Gate process (runtime) | UPDATE, DELETE — anywhere |
| `db_archiver` | `SELECT, DELETE` on operational tables; `SELECT` on `audit_log` | Archival worker invoked by `POST /api/v1/admin/archive` | INSERT, UPDATE — anywhere |

**Why two roles, not one.** The append-only invariant ("the gate cannot mutate or remove rows") is enforced by grant, not by convention. If `app` had DELETE, a single misrouted code path could destroy history; the database itself prevents that. The archival worker, conversely, has DELETE but no INSERT — it can only remove rows it has already copied to cold storage.

**`db_archiver` cannot DELETE from `audit_log`.** The audit ledger is preserved on the live DB for **at least 7 years** (compliance floor — GDPR Art 30, Reg 2024/1942 Art 6; see `../non-functional.md` §5); operators may extend indefinitely. Copying it to cold storage is a separate concern handled outside this archival job.

**Where the credentials live.** The `db_archiver` password lives in a Kubernetes Secret consumed by the archival-worker connection pool that backs `POST /api/v1/admin/archive`. The gate's main connection pool authenticates as `app` and never sees the `db_archiver` credentials. CronManager itself never connects to PostgreSQL — it only calls the gate's HTTPS admin endpoint with a Bearer token (see [`../deploy/cronmanager-archive.yaml`](../deploy/cronmanager-archive.yaml)).

## Pointers

- [`schema.sql`](./schema.sql) — canonical schema with full `COMMENT ON` coverage
- ER diagram: **deferred to Phase 3.** The pre-rewrite ER diagram at `../../efti-analysis/3-model/er-diagram.png` is no longer accurate (it shows FK constraints and PK uniqueness on `dataset_id` that the append-only schema explicitly forbids — see "Foreign keys" above). Don't follow it. The canonical structural truth is `schema.sql` itself plus the read-pattern documented in this file.
- Read patterns and XML/JSON transformations: [`../data-transformations.md`](../data-transformations.md)
- Permission rules: [`../permissions-matrix.md`](../permissions-matrix.md)
- Error catalog: [`../errors.json`](../errors.json)
- Non-functional contracts (SLOs, capacity, retention): [`../non-functional.md`](../non-functional.md)
- Archival epic: [`../../epics/epic_26_en.md`](../../epics/epic_26_en.md)
