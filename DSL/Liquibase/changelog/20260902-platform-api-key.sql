--liquibase formatted sql

--changeset efti:platform-api-key
-- ADR-004 (2026-08-25 — Rainer Türner, Sten Viljus, Anton Keks): platforms
-- authenticate to the gate with an API key in the X-Api-Key header. The key is
-- never stored in clear — only its SHA-256 hash (api_key_hash). api_key_hint is
-- the first 8 hex chars of that hash, shown in the admin UI so an operator can
-- tell which key is active without being able to recover it.
-- Since ADR-014 the hash (and therefore the key) is declared in
-- registry/platforms/<id>.json and applied by the registry-sync container; the
-- admin route that used to mint keys at runtime no longer exists.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
GRANT EXECUTE ON FUNCTION digest(text, text) TO app;
GRANT EXECUTE ON FUNCTION gen_random_bytes(integer) TO app;

ALTER TABLE platforms
  ADD COLUMN api_key_hash         BYTEA,
  ADD COLUMN api_key_hint         TEXT,
  ADD COLUMN api_key_generated_at TIMESTAMPTZ;

COMMENT ON COLUMN platforms.api_key_hash         IS 'SHA-256 of the platform''s X-Api-Key credential. Never store or log the key itself.';
COMMENT ON COLUMN platforms.api_key_hint         IS 'First 8 hex chars of api_key_hash — a non-reversible label for the admin UI.';
COMMENT ON COLUMN platforms.api_key_generated_at IS 'When the current API key was generated.';

CREATE INDEX idx_platforms_api_key_hash ON platforms (api_key_hash);

--rollback DROP INDEX IF EXISTS idx_platforms_api_key_hash;
--rollback ALTER TABLE platforms DROP COLUMN IF EXISTS api_key_hash, DROP COLUMN IF EXISTS api_key_hint, DROP COLUMN IF EXISTS api_key_generated_at;
