--liquibase formatted sql

--changeset efti:20261006-read-model-registries splitStatements:false
--comment ADR-012 DENORM-5: INSERT-only generation snapshots of the latest gate, platform and authority rows for the admin list endpoints.

CREATE TABLE rm_gates (
  generation      BIGINT      NOT NULL,
  id              CITEXT      NOT NULL,
  row_id          UUID        NOT NULL,
  country_code    CHAR(2)     NOT NULL,
  e_delivery_url  TEXT,
  e_delivery_cert TEXT,
  tls_cert        TEXT,
  status          TEXT        NOT NULL,
  last_ping_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (generation, id)
);

CREATE TABLE rm_platforms (
  generation           BIGINT      NOT NULL,
  id                   CITEXT      NOT NULL,
  row_id               UUID        NOT NULL,
  base_url             TEXT,
  headers              JSONB       NOT NULL,
  e_delivery_cert      TEXT,
  tls_cert             TEXT,
  status               TEXT        NOT NULL,
  api_key_hint         TEXT,
  api_key_generated_at TIMESTAMPTZ,
  has_api_key          BOOLEAN     NOT NULL,
  created_at           TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (generation, id)
);

CREATE TABLE rm_authorities (
  generation    BIGINT      NOT NULL,
  id            CITEXT      NOT NULL,
  row_id        UUID        NOT NULL,
  name          TEXT        NOT NULL,
  registry_code TEXT        NOT NULL,
  subsets       TEXT[]      NOT NULL,
  status        TEXT        NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (generation, id)
);

COMMENT ON TABLE rm_gates IS 'ADR-012 read model for GET /admin/v1/gates (list). Derivative of gates; never used for outbound routing or authorisation.';
COMMENT ON TABLE rm_platforms IS 'ADR-012 read model for GET /admin/v1/platforms (list). Carries no api_key_hash. Derivative of platforms; never used for authentication.';
COMMENT ON TABLE rm_authorities IS 'ADR-012 read model for GET /admin/v1/authorities (list). Derivative of authorities; never used for subset entitlement.';

GRANT SELECT, INSERT ON rm_gates, rm_platforms, rm_authorities TO app;
GRANT SELECT, DELETE ON rm_gates, rm_platforms, rm_authorities TO db_archiver;
--rollback DROP TABLE rm_authorities; DROP TABLE rm_platforms; DROP TABLE rm_gates;
