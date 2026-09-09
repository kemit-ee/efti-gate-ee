-- Runs once, as superuser, at cluster initialisation of the SEPARATE archive database
-- (compose service `archive-database`, DB `efti_archive`). Mounted at
-- /docker-entrypoint-initdb.d/. This DB is cold storage only — reached exclusively through
-- postgres_fdw from the live gate DB (see DSL/Liquibase/changelog/20260910-consignments-archive.sql)
-- and never by Ruuter/ReSql directly.
--
-- Deliberately plain types (text, not citext/enum) and NO CHECK / FK / NOT NULL beyond the
-- copied row's own guarantees: this is a landing table, not a validated one. `row_id` is the
-- only key, so the copy can be `ON CONFLICT (row_id) DO NOTHING` and never fail on a re-run.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE consignments (
  row_id                        UUID          PRIMARY KEY,
  dataset_id                    UUID          NOT NULL,
  platform_id                   TEXT          NOT NULL,
  gate_id                       TEXT          NOT NULL,
  xml                           TEXT          NOT NULL,
  status                        TEXT          NOT NULL,
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
  created_at                    TIMESTAMPTZ   NOT NULL,
  archived_at                   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_archive_consignments_dataset      ON consignments (dataset_id, platform_id, created_at DESC);
CREATE INDEX idx_archive_consignments_archived_at  ON consignments (archived_at);

COMMENT ON TABLE consignments IS 'Cold storage for superseded (non-current) gate consignments rows. Written and read only via postgres_fdw from the live gate DB. No CHECK/FK; ON CONFLICT (row_id) DO NOTHING on copy.';
