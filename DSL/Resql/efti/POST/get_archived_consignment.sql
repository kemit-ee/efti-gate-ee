/*
description: read a consignment's archived (cold-storage) history from the separate archive DB
params:
  datasetId: { type: string, required: true }
*/
SELECT
  row_id::text,
  dataset_id::text,
  platform_id,
  gate_id,
  status,
  xml,
  main_transport_id,
  created_at,
  archived_at
FROM archive.consignments
WHERE dataset_id = :datasetId::uuid
ORDER BY created_at DESC;
