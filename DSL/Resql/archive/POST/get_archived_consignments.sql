/*
description: (archive DB) one page of the archived (cold-storage) versions of a dataset. Used by the
  history-read fallback — the caller merges these with the live current row(s). Metadata only, the xml
  payload is deliberately not projected.
params:
  datasetId: { type: string, required: true }
  limit: { type: number, default: 100 }
  offset: { type: number, default: 0 }
*/
SELECT
  row_id::text,
  dataset_id::text,
  platform_id,
  gate_id,
  status,
  transport_mode,
  main_transport_id,
  main_transport_type,
  used_equipment_ids,
  carried_equipment_ids,
  created_at,
  archived_at
FROM consignments
WHERE dataset_id = :datasetId::uuid
ORDER BY created_at DESC, row_id DESC
LIMIT LEAST(GREATEST(COALESCE(:limit, 100), 0), 1000) OFFSET GREATEST(COALESCE(:offset, 0), 0);
