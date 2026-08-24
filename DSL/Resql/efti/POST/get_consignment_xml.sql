SELECT DISTINCT ON (dataset_id)
  row_id,
  dataset_id,
  platform_id,
  gate_id,
  xml,
  status::text,
  created_at
FROM consignments
WHERE dataset_id = :datasetId::uuid
  AND (:platformId IS NULL OR :platformId = '' OR platform_id = :platformId)
  AND (:gateId IS NULL OR :gateId = '' OR gate_id = :gateId)
ORDER BY dataset_id, created_at DESC;
