-- ----------------------------------------------------------------------------
-- 3.5 consignments
-- ----------------------------------------------------------------------------

CREATE TYPE consignment_status AS ENUM (
  'ACTIVE',
  'INACTIVE',
  'DELETED'
);

COMMENT ON TYPE consignment_status IS 'Lifecycle status of a stored consignment record. State transitions happen by INSERTing a new consignments row with the new status; the latest row wins.';

CREATE TABLE consignments (
  row_id                        UUID                PRIMARY KEY DEFAULT uuid_generate_v4(),
  dataset_id                    UUID                NOT NULL,
  platform_id                   CITEXT              NOT NULL,
  gate_id                       CITEXT              NOT NULL,
  xml                           TEXT                NOT NULL,
  status                        consignment_status  NOT NULL DEFAULT 'ACTIVE',
  transport_mode                CHAR(1),
  acceptance_date               TIMESTAMPTZ,
  acceptance_country            CHAR(2),
  delivery_date                 TIMESTAMPTZ,
  delivery_country              CHAR(2),
  dangerous_goods               VARCHAR(2),
  main_transport_id             TEXT,
  main_transport_type           TEXT,
  transport_reg_country         CHAR(2),
  loading_date                  TIMESTAMPTZ,
  loading_country               CHAR(2),
  unloading_date                TIMESTAMPTZ,
  unloading_country             CHAR(2),
  used_equipment_ids            TEXT[],
  used_equipment_categories     TEXT[],
  used_equipment_countries      TEXT[],
  used_equipment_seq            TEXT[],
  carried_equipment_ids         TEXT[],
  carried_equipment_categories  TEXT[],
  carried_equipment_seq         TEXT[],
  created_at                    TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

  CONSTRAINT consignments_acceptance_country_fmt    CHECK (acceptance_country    IS NULL OR acceptance_country    ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_delivery_country_fmt      CHECK (delivery_country      IS NULL OR delivery_country      ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_transport_reg_country_fmt CHECK (transport_reg_country IS NULL OR transport_reg_country ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_loading_country_fmt       CHECK (loading_country       IS NULL OR loading_country       ~ '^[A-Z]{2}$'),
  CONSTRAINT consignments_unloading_country_fmt     CHECK (unloading_country     IS NULL OR unloading_country     ~ '^[A-Z]{2}$')
);

COMMENT ON TABLE  consignments IS 'Stored consignment identifier metadata. Append-only: re-uploads and status flips all INSERT new rows with the same dataset_id.';
COMMENT ON COLUMN consignments.row_id                        IS 'Synthetic primary key, unique per row';
COMMENT ON COLUMN consignments.dataset_id                    IS 'Logical dataset UUID assigned by the platform; many rows over time. Latest by created_at is current.';
COMMENT ON COLUMN consignments.platform_id                   IS 'Logical platform.id at row-write time. Denormalised.';
COMMENT ON COLUMN consignments.gate_id                       IS 'Logical gate.id at row-write time. Denormalised.';
COMMENT ON COLUMN consignments.xml                           IS 'Identifier XML payload as received from the platform';
COMMENT ON COLUMN consignments.status                        IS 'Lifecycle status snapshot at row-write time';
COMMENT ON COLUMN consignments.transport_mode                IS 'Transport mode extracted from XML';
COMMENT ON COLUMN consignments.acceptance_date               IS 'Carrier acceptance date from XML';
COMMENT ON COLUMN consignments.acceptance_country            IS 'Carrier acceptance country (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.delivery_date                 IS 'Delivery date from XML';
COMMENT ON COLUMN consignments.delivery_country              IS 'Delivery country (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.dangerous_goods               IS 'Dangerous goods indication code from XML';
COMMENT ON COLUMN consignments.main_transport_id             IS 'Main carriage transport means ID from XML';
COMMENT ON COLUMN consignments.main_transport_type           IS 'Main carriage transport means type code from XML';
COMMENT ON COLUMN consignments.transport_reg_country         IS 'Transport means registration country (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.loading_date                  IS 'Main carriage loading date from XML';
COMMENT ON COLUMN consignments.loading_country               IS 'Main carriage loading country (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.unloading_date                IS 'Main carriage unloading date from XML';
COMMENT ON COLUMN consignments.unloading_country             IS 'Main carriage unloading country (ISO 3166-1 alpha-2)';
COMMENT ON COLUMN consignments.used_equipment_ids            IS 'Used transport equipment IDs from XML';
COMMENT ON COLUMN consignments.used_equipment_categories     IS 'Used transport equipment category codes from XML';
COMMENT ON COLUMN consignments.used_equipment_countries      IS 'Used transport equipment registration countries from XML';
COMMENT ON COLUMN consignments.used_equipment_seq            IS 'Used transport equipment sequence numbers from XML';
COMMENT ON COLUMN consignments.carried_equipment_ids         IS 'Carried transport equipment IDs from XML';
COMMENT ON COLUMN consignments.carried_equipment_categories  IS 'Carried transport equipment category codes from XML';
COMMENT ON COLUMN consignments.carried_equipment_seq         IS 'Carried transport equipment sequence numbers from XML';
COMMENT ON COLUMN consignments.created_at                    IS 'When this row was inserted';

CREATE INDEX idx_consignments_dataset_latest      ON consignments (dataset_id, platform_id, created_at DESC);
CREATE INDEX idx_consignments_platform            ON consignments (platform_id);
CREATE INDEX idx_consignments_status_active       ON consignments (status) WHERE status = 'ACTIVE';
CREATE INDEX idx_consignments_transport_mode      ON consignments (transport_mode);
CREATE INDEX idx_consignments_acceptance_date     ON consignments (acceptance_date);
CREATE INDEX idx_consignments_acceptance_country  ON consignments (acceptance_country);
CREATE INDEX idx_consignments_delivery_date       ON consignments (delivery_date);
CREATE INDEX idx_consignments_delivery_country    ON consignments (delivery_country);
CREATE INDEX idx_consignments_dangerous_goods     ON consignments (dangerous_goods);
CREATE INDEX idx_consignments_main_transport_id   ON consignments (main_transport_id);
CREATE INDEX idx_consignments_main_transport_type ON consignments (main_transport_type);
CREATE INDEX idx_consignments_transport_reg_country ON consignments (transport_reg_country);
CREATE INDEX idx_consignments_loading_date        ON consignments (loading_date);
CREATE INDEX idx_consignments_loading_country     ON consignments (loading_country);
CREATE INDEX idx_consignments_unloading_date      ON consignments (unloading_date);
CREATE INDEX idx_consignments_unloading_country   ON consignments (unloading_country);
CREATE INDEX idx_consignments_used_equip_ids      ON consignments USING gin (used_equipment_ids);
CREATE INDEX idx_consignments_used_equip_cat      ON consignments USING gin (used_equipment_categories);
CREATE INDEX idx_consignments_used_equip_ctry     ON consignments USING gin (used_equipment_countries);
CREATE INDEX idx_consignments_used_equip_seq      ON consignments USING gin (used_equipment_seq);
CREATE INDEX idx_consignments_carried_equip_ids   ON consignments USING gin (carried_equipment_ids);
CREATE INDEX idx_consignments_carried_equip_cat   ON consignments USING gin (carried_equipment_categories);
CREATE INDEX idx_consignments_carried_equip_seq   ON consignments USING gin (carried_equipment_seq);

GRANT SELECT, INSERT ON consignments TO app;
GRANT SELECT, DELETE ON consignments TO db_archiver;
