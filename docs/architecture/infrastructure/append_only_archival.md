# Architecture: Append-Only Archival via CronManager

## Changes

- _Initial state. Change tracking begins at v1.0.0._

> Sub-architecture for the Append-Only Archival via CronManager surface. For overarching rules see [theme README](README.md). AC are in [`../../cfr/infrastructure/append_only_archival.md`](../../cfr/infrastructure/append_only_archival.md).

## Flow at a glance

```mermaid
sequenceDiagram
    participant CM as CronManager
    participant Gate as eFTI Gate (admin endpoint)
    participant DB as PostgreSQL (live)
    participant Archive as Archival Store

    Note over CM: YAML schedule, e.g.<br/>"0 0 3 * * ?" (03:00 daily).<br/>HTTP job → admin archive<br/>endpoint.
    CM->>+Gate: POST /api/v1/admin/archive<br/>Authorization: Bearer OPS_TOKEN
    Gate->>Gate: Auth: caller is configured CronManager source
    Gate->>+DB: Select up to batch_size non-latest rows per logical id<br/>from this archivable table — rows whose latest sibling has won.<br/>See db/README.md for the canonical read pattern.
    DB-->>-Gate: candidate rows
    Gate->>+Archive: PUT batch (S3 / cold Postgres / NFS)
    Archive-->>-Gate: ack
    Gate->>+DB: Delete the just-archived rows from the live DB.<br/>The DELETE runs under the db_archiver role, NOT the runtime app role<br/>(app has SELECT, INSERT only).
    DB-->>-Gate: rows archived
    Gate->>+DB: Append a jobs_execution_log row<br/>(job_name='archive', started_at, finished_at, status, details).
    DB-->>-Gate: ok
    Gate-->>-CM: 200 OK { archived_count: …, duration_ms: … }
```

## Rationale

The gate's append-only design preserves complete history without UPDATE-triggers or `_history` tables — but that history would unboundedly grow the live DB. The CronManager-driven nightly sweep moves non-latest rows to a separate archival destination, keeping the live DB compact while the full history remains queryable from cold storage. The two-role split (`app` cannot DELETE, `db_archiver` can DELETE operational tables but cannot DELETE `audit_log`) means the append-only invariant survives even though the archive job has to delete from the live DB. `audit_log` lives only on the live DB because GDPR Art. 30 audit access must be queryable indefinitely without a cold-storage roundtrip.

