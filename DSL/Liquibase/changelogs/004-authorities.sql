-- ----------------------------------------------------------------------------
-- 3.3 authorities
-- ----------------------------------------------------------------------------

CREATE TYPE authority_status AS ENUM (
  'ACTIVE',
  'DELETED'
);

COMMENT ON TYPE authority_status IS 'Lifecycle status of a competent authority. ACTIVE — visible and operational; DELETED — soft-deleted (removed by operator, row retained for audit).';

CREATE TABLE authorities (
  row_id        UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id            CITEXT       NOT NULL,
  name          TEXT         NOT NULL,
  registry_code TEXT         NOT NULL,
  subsets       TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
  status        authority_status NOT NULL DEFAULT 'ACTIVE',
  created_by    UUID,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  authorities IS 'Registry of competent authorities. Append-only: each edit is a new row sharing the same id; latest wins.';
COMMENT ON COLUMN authorities.row_id        IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN authorities.id            IS 'Logical authority identifier (e.g. auth-mta). Many rows over time.';
COMMENT ON COLUMN authorities.name          IS 'Human-readable name (e.g. "Estonian Tax and Customs Board")';
COMMENT ON COLUMN authorities.registry_code IS 'Estonian Business Registry code of the authority, used for access control';
COMMENT ON COLUMN authorities.subsets       IS 'eFTI subsets this authority is permitted to request. Constrained to EU01..EU07.';
COMMENT ON COLUMN authorities.status        IS 'Current authority status: ACTIVE — visible and operational; DELETED — soft-deleted (removed by operator, row retained for audit).';
COMMENT ON COLUMN authorities.created_by    IS 'users.row_id of the actor that wrote this row';
COMMENT ON COLUMN authorities.created_at    IS 'When this row was inserted';

CREATE INDEX idx_authorities_id_latest       ON authorities (id, created_at DESC);
CREATE INDEX idx_authorities_registry_code   ON authorities (registry_code);
CREATE INDEX idx_authorities_status          ON authorities (status);

GRANT SELECT, INSERT ON authorities TO app;
GRANT SELECT, DELETE ON authorities TO db_archiver;
