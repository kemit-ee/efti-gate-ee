--liquibase formatted sql

--changeset efti:20261009-drop-registry-tables splitStatements:false
-- ADR-015: the gates, platforms and authorities registries no longer live in the database. They are
-- authored as files under registry/{gates,platforms,authorities}/<id>.json and served over HTTP by
-- the `registry` service (see registry/README.md), so the three tables, their ADR-012 read models
-- and the two enums that only described their status go away. Dropping the tables also removes
-- their indexes and the protect_*_append triggers.
DROP TABLE IF EXISTS rm_gates, rm_platforms, rm_authorities;
DROP TABLE IF EXISTS gates, platforms, authorities;
DROP TYPE IF EXISTS gate_status, authority_status;

-- protect_registry_append() now serves `users` alone — its CRUD is the one that stayed on the admin
-- API (ADR-011 §9): per-id advisory transaction lock, revision assignment, and is_active /
-- token_revoked_at preservation so a stale insert cannot undo a logical deletion or a revocation.
-- The platforms api_key carry-forward branch and the DELETED-reactivation guard are gone with the
-- tables they were written for.
CREATE OR REPLACE FUNCTION protect_registry_append() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  previous JSONB;
  id_type TEXT;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME || ':' || lower(NEW.id::text), 0));
  id_type := 'uuid';
  EXECUTE format('SELECT to_jsonb(t) FROM %I.%I t WHERE id = $1::%s ORDER BY created_at DESC, revision DESC LIMIT 1', TG_TABLE_SCHEMA, TG_TABLE_NAME, id_type)
    INTO previous USING NEW.id::text;
  NEW.revision := nextval(pg_get_serial_sequence(format('%I.%I', TG_TABLE_SCHEMA, TG_TABLE_NAME), 'revision'));
  IF previous IS NOT NULL THEN
    NEW.is_active := NEW.is_active AND (previous->>'is_active')::boolean;
    NEW.token_revoked_at := GREATEST(NEW.token_revoked_at, (previous->>'token_revoked_at')::timestamptz);
  END IF;
  RETURN NEW;
END $$;

COMMENT ON FUNCTION protect_registry_append() IS 'BEFORE INSERT trigger on users: serialises appends per logical id with an advisory transaction lock, assigns the revision and preserves is_active/token_revoked_at, so a stale insert cannot resurrect a deleted user or clear a revocation. Gates, platforms and authorities moved to the registry files (ADR-015) and no longer have tables.';

-- read_model_pointer and read_model_generation_seq stay: the consignment read models still use them.
-- pgcrypto is kept though nothing in this repo calls digest()/gen_random_bytes() any more (it was
-- installed for the platforms api_key_hash by 20260902-platform-api-key.sql) — it belongs to an
-- already-applied changeset and must not be dropped from under a database that has it.
--
-- No rollback block: these tables held the registry data that is now served from registry/**. A
-- rollback would recreate them empty rather than restore the previous state, and would leave the
-- registry files and the tables disagreeing. Revert the registry files instead.
