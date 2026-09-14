/*
description: get current consignment versions, optionally narrowed by UIL platform and gate
params:
  datasetId: { type: string, required: true }
  platformId: { type: string }
  gateId: { type: string }
*/
SELECT
  row_id,
  dataset_id,
  platform_id,
  gate_id,
  status::text,
  created_at
FROM (
  SELECT DISTINCT ON (dataset_id, platform_id)
    row_id, dataset_id, platform_id, gate_id, status::text AS status, created_at
  FROM consignments
  WHERE dataset_id = :datasetId::uuid
    AND (:platformId IS NULL OR platform_id = :platformId)
  ORDER BY dataset_id, platform_id, created_at DESC, row_id DESC
) latest
WHERE :gateId IS NULL OR gate_id = :gateId
ORDER BY platform_id;
