--liquibase formatted sql

--changeset efti:20261007-read-model-consignment-summary splitStatements:false
--comment ADR-012 DENORM-4: per-day consignment counts per eFTI subset and dimension, INSERT-only generation snapshots.

CREATE TABLE rm_consignment_summary (
  generation        BIGINT NOT NULL,
  subset            TEXT   NOT NULL,
  dimension         TEXT   NOT NULL,
  dim_value         TEXT   NOT NULL,
  day               DATE   NOT NULL,
  consignment_count BIGINT NOT NULL,
  PRIMARY KEY (generation, subset, dimension, dim_value, day)
);

COMMENT ON TABLE rm_consignment_summary IS 'ADR-012 read model: current ACTIVE consignments counted per registration day, per dimension value. subset names the eFTI subset (EU02..EU04) that entitles an authority to the dimension; the empty subset is the ungated total. Derivative of consignments; the caller''s entitlement is always read from authorities.';

GRANT SELECT, INSERT ON rm_consignment_summary TO app;
GRANT SELECT, DELETE ON rm_consignment_summary TO db_archiver;
--rollback DROP TABLE rm_consignment_summary;
