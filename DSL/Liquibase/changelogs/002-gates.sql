-- ----------------------------------------------------------------------------
-- 3.1 gates
-- ----------------------------------------------------------------------------

CREATE TYPE gate_status AS ENUM (
  'ONLINE',
  'OFFLINE',
  'DISABLED',
  'DELETED'
);

COMMENT ON TYPE gate_status IS 'Operational status of an eFTI gate or platform node';

CREATE TABLE gates (
  row_id          UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  id              CITEXT       NOT NULL,
  country_code    CHAR(2)      NOT NULL,
  e_delivery_url  TEXT,
  e_delivery_cert TEXT,
  tls_cert        TEXT,
  status          gate_status  NOT NULL,
  last_ping_at    TIMESTAMPTZ,
  created_by      UUID,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  CONSTRAINT gates_country_format CHECK (country_code ~ '^[A-Z]{2}$')
);

COMMENT ON TABLE  gates IS 'Registry of eFTI gates (own + remote peers). Append-only: each registry change (status flip, ping, URL/cert update) is a new row with the same id. The latest row by created_at is the gate''s current state. Cron-archived by CronManager.';
COMMENT ON COLUMN gates.row_id          IS 'Synthetic primary key, unique per row (one entity has many rows over time)';
COMMENT ON COLUMN gates.id              IS 'Logical gate identifier in eu-{cc}{nn} format (e.g. eu-ee01). Many rows can share this id over time; latest wins.';
COMMENT ON COLUMN gates.country_code    IS 'ISO 3166-1 alpha-2 country code';
COMMENT ON COLUMN gates.e_delivery_url  IS 'AS4 access-point URL for inbound G2G messages';
COMMENT ON COLUMN gates.e_delivery_cert IS 'Public certificate (PEM) used to verify AS4 messages from this gate';
COMMENT ON COLUMN gates.tls_cert        IS 'Public TLS certificate (PEM) used to verify the gate''s HTTPS endpoint';
COMMENT ON COLUMN gates.status          IS 'Current gate operational status: ONLINE — active and available; OFFLINE — ping failed; DISABLED — administratively disabled (visible in list); DELETED — soft deletion (removed by operator, row retained for audit).';
COMMENT ON COLUMN gates.last_ping_at    IS 'Timestamp of the latest successful ping that produced this row. NULL if this row pre-dates first ping.';
COMMENT ON COLUMN gates.created_by      IS 'Denormalised users.row_id of the actor. NULL for system events (ping job, registry sync).';
COMMENT ON COLUMN gates.created_at      IS 'When this row was inserted. Latest created_at per id is the current state.';

CREATE INDEX idx_gates_id_latest ON gates (id, created_at DESC);
CREATE INDEX idx_gates_status    ON gates (status);
CREATE INDEX idx_gates_country   ON gates (country_code);

GRANT SELECT, INSERT ON gates TO app;
GRANT SELECT, DELETE ON gates TO db_archiver;
