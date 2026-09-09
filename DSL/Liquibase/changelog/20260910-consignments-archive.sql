--liquibase formatted sql

-- Cold-storage destination for non-current `consignments` rows, plus the sweep the archiver runs.
--
-- Context: `consignments` is append-only — a re-upload, a status flip, a soft-delete are all new
-- rows sharing `(platform_id, dataset_id)`; the latest `created_at` wins. History therefore grows
-- without bound. CronManager (external Quartz scheduler → the gate's archive admin endpoint) sweeps
-- the superseded rows out nightly so the live table stays compact. See
-- docs/architecture/infrastructure/append_only_archival.md.
--
-- "No conflict there": `archive.consignments` carries NO CHECK constraints, NO foreign keys and
-- NO NOT NULL beyond what a copied row already satisfies. Its only key is `row_id` (globally unique
-- per physical row), and every insert is `ON CONFLICT (row_id) DO NOTHING`, so a re-run or an
-- overlapping sweep can never fail on a duplicate. It is a landing table, not a validated one.
--
-- What moves: ONLY rows that are provably never returned by a latest-row read — i.e. a newer row
-- exists for the same `(platform_id, dataset_id)` AND that newer row is itself older than the
-- retention window (default 30 days), so recent history stays live. The current row of every
-- dataset — including a `DELETED` tombstone — always stays in `public.consignments`, so
-- get_consignment_by_id / get_consignments / the X-Road reads are unaffected.

--changeset efti:consignments-archive-schema
CREATE SCHEMA IF NOT EXISTS archive;
COMMENT ON SCHEMA archive IS 'Cold storage for non-current append-only rows swept out of the live tables by CronManager. Queryable, but off the hot path. Can later be relocated behind postgres_fdw to a physically separate database without touching the sweep logic.';

GRANT USAGE ON SCHEMA archive TO db_archiver;
--rollback DROP SCHEMA IF EXISTS archive CASCADE;

--changeset efti:consignments-archive-table splitStatements:false
-- Same shape as public.consignments (INCLUDING DEFAULTS so a manual insert still gets sane values),
-- but EXCLUDING CONSTRAINTS drops the PK + the five country-format CHECKs, and EXCLUDING INDEXES
-- keeps it lean. archived_at is appended LAST so `INSERT INTO archive.consignments SELECT c.*, now()`
-- lines up. NOTE: any future migration that adds/drops a column on public.consignments MUST mirror
-- it here — archive.sweep_consignments relies on the column lists matching.
CREATE TABLE archive.consignments (
  LIKE public.consignments INCLUDING DEFAULTS EXCLUDING CONSTRAINTS EXCLUDING INDEXES
);
ALTER TABLE archive.consignments ADD COLUMN archived_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE archive.consignments ADD PRIMARY KEY (row_id);

CREATE INDEX idx_archive_consignments_dataset ON archive.consignments (dataset_id, platform_id, created_at DESC);
CREATE INDEX idx_archive_consignments_archived_at ON archive.consignments (archived_at);

COMMENT ON TABLE archive.consignments IS 'Superseded (non-current) consignments rows moved here by archive.sweep_consignments(). No CHECK/FK constraints and every insert is ON CONFLICT (row_id) DO NOTHING, so the sweep is idempotent and cannot fail on a duplicate. Column list must stay in lockstep with public.consignments.';
COMMENT ON COLUMN archive.consignments.archived_at IS 'When the sweep moved this row out of public.consignments';

GRANT SELECT, INSERT, DELETE ON archive.consignments TO db_archiver;
--rollback DROP TABLE IF EXISTS archive.consignments;

--changeset efti:consignments-archive-sweep-fn splitStatements:false runOnChange:true
-- Moves superseded consignments rows to archive.consignments and deletes them from the live table,
-- atomically (one statement, three data-modifying CTEs sharing a snapshot). SECURITY INVOKER: runs
-- with the caller's rights, so it must be called as db_archiver (SELECT on public, DELETE on public,
-- INSERT on archive). Advisory xact lock serialises concurrent sweeps across cluster nodes.
--
--   p_older_than — keep this much of each dataset's recent history live (default 30 days). A row is
--                  swept only if the row that superseded it is itself older than now() - p_older_than.
--
-- Returns the number of rows moved.
CREATE OR REPLACE FUNCTION archive.sweep_consignments(p_older_than interval DEFAULT interval '30 days')
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
  moved bigint;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('archive.sweep_consignments'));

  WITH superseded AS (
    SELECT c.row_id
    FROM public.consignments c
    WHERE EXISTS (
      SELECT 1
      FROM public.consignments n
      WHERE n.platform_id = c.platform_id
        AND n.dataset_id  = c.dataset_id
        AND n.created_at   > c.created_at
        AND n.created_at   < now() - p_older_than
    )
  ),
  ins AS (
    INSERT INTO archive.consignments
    SELECT c.*, now()
    FROM public.consignments c
    JOIN superseded s USING (row_id)
    ON CONFLICT (row_id) DO NOTHING
    RETURNING 1
  ),
  del AS (
    DELETE FROM public.consignments c
    USING superseded s
    WHERE c.row_id = s.row_id
    RETURNING 1
  )
  SELECT count(*) INTO moved FROM del;

  RETURN moved;
END;
$$;

REVOKE ALL ON FUNCTION archive.sweep_consignments(interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION archive.sweep_consignments(interval) TO db_archiver;
--rollback DROP FUNCTION IF EXISTS archive.sweep_consignments(interval);

--changeset efti:consignments-archive-purge-fn splitStatements:false runOnChange:true
-- Cold-storage retention: drop archived rows older than p_keep (default 7 years). Separate from the
-- sweep so retention policy can be tuned without touching the move logic. db_archiver only.
CREATE OR REPLACE FUNCTION archive.purge_consignments(p_keep interval DEFAULT interval '7 years')
RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
  purged bigint;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('archive.purge_consignments'));
  WITH d AS (
    DELETE FROM archive.consignments WHERE archived_at < now() - p_keep RETURNING 1
  )
  SELECT count(*) INTO purged FROM d;
  RETURN purged;
END;
$$;

REVOKE ALL ON FUNCTION archive.purge_consignments(interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION archive.purge_consignments(interval) TO db_archiver;
--rollback DROP FUNCTION IF EXISTS archive.purge_consignments(interval);
