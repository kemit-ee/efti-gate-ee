--liquibase formatted sql

-- Cold storage for non-current `consignments` rows lives in a SEPARATE PostgreSQL instance
-- (compose service `archive-database`, DB `efti_archive`). This changeset wires the live gate DB
-- to it over postgres_fdw so the archive flow can copy / verify / delete through ReSql like
-- everything else — one datasource, the FDW is transparent.
--
-- The flow is Ruuter + ReSql, NOT a database function:
--   1. DSL/Resql/efti/POST/archive_consignments.sql         copy superseded rows into archive.consignments (over FDW)
--   2. DSL/Resql/efti/POST/verify_archived_consignments.sql read them back from the archive DB and confirm
--   3. DSL/Resql/efti/POST/delete_archived_consignments.sql the real DELETE FROM public.consignments —
--                                                           only rows that still have a newer sibling
--   Orchestrated by DSL/Ruuter/ops/POST/v1/archive-consignments.yml (ARCHIVE_OPS_TOKEN, for
--   CronManager). Step 3 runs only if step 2 confirmed 100 % of the candidates, and only via the
--   loopback-internal route DSL/Ruuter/efti/POST/internal/delete-archived-consignments.yml.
--
-- What moves: ONLY rows whose superseding row is older than the retention window (default 30d), so
-- the current row of every dataset — incl. a DELETED tombstone — always stays in public.consignments
-- and every existing read (get_consignment_by_id, get_consignments, the X-Road reads) is unaffected.
--
-- DEV credentials: the archive DB uses the same POSTGRES_PASSWORD as the live DB ('01234'). There is
-- no injection mechanism — same posture as constants.ini's INTERNAL_SERVICE_TOKEN. Production wiring
-- (separate archive password, TLS to the archive host) is a deployment concern, tracked with ADR-006's
-- open questions.

--changeset efti:consignments-archive-fdw splitStatements:false
CREATE EXTENSION IF NOT EXISTS postgres_fdw;

CREATE SERVER archive_srv
  FOREIGN DATA WRAPPER postgres_fdw
  OPTIONS (host 'archive-database', port '5432', dbname 'efti_archive', updatable 'true');

-- ReSql connects as the superuser `efti`; this mapping is what the runtime uses. If ReSql is ever
-- moved to the restricted `app` role, add `CREATE USER MAPPING FOR app ...` + the archive grants.
CREATE USER MAPPING FOR efti SERVER archive_srv
  OPTIONS (user 'efti', password '01234');
CREATE USER MAPPING FOR db_archiver SERVER archive_srv
  OPTIONS (user 'efti', password '01234');

CREATE SCHEMA archive;
COMMENT ON SCHEMA archive IS 'postgres_fdw window onto the separate efti_archive database. Cold storage for non-current append-only rows; off the hot path.';

-- Column list and order mirror public.consignments (plain types on the remote), + archived_at last.
-- Any future migration that changes public.consignments columns MUST change this foreign table and
-- DSL/Liquibase/archive-init.sql in lockstep.
CREATE FOREIGN TABLE archive.consignments (
  row_id                        UUID,
  dataset_id                    UUID,
  platform_id                   TEXT,
  gate_id                       TEXT,
  xml                           TEXT,
  status                        TEXT,
  transport_mode                TEXT,
  acceptance_date               TIMESTAMPTZ,
  acceptance_country            TEXT,
  delivery_date                 TIMESTAMPTZ,
  delivery_country              TEXT,
  dangerous_goods               TEXT,
  main_transport_id             TEXT,
  main_transport_type           TEXT,
  transport_reg_country         TEXT,
  loading_date                  TIMESTAMPTZ,
  loading_country               TEXT,
  unloading_date                TIMESTAMPTZ,
  unloading_country             TEXT,
  used_equipment_ids            TEXT[],
  used_equipment_categories     TEXT[],
  used_equipment_countries      TEXT[],
  used_equipment_seq            INTEGER[],
  carried_equipment_ids         TEXT[],
  carried_equipment_categories  TEXT[],
  carried_equipment_seq         INTEGER[],
  created_at                    TIMESTAMPTZ,
  archived_at                   TIMESTAMPTZ
)
  SERVER archive_srv
  OPTIONS (schema_name 'public', table_name 'consignments');

COMMENT ON FOREIGN TABLE archive.consignments IS 'Foreign table into efti_archive.public.consignments. Copy is ON CONFLICT (row_id) DO NOTHING so the archive step is idempotent.';

--rollback DROP FOREIGN TABLE IF EXISTS archive.consignments;
--rollback DROP SCHEMA IF EXISTS archive;
--rollback DROP USER MAPPING IF EXISTS FOR db_archiver SERVER archive_srv;
--rollback DROP USER MAPPING IF EXISTS FOR efti SERVER archive_srv;
--rollback DROP SERVER IF EXISTS archive_srv;
