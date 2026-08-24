-- ----------------------------------------------------------------------------
-- 3.4 users
-- ----------------------------------------------------------------------------

CREATE TABLE users (
  row_id            UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id                UUID         NOT NULL,
  tara_sub          TEXT         NOT NULL,
  email             CITEXT       NOT NULL,
  name              TEXT         NOT NULL,
  is_admin          BOOLEAN      NOT NULL DEFAULT FALSE,
  roles             JSONB        NOT NULL DEFAULT '{}'::jsonb,
  subsets           TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
  secret_hash       TEXT,
  token_revoked_at  TIMESTAMPTZ,
  is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by        UUID         DEFAULT get_app_user(),
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  CONSTRAINT users_subsets_valid CHECK (subsets <@ ARRAY['EU01','EU02','EU03','EU04','EU05','EU06','EU07']::text[])
);

COMMENT ON TABLE  users IS 'Human users of the gate (authority officers, gate admins, and the single break-glass local-admin row). Append-only: every role/subset change INSERTs a new row with the same id. Primary auth is TARA OIDC; the gate matches the JWT `sub` claim against tara_sub to resolve a JWT to its users row.';
COMMENT ON COLUMN users.row_id           IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN users.id               IS 'Logical user identifier (UUID). Many rows over time; latest wins.';
COMMENT ON COLUMN users.tara_sub         IS 'The `sub` value the gate matches against on every JWT validation. For TARA-issued JWTs this is the Estonian PIC. For the single break-glass local-admin row it is the reserved literal ''local-admin''.';
COMMENT ON COLUMN users.email            IS 'Display / contact email. CITEXT for case-insensitive match.';
COMMENT ON COLUMN users.name             IS 'Display name';
COMMENT ON COLUMN users.is_admin         IS 'Bypass flag: TRUE skips role-level @Access checks (super admin).';
COMMENT ON COLUMN users.roles            IS 'Role → scope-IDs mapping. {"AUTHORITY":["auth-mta"]} for an authority officer, {"ADMIN":["eu-xx01"]} for a gate-scoped admin, or {} for a super admin.';
COMMENT ON COLUMN users.subsets          IS 'eFTI subsets this user is permitted to request.';
COMMENT ON COLUMN users.secret_hash      IS 'bcrypt hash of the break-glass local-admin password. NULL for normal users.';
COMMENT ON COLUMN users.token_revoked_at IS 'Per-user broadcast revocation marker. NULL means no broadcast revocation has occurred.';
COMMENT ON COLUMN users.is_active        IS 'Logical-deletion flag';
COMMENT ON COLUMN users.created_by       IS 'users.row_id of the actor that wrote this row.';
COMMENT ON COLUMN users.created_at       IS 'When this row was inserted';

CREATE INDEX idx_users_id_latest    ON users (id, created_at DESC);
CREATE INDEX idx_users_email_latest ON users (email, created_at DESC);
CREATE INDEX idx_users_tara_sub     ON users (tara_sub, created_at DESC);
CREATE INDEX idx_users_active       ON users (is_active) WHERE is_active = TRUE;

GRANT SELECT, INSERT ON users TO app;
GRANT SELECT, DELETE ON users TO db_archiver;
