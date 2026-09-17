-- 1M consignments, but shaped for the transport-means query benchmarks instead of a flat
-- "1M all-distinct, one row each" set (docs/performance/askend_perf_verification/bulk-insert-1m.sql).
-- Total row count stays ~1M; the mix trades some of the flat baseline for two shapes the flat
-- set cannot exercise at all:
--
--   700 000 FLAT-<i>       same shape as bulk-insert-1m.sql (one row per dataset, all ids unique)
--   200 000 HIST-<d>-<r>   10 000 datasets x 20 revisions each, so latest-row resolution has to
--                          skip 19 superseded rows per dataset instead of finding the only row
--   100 000 WIDE-*         two equipment ids ('WIDE-SHARED-001'/'002') shared across 25 000 rows
--                          each on used_equipment_ids and 25 000 each on carried_equipment_ids
--                          (50 000 candidate rows per token), so a single OR branch returns a
--                          wide result set instead of 0 or 1 candidates
--
-- Replaces (does not add to) the flat set — DELETEs platform_id IN ('bulk','bulk-hist','bulk-wide')
-- first, same as bulk-insert-1m.sql's own DELETE. See analysis.md §8 for why and what it showed.
\timing on
DELETE FROM consignments WHERE platform_id IN ('bulk', 'bulk-hist', 'bulk-wide');

-- 700k flat baseline (unchanged shape, just 700k instead of 1M)
INSERT INTO consignments (
  dataset_id, platform_id, gate_id, xml, status,
  transport_mode, acceptance_country, delivery_country, dangerous_goods,
  main_transport_id, main_transport_type, transport_reg_country,
  used_equipment_ids, used_equipment_categories, carried_equipment_ids
)
SELECT
  gen_random_uuid(), 'bulk', 'EU-EE',
  '<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>FLAT-' || i || '</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>',
  'ACTIVE', '1', 'DE', 'EE', '2',
  'FLAT-' || i, '1513', 'DE',
  ARRAY['TE-' || (i*2), 'TE-' || (i*2+1)], ARRAY['T10','T10'], ARRAY['CT-' || (i*2+2), 'CT-' || (i*2+3)]
FROM generate_series(1, 700000) AS i;

-- 200k realistic history: 10 000 datasets, 20 revisions each, created_at strictly increasing
-- within a dataset so the latest row is unambiguous; each revision has its own identifier
-- (simulates re-upload/correction, like the AAA->BBB semantic fixture but at scale).
INSERT INTO consignments (
  dataset_id, platform_id, gate_id, xml, status,
  transport_mode, acceptance_country, delivery_country, dangerous_goods,
  main_transport_id, main_transport_type, transport_reg_country,
  used_equipment_ids, carried_equipment_ids, created_at
)
SELECT
  ('00000000-0000-0000-0000-' || lpad(d::text, 12, '0'))::uuid,
  'bulk-hist', 'EU-EE',
  '<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>HIST-' || d || '-' || r || '</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>',
  'ACTIVE', '1', 'DE', 'EE', '2',
  'HIST-' || d || '-' || r, '1513', 'DE',
  ARRAY['HTE-' || d || '-' || r], ARRAY['HCT-' || d || '-' || r],
  now() - ((20 - r) * interval '1 hour')
FROM generate_series(1, 10000) AS d, generate_series(1, 20) AS r;

-- 100k wide/combined OR: two shared equipment ids, each present on 50 000 otherwise-distinct
-- rows (25k via used_equipment_ids, 25k via carried_equipment_ids), so a single-token query
-- OR-matches a wide candidate set on two different index branches, not just one row.
INSERT INTO consignments (
  dataset_id, platform_id, gate_id, xml, status,
  transport_mode, acceptance_country, delivery_country, dangerous_goods,
  main_transport_id, main_transport_type, transport_reg_country,
  used_equipment_ids, carried_equipment_ids
)
SELECT
  gen_random_uuid(), 'bulk-wide', 'EU-EE',
  '<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>WIDE-MT-' || i || '</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>',
  'ACTIVE'::consignment_status, '1', 'DE', 'EE', '2',
  'WIDE-MT-' || i, '1513', 'DE',
  CASE WHEN i % 2 = 0 THEN ARRAY['WIDE-SHARED-001', 'TE-W-' || i] ELSE ARRAY['TE-W-' || i] END,
  CASE WHEN i % 2 = 1 THEN ARRAY['WIDE-SHARED-001', 'CT-W-' || i] ELSE ARRAY['CT-W-' || i] END
FROM generate_series(1, 50000) AS i
UNION ALL
SELECT
  gen_random_uuid(), 'bulk-wide', 'EU-EE',
  '<ParameterIDSetCriteria><MainCarriageTransportMeansIDParameterScope><ID>WIDE-MT2-' || i || '</ID></MainCarriageTransportMeansIDParameterScope></ParameterIDSetCriteria>',
  'ACTIVE'::consignment_status, '1', 'DE', 'EE', '2',
  'WIDE-MT2-' || i, '1513', 'DE',
  CASE WHEN i % 2 = 0 THEN ARRAY['WIDE-SHARED-002', 'TE-W2-' || i] ELSE ARRAY['TE-W2-' || i] END,
  CASE WHEN i % 2 = 1 THEN ARRAY['WIDE-SHARED-002', 'CT-W2-' || i] ELSE ARRAY['CT-W2-' || i] END
FROM generate_series(1, 50000) AS i;

ANALYZE consignments;

-- Sanity check: row counts per shape, and that deep history resolves to exactly the latest revision.
SELECT platform_id, count(*) FROM consignments WHERE platform_id IN ('bulk','bulk-hist','bulk-wide') GROUP BY platform_id ORDER BY 1;
SELECT count(*) AS should_be_1 FROM consignments c
  WHERE c.main_transport_id = 'HIST-5000-20' AND NOT EXISTS (
    SELECT 1 FROM consignments c2 WHERE c2.dataset_id = c.dataset_id AND c2.platform_id = c.platform_id AND c2.created_at > c.created_at);
SELECT count(*) AS should_be_0 FROM consignments c
  WHERE c.main_transport_id = 'HIST-5000-1' AND NOT EXISTS (
    SELECT 1 FROM consignments c2 WHERE c2.dataset_id = c.dataset_id AND c2.platform_id = c.platform_id AND c2.created_at > c.created_at);
