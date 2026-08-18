-- ----------------------------------------------------------------------------
-- 3.4 users
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS users (
  row_id            UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id                UUID         NOT NULL,
  tara_sub          TEXT         NOT NULL,
  name              TEXT         NOT NULL,
  secret_hash       TEXT,
  token_revoked_at  TIMESTAMPTZ,
  is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by        UUID,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  users IS 'Human admin users of the gate. Append-only: every role/subset change INSERTs a new row with the same id. Primary auth is TARA OIDC; gate matches JWT `sub` against `tara_sub`.';
COMMENT ON COLUMN users.row_id            IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN users.id                IS 'Logical user identifier (UUID). Many rows over time; latest wins.';
COMMENT ON COLUMN users.tara_sub          IS 'JWT `sub` value the gate matches against. For TARA JWTs this is the Estonian PIC. For break-glass local-admin the reserved literal ''local-admin''.';
COMMENT ON COLUMN users.name              IS 'Display name';
COMMENT ON COLUMN users.secret_hash       IS 'bcrypt hash of the break-glass local-admin password. NULL for normal users.';
COMMENT ON COLUMN users.token_revoked_at  IS 'Per-user broadcast revocation marker. NULL means no broadcast revocation has occurred.';
COMMENT ON COLUMN users.is_active         IS 'Logical-deletion flag';
COMMENT ON COLUMN users.created_by        IS 'users.row_id of the actor that wrote this row.';
COMMENT ON COLUMN users.created_at        IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_users_id_latest    ON users (id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_users_tara_sub     ON users (tara_sub, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_users_active       ON users (is_active) WHERE is_active = TRUE;

GRANT SELECT, INSERT ON users TO app;
GRANT SELECT, DELETE ON users TO db_archiver;
