/*
description: (archive DB) read the archived (cold-storage) rows for a dataset. Used by the
  history-read fallback — the caller merges these with the live current row(s).
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
  transport_mode,
  main_transport_id,
  main_transport_type,
  used_equipment_ids,
  carried_equipment_ids,
  created_at,
  archived_at
FROM consignments
WHERE dataset_id = :datasetId::uuid
ORDER BY created_at DESC;
