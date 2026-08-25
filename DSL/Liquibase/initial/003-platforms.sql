-- ----------------------------------------------------------------------------
-- 3.2 platforms
-- ----------------------------------------------------------------------------

CREATE TABLE platforms (
  row_id          UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id              CITEXT       NOT NULL,
  base_url        TEXT,
  headers         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  e_delivery_cert TEXT,
  tls_cert        TEXT,
  cert_subject    TEXT,
  cert_serial     TEXT,
  status          gate_status  NOT NULL DEFAULT 'ONLINE',
  created_by      UUID        DEFAULT get_app_user(),
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  platforms IS 'Registry of eFTI platforms registered with this gate. Append-only: each edit is a new row sharing the same id; latest wins.';
COMMENT ON COLUMN platforms.row_id              IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN platforms.id                  IS 'Logical platform identifier (e.g. plt-xxx-001). Many rows can share this id over time.';
COMMENT ON COLUMN platforms.base_url            IS 'Platform''s REST API base URL';
COMMENT ON COLUMN platforms.headers             IS 'Custom headers (e.g. API key) the gate sends with platform requests';
COMMENT ON COLUMN platforms.e_delivery_cert     IS 'Public certificate (PEM) for AS4 communication with this platform';
COMMENT ON COLUMN platforms.tls_cert            IS 'Public TLS certificate (PEM) for HTTPS communication';
COMMENT ON COLUMN platforms.cert_subject        IS 'Subject DN of the platform''s eDelivery AP X.509 certificate. Used for inbound mTLS lookup.';
COMMENT ON COLUMN platforms.cert_serial         IS 'Serial number of the eDelivery AP certificate. Together with cert_subject forms the natural key for inbound-mTLS lookup.';
COMMENT ON COLUMN platforms.status              IS 'Current platform operational status: ONLINE — active and available; OFFLINE — ping failed; DISABLED — administratively disabled (visible in list); DELETED — soft deletion (removed by operator, row retained for audit).';
COMMENT ON COLUMN platforms.created_by          IS 'users.row_id of the actor that wrote this row';
COMMENT ON COLUMN platforms.created_at          IS 'When this row was inserted';

CREATE INDEX idx_platforms_id_latest   ON platforms (id, created_at DESC);
CREATE INDEX idx_platforms_status      ON platforms (status);
CREATE INDEX idx_platforms_cert_lookup ON platforms (cert_subject, cert_serial);

GRANT SELECT, INSERT ON platforms TO app;
GRANT SELECT, DELETE ON platforms TO db_archiver;
