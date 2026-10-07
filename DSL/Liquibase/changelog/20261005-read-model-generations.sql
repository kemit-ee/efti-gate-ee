--liquibase formatted sql

--changeset efti:20261005-read-model-generations splitStatements:false
--comment ADR-012 read-model layer: INSERT-only generation snapshots plus an INSERT-only pointer naming the current generation.

CREATE SEQUENCE read_model_generation_seq;

CREATE TABLE read_model_pointer (
  revision     BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  model        TEXT        NOT NULL,
  generation   BIGINT      NOT NULL,
  published_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE read_model_pointer IS 'ADR-012: the current generation of each read model is the row with the highest revision for that model. INSERT-only; a snapshot is published by inserting a row in the same statement that wrote it.';

CREATE INDEX idx_read_model_pointer_model ON read_model_pointer (model, revision DESC);

CREATE TABLE rm_consignment_counts (
  generation        BIGINT             NOT NULL,
  gate_id           CITEXT             NOT NULL,
  platform_id       CITEXT             NOT NULL,
  status            consignment_status NOT NULL,
  consignment_count BIGINT             NOT NULL,
  created_at        TIMESTAMPTZ        NOT NULL DEFAULT now(),
  PRIMARY KEY (generation, gate_id, platform_id, status)
);

COMMENT ON TABLE rm_consignment_counts IS 'ADR-012 read model: number of current (latest-row) consignments per gate, platform and status, one full snapshot per generation. Derivative of consignments; rebuilt by POST /ops/v1/refresh-read-models.';

GRANT USAGE, SELECT ON SEQUENCE read_model_generation_seq TO app;
GRANT USAGE, SELECT ON SEQUENCE read_model_pointer_revision_seq TO app;
GRANT SELECT, INSERT ON read_model_pointer TO app;
GRANT SELECT, DELETE ON read_model_pointer TO db_archiver;
GRANT SELECT, INSERT ON rm_consignment_counts TO app;
GRANT SELECT, DELETE ON rm_consignment_counts TO db_archiver;
--rollback DROP TABLE rm_consignment_counts; DROP TABLE read_model_pointer; DROP SEQUENCE read_model_generation_seq;
