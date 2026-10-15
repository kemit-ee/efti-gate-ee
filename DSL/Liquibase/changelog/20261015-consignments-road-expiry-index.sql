--liquibase formatted sql

--changeset efti:20261015-consignments-road-expiry-index
-- Cabotage-expiry sweep (expire_road_consignments.sql, POST /ops/v1/expire-identifiers): road
-- rows that are still ACTIVE, by carrier acceptance date. Stays small: an expired dataset's ACTIVE
-- row is superseded by its INACTIVE copy and then moved out by the archive sweep.
CREATE INDEX idx_consignments_road_expiry ON consignments (acceptance_date) WHERE transport_mode = '3' AND status = 'ACTIVE';
--rollback DROP INDEX IF EXISTS idx_consignments_road_expiry;
