/*
description: get consignment xml
params:
  datasetId: { type: string, required: true }
  gateId: { type: string, required: true }
  platformId: { type: string, required: true }
*/
SELECT
  row_id,
  dataset_id,
  platform_id,
  gate_id,
  xml,
  status::text,
  created_at
FROM (
  SELECT DISTINCT ON (dataset_id, platform_id)
    row_id, dataset_id, platform_id, gate_id, xml, status::text AS status, created_at
  FROM consignments
  WHERE dataset_id = :datasetId::uuid AND platform_id = :platformId
  ORDER BY dataset_id, platform_id, created_at DESC, row_id DESC
) latest
WHERE gate_id = :gateId;
