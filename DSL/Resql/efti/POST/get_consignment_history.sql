/*
description: every LIVE row for a dataset (all versions, newest first) — not just the current one.
  Pair with archive/get_archived_consignments for the rows that have been swept to cold storage.
params:
  datasetId: { type: string, required: true }
*/
SELECT
  row_id::text,
  dataset_id::text,
  platform_id,
  gate_id,
  status::text,
  xml,
  transport_mode,
  main_transport_id,
  main_transport_type,
  used_equipment_ids,
  carried_equipment_ids,
  created_at
FROM consignments
WHERE dataset_id = :datasetId::uuid
ORDER BY created_at DESC;
