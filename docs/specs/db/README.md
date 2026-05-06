# eFTI Gate Database

Canonical schema: [`schema.sql`](./schema.sql). Apply once against an empty database; setup commands are in the file header. Every table and column carries a `COMMENT ON …` — read those for field-level documentation.

## Design rules

### Persistence taxonomy

Three classes of table, each with different mutability rules:

| Class | Tables | App role grants | Modify rule |
|---|---|---|---|
| **Ledger** | `change_history`, `audit_log`, `follow_up_log` | `SELECT, INSERT` | `UPDATE`/`DELETE` rejected by `BEFORE` trigger that `RAISE EXCEPTION` |
| **Ephemeral** | `request_id_cache`, `sessions`, `jobs_execution_log` | `SELECT, INSERT` | App never modifies; rows age out via partition rotation under a maintenance role |
| **Registry** | `gates`, `platforms`, `authorities`, `users`, `consignments`, `identifiers` | `SELECT, INSERT, UPDATE` | `UPDATE` allowed; every change captured into `change_history` by `AFTER UPDATE` trigger; `DELETE` never granted to `app` |

`DELETE` is not granted to the runtime `app` role on any table. Logical deletion of registry rows uses status enums (`gates.status='DISABLED'`, `consignments.status='deleted'`).

### Other rules

- **Denormalised reads — no JOIN in app hot paths.** Search columns (`vehicle_plate`, `vehicle_country`, `mode`, `dangerous_goods`, `origin_country`, `destination_country`, `transport_date`) live on `consignments` directly. Authority queries hit a single table.
- **Audit immutability** is defense-in-depth: `BEFORE UPDATE OR DELETE` triggers `RAISE EXCEPTION` on the three ledger tables, plus `REVOKE UPDATE, DELETE FROM PUBLIC`.

## Retention

Ephemeral tables grow without DELETE; rotation is by partition drop, run by a separate maintenance role (not the app role):

| Table | Strategy | Window |
|---|---|---|
| `request_id_cache` | Daily partitions on `seen_at`; drop after 1 day (TTL is 10 minutes; daily granularity is sufficient) | 1 day |
| `sessions` | Daily partitions on `created_at`; drop after `JWT_EXPIRY_SECONDS` × 2 | configurable |
| `jobs_execution_log` | Monthly partitions on `started_at`; keep 12 months | 12 months |
| `change_history` | Monthly partitions on `changed_at`; keep 7 years (GDPR Art 30) | 7 years |
| `audit_log` | Monthly partitions on `recorded_at`; keep 7 years (eFTI Reg 2024/1942 audit) | 7 years |
| `follow_up_log` | Monthly partitions on `received_at`; keep 7 years | 7 years |

Partition declarations are not embedded in `schema.sql` (kept tractable for first-run); deployment phase wires up a partition manager (e.g. `pg_partman`).

## Pointers

- ER diagram: [`../../efti-analysis/3-model/er-diagram.png`](../../efti-analysis/3-model/er-diagram.png)
- Read patterns and XML/JSON transformations: [`../data-transformations.md`](../data-transformations.md)
- Permission rules: [`../permissions-matrix.md`](../permissions-matrix.md)
- Error catalog: [`../errors.json`](../errors.json)
