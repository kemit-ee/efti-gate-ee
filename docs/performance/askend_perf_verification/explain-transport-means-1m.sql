-- EXPLAIN (ANALYZE, BUFFERS) for the CURRENT get_consignments_by_transport_means.sql /
-- check_transport_means_registered.sql (post e4830d5 "perf: use array containment for
-- transport filters" — @> instead of ANY(), same candidate-narrowing + LATERAL latest-row
-- pattern as both files ship today). Run against the same 1M dataset as the C6 measurements
-- (docs/performance/askend_perf_verification/bulk-insert-1m.sql + seed-consignments.sql).
\timing on

-- 1) main_transport_id exact match, single hit (mirrors the C6 'BULK-500000' measurement)
EXPLAIN (ANALYZE, BUFFERS)
WITH candidates AS MATERIALIZED (
  SELECT DISTINCT dataset_id, platform_id
  FROM consignments
  WHERE main_transport_id = 'BULK-500000'
     OR used_equipment_ids @> ARRAY['BULK-500000']
     OR carried_equipment_ids @> ARRAY['BULK-500000']
)
SELECT
  jsonb_build_object('gateId', latest.gate_id, 'platformId', latest.platform_id, 'datasetId', latest.dataset_id) AS uil,
  latest.main_transport_id, latest.status, latest.created_at
FROM candidates candidate
CROSS JOIN LATERAL (
  SELECT row_id, revision, dataset_id, platform_id, gate_id, main_transport_id, status::text AS status,
         used_equipment_ids, carried_equipment_ids, transport_reg_country, created_at
  FROM consignments
  WHERE dataset_id = candidate.dataset_id AND platform_id = candidate.platform_id
  ORDER BY created_at DESC, revision DESC
  LIMIT 1
) latest
WHERE (latest.main_transport_id = 'BULK-500000'
       OR latest.used_equipment_ids @> ARRAY['BULK-500000']
       OR latest.carried_equipment_ids @> ARRAY['BULK-500000'])
  AND latest.status = 'ACTIVE'
  AND (NULL::text IS NULL)
ORDER BY latest.created_at DESC, latest.revision DESC
LIMIT 50;

-- 2) used_equipment_ids match (container/equipment id, not the main carriage id)
EXPLAIN (ANALYZE, BUFFERS)
WITH candidates AS MATERIALIZED (
  SELECT DISTINCT dataset_id, platform_id
  FROM consignments
  WHERE main_transport_id = 'TE-1000000'
     OR used_equipment_ids @> ARRAY['TE-1000000']
     OR carried_equipment_ids @> ARRAY['TE-1000000']
)
SELECT
  jsonb_build_object('gateId', latest.gate_id, 'platformId', latest.platform_id, 'datasetId', latest.dataset_id) AS uil,
  latest.main_transport_id, latest.status, latest.created_at
FROM candidates candidate
CROSS JOIN LATERAL (
  SELECT row_id, revision, dataset_id, platform_id, gate_id, main_transport_id, status::text AS status,
         used_equipment_ids, carried_equipment_ids, transport_reg_country, created_at
  FROM consignments
  WHERE dataset_id = candidate.dataset_id AND platform_id = candidate.platform_id
  ORDER BY created_at DESC, revision DESC
  LIMIT 1
) latest
WHERE (latest.main_transport_id = 'TE-1000000'
       OR latest.used_equipment_ids @> ARRAY['TE-1000000']
       OR latest.carried_equipment_ids @> ARRAY['TE-1000000'])
  AND latest.status = 'ACTIVE'
  AND (NULL::text IS NULL)
ORDER BY latest.created_at DESC, latest.revision DESC
LIMIT 50;

-- 3) Identifier absent entirely (worst case for candidate-narrowing: 3-way OR scans all three
--    indexes and finds nothing, no early exit)
EXPLAIN (ANALYZE, BUFFERS)
WITH candidates AS MATERIALIZED (
  SELECT DISTINCT dataset_id, platform_id
  FROM consignments
  WHERE main_transport_id = 'NOT-PRESENT-XYZ'
     OR used_equipment_ids @> ARRAY['NOT-PRESENT-XYZ']
     OR carried_equipment_ids @> ARRAY['NOT-PRESENT-XYZ']
)
SELECT
  jsonb_build_object('gateId', latest.gate_id, 'platformId', latest.platform_id, 'datasetId', latest.dataset_id) AS uil,
  latest.main_transport_id, latest.status, latest.created_at
FROM candidates candidate
CROSS JOIN LATERAL (
  SELECT row_id, revision, dataset_id, platform_id, gate_id, main_transport_id, status::text AS status,
         used_equipment_ids, carried_equipment_ids, transport_reg_country, created_at
  FROM consignments
  WHERE dataset_id = candidate.dataset_id AND platform_id = candidate.platform_id
  ORDER BY created_at DESC, revision DESC
  LIMIT 1
) latest
WHERE (latest.main_transport_id = 'NOT-PRESENT-XYZ'
       OR latest.used_equipment_ids @> ARRAY['NOT-PRESENT-XYZ']
       OR latest.carried_equipment_ids @> ARRAY['NOT-PRESENT-XYZ'])
  AND latest.status = 'ACTIVE'
  AND (NULL::text IS NULL)
ORDER BY latest.created_at DESC, latest.revision DESC
LIMIT 50;

-- 4) check_transport_means_registered.sql — existence check, single hit
EXPLAIN (ANALYZE, BUFFERS)
SELECT EXISTS (
  SELECT 1
  FROM (
    SELECT DISTINCT dataset_id, platform_id
    FROM consignments
    WHERE main_transport_id = 'BULK-500000'
       OR used_equipment_ids @> ARRAY['BULK-500000']
       OR carried_equipment_ids @> ARRAY['BULK-500000']
  ) candidate
  CROSS JOIN LATERAL (
    SELECT main_transport_id, used_equipment_ids, carried_equipment_ids, transport_reg_country, status::text AS status
    FROM consignments
    WHERE dataset_id = candidate.dataset_id AND platform_id = candidate.platform_id
    ORDER BY created_at DESC, revision DESC
    LIMIT 1
  ) latest
  WHERE (latest.main_transport_id = 'BULK-500000'
         OR latest.used_equipment_ids @> ARRAY['BULK-500000']
         OR latest.carried_equipment_ids @> ARRAY['BULK-500000'])
    AND latest.status = 'ACTIVE'
    AND (NULL::text IS NULL)
  LIMIT 1
) AS registered;

-- 5) check_transport_means_registered.sql — not registered (absent id, worst case)
EXPLAIN (ANALYZE, BUFFERS)
SELECT EXISTS (
  SELECT 1
  FROM (
    SELECT DISTINCT dataset_id, platform_id
    FROM consignments
    WHERE main_transport_id = 'NOT-PRESENT-XYZ'
       OR used_equipment_ids @> ARRAY['NOT-PRESENT-XYZ']
       OR carried_equipment_ids @> ARRAY['NOT-PRESENT-XYZ']
  ) candidate
  CROSS JOIN LATERAL (
    SELECT main_transport_id, used_equipment_ids, carried_equipment_ids, transport_reg_country, status::text AS status
    FROM consignments
    WHERE dataset_id = candidate.dataset_id AND platform_id = candidate.platform_id
    ORDER BY created_at DESC, revision DESC
    LIMIT 1
  ) latest
  WHERE (latest.main_transport_id = 'NOT-PRESENT-XYZ'
         OR latest.used_equipment_ids @> ARRAY['NOT-PRESENT-XYZ']
         OR latest.carried_equipment_ids @> ARRAY['NOT-PRESENT-XYZ'])
    AND latest.status = 'ACTIVE'
    AND (NULL::text IS NULL)
  LIMIT 1
) AS registered;
