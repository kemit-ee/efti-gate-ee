-- Migration: V1__initial_schema.sql
-- Description: Initial database schema for eFTI Gate v2.0
-- Author: Development Team
-- Date: 2026-04-22
-- Database: PostgreSQL 14+
--
-- Source: Derived from gate/db/*.sql (Liquibase changesets)
-- Rollback: DROP SCHEMA public CASCADE; CREATE SCHEMA public;
--           WARNING: Destroys ALL data in database

-- =====================================================
-- EXTENSIONS
-- =====================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid(), sha256
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive text type

-- =====================================================
-- FUNCTIONS (must exist before triggers)
-- =====================================================

CREATE OR REPLACE PROCEDURE set_app_user(userId uuid) LANGUAGE plpgsql AS $$
BEGIN
  PERFORM set_config('app.user', userId::text, true);
END; $$;

CREATE OR REPLACE FUNCTION get_app_user() RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE
  userId VARCHAR;
BEGIN
  userId := current_setting('app.user', true);
  RETURN CASE WHEN userId = '' THEN NULL ELSE userId::uuid END;
END; $$;

-- =====================================================
-- CHANGE HISTORY TABLE & TRIGGER FUNCTION
-- =====================================================

CREATE TABLE IF NOT EXISTS change_history (
  "table"     VARCHAR     NOT NULL,
  rowId       VARCHAR     NOT NULL,
  "column"    VARCHAR     NOT NULL,
  oldValue    VARCHAR,
  newValue    VARCHAR,
  changedAt   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  changedBy   UUID
);

CREATE INDEX IF NOT EXISTS change_history_id_idx ON change_history ("table", rowId);

CREATE OR REPLACE FUNCTION add_change_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  col      NAME;
  oldValue TEXT;
  newValue TEXT;
BEGIN
  FOR col IN (
    SELECT attname FROM pg_catalog.pg_attribute a
      JOIN pg_catalog.pg_class c ON c.oid = a.attrelid AND c.relname = tg_table_name
      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace AND n.nspname = tg_table_schema
    WHERE a.attnum > 0 AND NOT a.attisdropped)
  LOOP
    EXECUTE format('SELECT (($1).%I)::text', col) USING new INTO newValue;
    EXECUTE format('SELECT (($1).%I)::text', col) USING old INTO oldValue;
    IF (oldValue IS NOT DISTINCT FROM newValue) THEN CONTINUE; END IF;

    INSERT INTO change_history ("table", rowId, "column", oldValue, newValue, changedBy)
    VALUES (tg_table_name, new.id, col, oldValue, newValue, get_app_user());
  END LOOP;
  RETURN new;
END; $$;

-- =====================================================
-- GATES TABLE
-- =====================================================

CREATE TABLE IF NOT EXISTS gates (
  id              CITEXT      PRIMARY KEY,
  countryCode     VARCHAR(2)  NOT NULL,
  eDeliveryUrl    TEXT        NOT NULL,
  eDeliveryCert   TEXT        NOT NULL,
  tlsCert         TEXT,
  status          TEXT        NOT NULL DEFAULT 'ONLINE',
  createdAt       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER gates_history
  AFTER UPDATE ON gates
  FOR EACH ROW EXECUTE FUNCTION add_change_history();

-- =====================================================
-- PLATFORMS TABLE
-- =====================================================

CREATE TABLE IF NOT EXISTS platforms (
  id                  CITEXT      PRIMARY KEY,
  baseUrl             TEXT        NOT NULL,
  headers             JSONB       NOT NULL DEFAULT '{}',
  supportsSubsetting  BOOLEAN     NOT NULL DEFAULT TRUE,
  eDeliveryCert       TEXT,
  tlsCert             TEXT,
  createdAt           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER platforms_history
  AFTER UPDATE ON platforms
  FOR EACH ROW EXECUTE FUNCTION add_change_history();

-- =====================================================
-- AUTHORITIES TABLE
-- =====================================================

CREATE TABLE IF NOT EXISTS authorities (
  id          CITEXT      PRIMARY KEY,
  subsets     TEXT[]      NOT NULL,
  countryCode VARCHAR(2)  NOT NULL,
  name        TEXT
);

CREATE TRIGGER authorities_history
  AFTER UPDATE ON authorities
  FOR EACH ROW EXECUTE FUNCTION add_change_history();

-- =====================================================
-- USERS TABLE
-- =====================================================

CREATE TABLE IF NOT EXISTS users (
  id          UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  name        TEXT        NOT NULL,
  email       TEXT        UNIQUE,
  isAdmin     BOOLEAN     NOT NULL DEFAULT FALSE,
  subsets     TEXT[],
  roles       JSONB       NOT NULL DEFAULT '{}',
  secretHash  TEXT        NOT NULL,
  createdAt   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  createdBy   UUID        REFERENCES users (id)
);

CREATE TRIGGER users_history
  AFTER UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION add_change_history();

-- =====================================================
-- CONSIGNMENTS TABLE
-- =====================================================

CREATE TABLE IF NOT EXISTS consignments (
  datasetId       UUID        PRIMARY KEY,
  platformId      TEXT        NOT NULL,
  gateId          TEXT        NOT NULL,
  xml             TEXT        NOT NULL,
  mode            TEXT,
  dangerousGoods  BOOLEAN,
  createdAt       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updatedAt       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deliveredAt     TIMESTAMPTZ
);

-- =====================================================
-- IDENTIFIERS TABLE
-- =====================================================

CREATE TABLE IF NOT EXISTS identifiers (
  id          TEXT        NOT NULL,
  datasetId   UUID        NOT NULL REFERENCES consignments (datasetId) ON DELETE CASCADE,
  type        TEXT        NOT NULL,
  countryCode TEXT,
  PRIMARY KEY (id, datasetId)
);

CREATE INDEX IF NOT EXISTS identifiers_dataset_idx ON identifiers (datasetId);

-- =====================================================
-- ASYNC RESPONSES TABLE
-- =====================================================

CREATE TABLE IF NOT EXISTS async_responses (
  receiverId  TEXT        NOT NULL,
  requestId   TEXT        NOT NULL,
  body        TEXT        NOT NULL,
  createdAt   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (receiverId, requestId)
);

-- =====================================================
-- APPLICATION USER PERMISSIONS
-- =====================================================

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app') THEN
    CREATE ROLE app;
  END IF;
END $$;

GRANT SELECT, INSERT, UPDATE ON consignments    TO app;
GRANT SELECT, INSERT, UPDATE ON identifiers     TO app;
GRANT SELECT, INSERT, UPDATE ON platforms       TO app;
GRANT SELECT, INSERT, UPDATE ON authorities     TO app;
GRANT SELECT, INSERT, UPDATE ON users           TO app;
GRANT SELECT, INSERT, UPDATE ON gates           TO app;
GRANT SELECT, INSERT, UPDATE ON async_responses TO app;
GRANT SELECT, INSERT         ON change_history  TO app;

GRANT DELETE ON consignments    TO app;
GRANT DELETE ON identifiers     TO app;
GRANT DELETE ON platforms       TO app;
GRANT DELETE ON authorities     TO app;
GRANT DELETE ON users           TO app;
GRANT DELETE ON gates           TO app;
GRANT DELETE ON async_responses TO app;

-- =====================================================
-- MIGRATION COMPLETE
-- =====================================================

DO $$
BEGIN
  RAISE NOTICE 'Migration V1 completed: Initial schema created (gates, platforms, authorities, users, consignments, identifiers, async_responses, change_history)';
END $$;
