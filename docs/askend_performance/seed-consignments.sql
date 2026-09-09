-- ---------------------------------------------------------------------------
-- Base data for the consignments-search benchmarks (docs/askend_performance/analysis.md).
--
-- Fixes vs docs/performance/bulk-insert-consignments.sql (Anton's original):
--   * gate_id = 'EU-EE'  (was 'EE' — OWN_GATE_ID is 'EU-EE' in constants.ini, so the old
--     rows never passed local_search's `gate_id = :gateId` filter and every search fell
--     through to the multiplexer)
--   * a deterministic, findable row: main_transport_id = 'VESSEL-001', matching
--     docs/askend_performance/search.json / resql-search.json and code/xml-mapper/xsd/FTI004/sample.xml
--   * denormalised criteria columns actually populated (Anton's DO-loop left most NULL)
--
-- Run (small deterministic set only):
--   docker compose -f compose.yml exec -T database psql -U efti -d efti < docs/askend_performance/seed-consignments.sql
-- For volume, follow with docs/askend_performance/bulk-insert-1m.sql.
-- ---------------------------------------------------------------------------
\timing on

-- Clean re-seed of just the benchmark rows (append-only table: DELETE is a test-harness
-- privilege, not something the `app` role can do).
DELETE FROM consignments WHERE platform_id IN ('mock', 'p1', 'bulk');

-- ---------------------------------------------------------------------------
-- 1. The findable baseline row — one consignment, current version, gate EU-EE.
--    A search for mainTransportId EQ 'VESSEL-001' must return exactly this row.
-- ---------------------------------------------------------------------------
INSERT INTO consignments (
  dataset_id, platform_id, gate_id, xml, status,
  transport_mode,
  acceptance_date, acceptance_country,
  delivery_date, delivery_country,
  dangerous_goods,
  main_transport_id, main_transport_type, transport_reg_country,
  loading_date, loading_country,
  unloading_date, unloading_country,
  used_equipment_ids, used_equipment_categories, used_equipment_countries, used_equipment_seq,
  carried_equipment_ids, carried_equipment_categories, carried_equipment_seq
) VALUES (
  '00000000-0000-0000-0000-000000000001', 'mock', 'EU-EE',
  '<ParameterIDSetCriteria>'
    '<CarrierAcceptanceCountryParameterScope><CountryID>DE</CountryID></CarrierAcceptanceCountryParameterScope>'
    '<DeliveryCountryParameterScope><CountryID>EE</CountryID></DeliveryCountryParameterScope>'
    '<DangerousGoodsIndicationCodeParameterScope><DangerousGoodsIndicationParameterCode>2</DangerousGoodsIndicationParameterCode></DangerousGoodsIndicationCodeParameterScope>'
    '<MainCarriageTransportMeansIDParameterScope><ID>VESSEL-001</ID></MainCarriageTransportMeansIDParameterScope>'
    '<MainCarriageModeCodeParameterScope><TransportModeParameterCode>1</TransportModeParameterCode></MainCarriageModeCodeParameterScope>'
    '<MainCarriageTransportMeansTypeCodeParameterScope><TransportMeansParameterCode>1513</TransportMeansParameterCode></MainCarriageTransportMeansTypeCodeParameterScope>'
    '<TransportMeansRegistrationCountryParameterScope><CountryID>DE</CountryID></TransportMeansRegistrationCountryParameterScope>'
    '<MainCarriageLoadingCountryParameterScope><CountryID>FI</CountryID></MainCarriageLoadingCountryParameterScope>'
    '<MainCarriageUnloadingCountryParameterScope><CountryID>AE</CountryID></MainCarriageUnloadingCountryParameterScope>'
    '<UsedTransportEquipmentIDParameterScope><ID>TE-001</ID><ID>TE-002</ID></UsedTransportEquipmentIDParameterScope>'
    '<CarriedTransportEquipmentIDParameterScope><ID>CT-001</ID><ID>CT-002</ID></CarriedTransportEquipmentIDParameterScope>'
  '</ParameterIDSetCriteria>',
  'ACTIVE',
  '1',
  '2021-09-24 00:00:00+00', 'DE',
  '2021-09-24 00:00:00+00', 'EE',
  '2',
  'VESSEL-001', '1513', 'DE',
  '2021-09-24 00:00:00+00', 'FI',
  '2021-09-24 00:00:00+00', 'AE',
  ARRAY['TE-001','TE-002'], ARRAY['T10','T10'], ARRAY['DE','DE'], ARRAY[1,2],
  ARRAY['CT-001','CT-002'], ARRAY['BPR','BPR'], ARRAY[3,4]
);

-- ---------------------------------------------------------------------------
-- 2. Semantic fixtures — an append-only re-upload and a single-version dataset.
--    dataset p1/1111...  uploaded twice: main_transport_id AAA (old) -> BBB (current).
--    dataset p1/2222...  uploaded once:  main_transport_id CCC.
--    A search for 'AAA' must return nothing (it is no longer the current version);
--    a search for 'BBB' and 'CCC' must return both. Drives semantic-test.sql.
-- ---------------------------------------------------------------------------
INSERT INTO consignments (dataset_id, platform_id, gate_id, xml, status, main_transport_id, created_at) VALUES
 ('11111111-1111-1111-1111-111111111111', 'p1', 'EU-EE', '<x/>', 'ACTIVE', 'AAA', now() - interval '1 hour'),
 ('11111111-1111-1111-1111-111111111111', 'p1', 'EU-EE', '<x/>', 'ACTIVE', 'BBB', now()),
 ('22222222-2222-2222-2222-222222222222', 'p1', 'EU-EE', '<x/>', 'ACTIVE', 'CCC', now());

ANALYZE consignments;

-- Sanity check: expect VESSEL-001 -> 1, AAA -> 0, BBB -> 1, CCC -> 1 (current versions only).
SELECT
  mt AS search_id,
  (SELECT count(*) FROM consignments c
     WHERE c.main_transport_id = probe.mt
       AND c.gate_id = 'EU-EE'
       AND c.status <> 'DELETED'
       AND NOT EXISTS (SELECT 1 FROM consignments c2
                       WHERE c2.platform_id = c.platform_id
                         AND c2.dataset_id  = c.dataset_id
                         AND c2.created_at   > c.created_at)) AS current_matches
FROM (VALUES ('VESSEL-001'), ('AAA'), ('BBB'), ('CCC')) AS probe(mt);
