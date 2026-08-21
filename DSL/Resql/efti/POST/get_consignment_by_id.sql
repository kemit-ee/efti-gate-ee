SELECT DISTINCT ON (dataset_id)
  row_id,
  dataset_id,
  platform_id,
  gate_id,
  status::text,
  created_at
FROM consignments
WHERE dataset_id = :datasetId::uuid
ORDER BY dataset_id, created_at DESC;
