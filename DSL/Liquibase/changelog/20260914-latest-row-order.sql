--liquibase formatted sql

--changeset efti:latest-row-order splitStatements:false
ALTER TABLE users ADD COLUMN revision BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE gates ADD COLUMN revision BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE platforms ADD COLUMN revision BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE authorities ADD COLUMN revision BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE consignments ADD COLUMN revision BIGINT GENERATED ALWAYS AS IDENTITY;

DROP INDEX idx_users_id_latest;
DROP INDEX idx_gates_id_latest;
DROP INDEX idx_platforms_id_latest;
DROP INDEX idx_authorities_id_latest;
DROP INDEX idx_consignments_dataset_latest;
CREATE INDEX idx_users_id_latest ON users (id, created_at DESC, revision DESC);
CREATE INDEX idx_gates_id_latest ON gates (id, created_at DESC, revision DESC);
CREATE INDEX idx_platforms_id_latest ON platforms (id, created_at DESC, revision DESC);
CREATE INDEX idx_authorities_id_latest ON authorities (id, created_at DESC, revision DESC);
CREATE INDEX idx_consignments_dataset_latest ON consignments (dataset_id, platform_id, created_at DESC, revision DESC);
GRANT USAGE, SELECT ON SEQUENCE users_revision_seq, gates_revision_seq,
  platforms_revision_seq, authorities_revision_seq, consignments_revision_seq TO app;

CREATE FUNCTION protect_registry_append() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  previous JSONB;
  id_type TEXT;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME || ':' || lower(NEW.id::text), 0));
  id_type := CASE WHEN TG_TABLE_NAME = 'users' THEN 'uuid' ELSE 'citext' END;
  EXECUTE format('SELECT to_jsonb(t) FROM %I.%I t WHERE id = $1::%s ORDER BY created_at DESC, revision DESC LIMIT 1', TG_TABLE_SCHEMA, TG_TABLE_NAME, id_type)
    INTO previous USING NEW.id::text;
  NEW.revision := nextval(pg_get_serial_sequence(format('%I.%I', TG_TABLE_SCHEMA, TG_TABLE_NAME), 'revision'));
  IF TG_TABLE_NAME = 'users' THEN
    IF previous IS NOT NULL THEN
      NEW.is_active := NEW.is_active AND (previous->>'is_active')::boolean;
      NEW.token_revoked_at := GREATEST(NEW.token_revoked_at, (previous->>'token_revoked_at')::timestamptz);
    END IF;
  ELSE
    IF previous->>'status' = 'DELETED' AND NEW.status::text <> 'DELETED' THEN
      RAISE EXCEPTION 'Cannot reactivate deleted % %', TG_TABLE_NAME, NEW.id USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'platforms' THEN
      IF (previous->>'api_key_generated_at')::timestamptz IS NOT NULL
        AND (NEW.api_key_generated_at IS NULL OR NEW.api_key_generated_at < (previous->>'api_key_generated_at')::timestamptz) THEN
        NEW.api_key_hash := (previous->>'api_key_hash')::bytea;
        NEW.api_key_hint := previous->>'api_key_hint';
        NEW.api_key_generated_at := (previous->>'api_key_generated_at')::timestamptz;
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER protect_users_append BEFORE INSERT ON users FOR EACH ROW EXECUTE FUNCTION protect_registry_append();
CREATE TRIGGER protect_gates_append BEFORE INSERT ON gates FOR EACH ROW EXECUTE FUNCTION protect_registry_append();
CREATE TRIGGER protect_platforms_append BEFORE INSERT ON platforms FOR EACH ROW EXECUTE FUNCTION protect_registry_append();
CREATE TRIGGER protect_authorities_append BEFORE INSERT ON authorities FOR EACH ROW EXECUTE FUNCTION protect_registry_append();
