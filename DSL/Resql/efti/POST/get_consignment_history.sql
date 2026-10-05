/*
description: one page of the LIVE versions of a dataset (newest first) — not just the current one. Metadata
  only, the xml payload is deliberately not projected, so a dataset an uploader has versioned many times
  cannot inflate the response. Pair with archive/get_archived_consignments for the rows swept to cold storage.
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
  status::text,
  transport_mode,
  main_transport_id,
  main_transport_type,
  used_equipment_ids,
  carried_equipment_ids,
  created_at
FROM consignments
WHERE dataset_id = :datasetId::uuid
ORDER BY created_at DESC, revision DESC
LIMIT LEAST(GREATEST(COALESCE(:limit, 100), 0), 1000) OFFSET GREATEST(COALESCE(:offset, 0), 0);
