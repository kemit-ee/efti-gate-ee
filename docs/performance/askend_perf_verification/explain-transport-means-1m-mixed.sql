-- EXPLAIN (ANALYZE, BUFFERS) for get_consignments_by_transport_means.sql /
-- check_transport_means_registered.sql against the mixed dataset
-- (bulk-insert-1m-mixed.sql): flat baseline, deep history, and wide/combined OR shapes.
\timing on

-- 1) Flat baseline, single hit (same shape as explain-transport-means-1m.sql query 1, for
--    a same-machine before/after comparison)
EXPLAIN (ANALYZE, BUFFERS)
WITH candidates AS MATERIALIZED (
  SELECT DISTINCT dataset_id, platform_id
  FROM consignments
  WHERE main_transport_id = 'FLAT-500000'
     OR used_equipment_ids @> ARRAY['FLAT-500000']
     OR carried_equipment_ids @> ARRAY['FLAT-500000']
)
SELECT jsonb_build_object('gateId', latest.gate_id, 'platformId', latest.platform_id, 'datasetId', latest.dataset_id) AS uil,
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
WHERE (latest.main_transport_id = 'FLAT-500000'
       OR latest.used_equipment_ids @> ARRAY['FLAT-500000']
       OR latest.carried_equipment_ids @> ARRAY['FLAT-500000'])
  AND latest.status = 'ACTIVE'
  AND (NULL::text IS NULL)
ORDER BY latest.created_at DESC, latest.revision DESC
LIMIT 50;

-- 2) Deep history, CURRENT revision (dataset has 19 superseded rows the LATERAL join must skip)
EXPLAIN (ANALYZE, BUFFERS)
WITH candidates AS MATERIALIZED (
  SELECT DISTINCT dataset_id, platform_id
  FROM consignments
  WHERE main_transport_id = 'HIST-5000-20'
     OR used_equipment_ids @> ARRAY['HIST-5000-20']
     OR carried_equipment_ids @> ARRAY['HIST-5000-20']
)
SELECT jsonb_build_object('gateId', latest.gate_id, 'platformId', latest.platform_id, 'datasetId', latest.dataset_id) AS uil,
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
WHERE (latest.main_transport_id = 'HIST-5000-20'
       OR latest.used_equipment_ids @> ARRAY['HIST-5000-20']
       OR latest.carried_equipment_ids @> ARRAY['HIST-5000-20'])
  AND latest.status = 'ACTIVE'
  AND (NULL::text IS NULL)
ORDER BY latest.created_at DESC, latest.revision DESC
LIMIT 50;

-- 3) Deep history, SUPERSEDED revision (must correctly return nothing: the row exists but is
--    not the dataset's latest, exactly the correctness property C6/ADR-009 exists to protect)
EXPLAIN (ANALYZE, BUFFERS)
WITH candidates AS MATERIALIZED (
  SELECT DISTINCT dataset_id, platform_id
  FROM consignments
  WHERE main_transport_id = 'HIST-5000-1'
     OR used_equipment_ids @> ARRAY['HIST-5000-1']
     OR carried_equipment_ids @> ARRAY['HIST-5000-1']
)
SELECT jsonb_build_object('gateId', latest.gate_id, 'platformId', latest.platform_id, 'datasetId', latest.dataset_id) AS uil,
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
WHERE (latest.main_transport_id = 'HIST-5000-1'
       OR latest.used_equipment_ids @> ARRAY['HIST-5000-1']
       OR latest.carried_equipment_ids @> ARRAY['HIST-5000-1'])
  AND latest.status = 'ACTIVE'
  AND (NULL::text IS NULL)
ORDER BY latest.created_at DESC, latest.revision DESC
LIMIT 50;

-- 4) Wide/combined OR: 50 000 candidate rows match (25k via used_equipment_ids, 25k via
--    carried_equipment_ids) — only the top 50 by created_at are returned, but every candidate's
--    latest row still has to be resolved before the ORDER BY/LIMIT can pick the winners.
EXPLAIN (ANALYZE, BUFFERS)
WITH candidates AS MATERIALIZED (
  SELECT DISTINCT dataset_id, platform_id
  FROM consignments
  WHERE main_transport_id = 'WIDE-SHARED-001'
     OR used_equipment_ids @> ARRAY['WIDE-SHARED-001']
     OR carried_equipment_ids @> ARRAY['WIDE-SHARED-001']
)
SELECT jsonb_build_object('gateId', latest.gate_id, 'platformId', latest.platform_id, 'datasetId', latest.dataset_id) AS uil,
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
WHERE (latest.main_transport_id = 'WIDE-SHARED-001'
       OR latest.used_equipment_ids @> ARRAY['WIDE-SHARED-001']
       OR latest.carried_equipment_ids @> ARRAY['WIDE-SHARED-001'])
  AND latest.status = 'ACTIVE'
  AND (NULL::text IS NULL)
ORDER BY latest.created_at DESC, latest.revision DESC
LIMIT 50;

-- 5) check_transport_means_registered on the SAME wide token — unlike
--    get_consignments_by_transport_means.sql's `WITH candidates AS MATERIALIZED`, this file's
--    candidate set is a plain derived subquery, so the planner is free to fold it into the
--    outer EXISTS + LIMIT 1 and short-circuit instead of computing all 50 000 candidates.
EXPLAIN (ANALYZE, BUFFERS)
SELECT EXISTS (
  SELECT 1
  FROM (
    SELECT DISTINCT dataset_id, platform_id
    FROM consignments
    WHERE main_transport_id = 'WIDE-SHARED-001'
       OR used_equipment_ids @> ARRAY['WIDE-SHARED-001']
       OR carried_equipment_ids @> ARRAY['WIDE-SHARED-001']
  ) candidate
  CROSS JOIN LATERAL (
    SELECT main_transport_id, used_equipment_ids, carried_equipment_ids, transport_reg_country, status::text AS status
    FROM consignments
    WHERE dataset_id = candidate.dataset_id AND platform_id = candidate.platform_id
    ORDER BY created_at DESC, revision DESC
    LIMIT 1
  ) latest
  WHERE (latest.main_transport_id = 'WIDE-SHARED-001'
         OR latest.used_equipment_ids @> ARRAY['WIDE-SHARED-001']
         OR latest.carried_equipment_ids @> ARRAY['WIDE-SHARED-001'])
    AND latest.status = 'ACTIVE'
    AND (NULL::text IS NULL)
  LIMIT 1
) AS registered;
