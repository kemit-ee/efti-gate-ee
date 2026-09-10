-- 1M consignments for load testing. Volume on top of the baseline data (sample.xml POST +
-- docs/performance/askend_perf_verification/seed-consignments.sql — see run.md).
-- Corrected vs docs/performance/bulk-insert-consignments.sql:
--   * gate_id = 'EU-EE' (was 'EE' — never matched local_search's OWN_GATE_ID filter)
--   * single INSERT ... SELECT generate_series (was a 100-iteration DO loop)
-- Query for an id in the set, e.g. main_transport_id = 'BULK-500000'; VESSEL-001 still
-- resolves to exactly the one seeded row.
--   docker compose -f compose.yml exec -T database psql -U efti -d efti < docs/performance/askend_perf_verification/bulk-insert-1m.sql
\timing on
DELETE FROM consignments WHERE platform_id = 'bulk';
INSERT INTO consignments (
  dataset_id, platform_id, gate_id, xml, status,
  transport_mode, acceptance_country, delivery_country, dangerous_goods,
  main_transport_id, main_transport_type, transport_reg_country,
  used_equipment_ids, used_equipment_categories, carried_equipment_ids
)
SELECT
  gen_random_uuid(), 'bulk', 'EU-EE',
  '<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>BULK-' || i || '</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>',
  'ACTIVE', '1', 'DE', 'EE', '2',
  'BULK-' || i, '1513', 'DE',
  ARRAY['TE-' || (i*2), 'TE-' || (i*2+1)], ARRAY['T10','T10'], ARRAY['CT-' || (i*2+2), 'CT-' || (i*2+3)]
FROM generate_series(1, 1000000) AS i;
ANALYZE consignments;
