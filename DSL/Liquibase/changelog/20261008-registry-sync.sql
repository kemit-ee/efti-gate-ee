--liquibase formatted sql

--changeset efti:20261008-registry-sync splitStatements:false
-- ADR-014: gates, platforms and authorities are now sourced from the git folder registry/** and
-- applied at startup by the one-shot registry-sync container. The admin API no longer writes these
-- three tables, which removes the only caller the reactivation guard was written for — and in the
-- declarative model re-adding a removed registry file MUST bring the entity back, so a guard that
-- raises 'Cannot reactivate deleted <table>' turns a legitimate commit into a boot failure.
-- The guard therefore now covers `users` only, whose CRUD stays on the API (ADR-011 §9).
CREATE OR REPLACE FUNCTION protect_registry_append() RETURNS trigger LANGUAGE plpgsql AS $$
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
  ELSIF TG_TABLE_NAME = 'platforms' THEN
    IF (previous->>'api_key_generated_at')::timestamptz IS NOT NULL
      AND (NEW.api_key_generated_at IS NULL OR NEW.api_key_generated_at < (previous->>'api_key_generated_at')::timestamptz) THEN
      NEW.api_key_hash := (previous->>'api_key_hash')::bytea;
      NEW.api_key_hint := previous->>'api_key_hint';
      NEW.api_key_generated_at := (previous->>'api_key_generated_at')::timestamptz;
    END IF;
  END IF;
  RETURN NEW;
END $$;

COMMENT ON FUNCTION protect_registry_append() IS 'BEFORE INSERT trigger shared by users, gates, platforms and authorities: serialises appends per logical id with an advisory transaction lock, assigns the revision, and enforces the per-table append invariants. Since ADR-014 the DELETED-reactivation guard covers users only — gates/platforms/authorities are declaratively re-applied from registry/** on every startup, where reviving a removed entry is the intended behaviour. For platforms it still carries api_key_hash/hint/generated_at forward unless the insert supplies an equal-or-newer key.';

--rollback CREATE OR REPLACE FUNCTION protect_registry_append() RETURNS trigger LANGUAGE plpgsql AS $$
--rollback DECLARE
--rollback   previous JSONB;
--rollback   id_type TEXT;
--rollback BEGIN
--rollback   PERFORM pg_advisory_xact_lock(hashtextextended(TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME || ':' || lower(NEW.id::text), 0));
--rollback   id_type := CASE WHEN TG_TABLE_NAME = 'users' THEN 'uuid' ELSE 'citext' END;
--rollback   EXECUTE format('SELECT to_jsonb(t) FROM %I.%I t WHERE id = $1::%s ORDER BY created_at DESC, revision DESC LIMIT 1', TG_TABLE_SCHEMA, TG_TABLE_NAME, id_type)
--rollback     INTO previous USING NEW.id::text;
--rollback   NEW.revision := nextval(pg_get_serial_sequence(format('%I.%I', TG_TABLE_SCHEMA, TG_TABLE_NAME), 'revision'));
--rollback   IF TG_TABLE_NAME = 'users' THEN
--rollback     IF previous IS NOT NULL THEN
--rollback       NEW.is_active := NEW.is_active AND (previous->>'is_active')::boolean;
--rollback       NEW.token_revoked_at := GREATEST(NEW.token_revoked_at, (previous->>'token_revoked_at')::timestamptz);
--rollback     END IF;
--rollback   ELSE
--rollback     IF previous->>'status' = 'DELETED' AND NEW.status::text <> 'DELETED' THEN
--rollback       RAISE EXCEPTION 'Cannot reactivate deleted % %', TG_TABLE_NAME, NEW.id USING ERRCODE = '23514';
--rollback     END IF;
--rollback     IF TG_TABLE_NAME = 'platforms' THEN
--rollback       IF (previous->>'api_key_generated_at')::timestamptz IS NOT NULL
--rollback         AND (NEW.api_key_generated_at IS NULL OR NEW.api_key_generated_at < (previous->>'api_key_generated_at')::timestamptz) THEN
--rollback         NEW.api_key_hash := (previous->>'api_key_hash')::bytea;
--rollback         NEW.api_key_hint := previous->>'api_key_hint';
--rollback         NEW.api_key_generated_at := (previous->>'api_key_generated_at')::timestamptz;
--rollback       END IF;
--rollback     END IF;
--rollback   END IF;
--rollback   RETURN NEW;
--rollback END $$;
