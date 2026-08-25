--liquibase formatted sql

-- Transcribed from docs/specs/db/schema.sql §3.4 (users) and §4.2 (sessions).
-- Both tables are APPEND-ONLY: no UPDATE, no DELETE, ever. A role change, a
-- soft-delete, or a token revocation all INSERT a new row sharing the logical id;
-- the latest row by created_at is the current state. The COMMENT ON statements are
-- part of the contract, not decoration — docs/specs/db/README.md treats them as the
-- authoritative column documentation.

--changeset efti:003-create-users
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
  created_by        UUID,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  CONSTRAINT users_subsets_valid CHECK (subsets <@ ARRAY['EU01','EU02','EU03','EU04','EU05','EU06','EU07']::text[])
);

COMMENT ON TABLE  users IS 'Human users of the gate (authority officers, gate admins, and the single break-glass local-admin row). Append-only: every role/subset change INSERTs a new row with the same id. Primary auth is TARA OIDC; the gate matches the JWT `sub` claim against tara_sub to resolve a JWT to its users row. Platform identity is NOT modelled here (Platform API uses mTLS against platforms.cert_subject); G2G identity is at the AS4 access point.';
COMMENT ON COLUMN users.row_id           IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN users.id               IS 'Logical user identifier (UUID). Many rows over time; latest wins.';
COMMENT ON COLUMN users.tara_sub         IS 'The `sub` value the gate matches against on every JWT validation. For TARA-issued JWTs this is the Estonian PIC. For the single break-glass local-admin row it is the reserved literal ''local-admin'' (lower-case, never collides with a PIC). Never NULL — the lookup path is uniform across TARA and break-glass JWTs.';
COMMENT ON COLUMN users.email            IS 'Display / contact email. CITEXT for case-insensitive match. Used for the human-facing UI and audit-trail readability — NOT the auth identifier; tara_sub is.';
COMMENT ON COLUMN users.name             IS 'Display name';
COMMENT ON COLUMN users.is_admin         IS 'Bypass flag: TRUE skips role-level @Access checks (super admin).';
COMMENT ON COLUMN users.roles            IS 'Role → scope-IDs mapping. Only AUTHORITY and ADMIN entries: {"AUTHORITY":["auth-mta"]} for an authority officer, {"ADMIN":["eu-xx01"]} for a gate-scoped admin, or {} for a super admin. PLATFORM and GATE roles do not exist here — Platform is mTLS via platforms.cert_subject, G2G is mTLS at the AS4 access point.';
COMMENT ON COLUMN users.subsets          IS 'eFTI subsets this user (typically AUTHORITY role) is permitted to request. Must be a subset of the authority''s subsets.';
COMMENT ON COLUMN users.secret_hash      IS 'bcrypt hash of the break-glass local-admin password. NULL for the typical user — primary auth is TARA-issued OIDC JWT (Authority + Admin) or the platform''s eDelivery AP X.509 cert (Platform). Populated only on the single local-root row used during TARA outages and initial bootstrap; the break-glass path is exposed via POST /api/v1/auth/local-token, default-disabled (LOCAL_ADMIN_FALLBACK_ENABLED=false).';
COMMENT ON COLUMN users.token_revoked_at IS 'Per-user broadcast revocation marker. POST /api/v1/users/{userId}/revoke-token INSERTs a new users row with this column set to NOW(); on JWT validation the gate rejects any presented JWT whose `iat` claim predates the resolved user''s latest token_revoked_at. Distinct from the per-jti `sessions` denylist (which targets a specific JWT, e.g. on POST /api/v1/auth/logout); this column targets all currently-issued JWTs for the user. NULL means no broadcast revocation has occurred.';
COMMENT ON COLUMN users.is_active        IS 'Logical-deletion flag';
COMMENT ON COLUMN users.created_by       IS 'users.row_id of the actor that wrote this row (the admin creating/editing). NULL for self-registration / TARA-on-first-login flows.';
COMMENT ON COLUMN users.created_at       IS 'When this row was inserted';

CREATE INDEX idx_users_id_latest    ON users (id, created_at DESC);
CREATE INDEX idx_users_email_latest ON users (email, created_at DESC);
CREATE INDEX idx_users_tara_sub     ON users (tara_sub, created_at DESC);
CREATE INDEX idx_users_active       ON users (is_active) WHERE is_active = TRUE;

--rollback DROP TABLE users;

--changeset efti:004-create-sessions
CREATE TABLE sessions (
  row_id      UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id     UUID         NOT NULL,
  jti         VARCHAR(255) NOT NULL,
  expires_at  TIMESTAMPTZ  NOT NULL,
  revoked_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  reason      VARCHAR(64),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  sessions IS 'JWT denylist. INSERT-only. Holds (jti, revoked_at) entries for tokens explicitly revoked before TARA-side exp. AccessChecker rejects any JWT whose jti is here AND whose exp is in the future. Rows past expires_at are archived by CronManager. Duplicates on jti are tolerated (idempotency: a second logout on the same JWT is a no-op INSERT, not a 409 — the read pattern is EXISTS WHERE jti=$1 AND expires_at > NOW()).';
COMMENT ON COLUMN sessions.row_id     IS 'Synthetic primary key';
COMMENT ON COLUMN sessions.user_id    IS 'Logical users.id of the JWT subject at revocation time. Denormalised, no FK.';
COMMENT ON COLUMN sessions.jti        IS 'JWT ID claim (RFC 7519 §4.1.7) — the logical identifier of the revoked token. Not unique: duplicates from multiple revoke calls on the same token are tolerated.';
COMMENT ON COLUMN sessions.expires_at IS 'Copied from the JWT exp claim. Once exp < NOW() the row stops being load-bearing and becomes archival candidate.';
COMMENT ON COLUMN sessions.revoked_at IS 'When the revocation event was recorded.';
COMMENT ON COLUMN sessions.reason     IS 'Revocation reason: ''logout'' (user-initiated), ''admin_revoke'' (POST /api/v1/users/{id}/revoke-token), ''incident'' (operator forced), …';
COMMENT ON COLUMN sessions.created_at IS 'When this row was inserted (== revoked_at).';

CREATE INDEX idx_sessions_jti        ON sessions (jti);
CREATE INDEX idx_sessions_user_id    ON sessions (user_id);
CREATE INDEX idx_sessions_expires_at ON sessions (expires_at);

--rollback DROP TABLE sessions;
