/*
description: get follow up messages
params:
  datasetId:
    type: string
  followUpId:
    type: string
  limit:
    type: string
  offset:
    type: string
*/
SELECT
  row_id,
  follow_up_id,
  requesting_gate_id,
  dataset_request_id,
  destination_gate_id,
  destination_platform_id,
  payload,
  status::text,
  failure_reason,
  received_at,
  created_at
FROM follow_up_log
WHERE dataset_request_id = :datasetId::uuid
  AND (:followUpId IS NULL OR :followUpId = '' OR follow_up_id = :followUpId::uuid)
ORDER BY received_at DESC
LIMIT COALESCE(:limit, 20) OFFSET COALESCE(:offset, 0);
