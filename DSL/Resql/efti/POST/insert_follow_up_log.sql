/*
description: insert follow up log (with a queryId the follow_up_log_once trigger skips a DELIVERED row when the latest row of the same queryId, platform and dataset request is already DELIVERED, and the statement returns no row; a FAILED row lets a retry through)
params:
  datasetRequestId: { type: string, required: true }
  destinationGateId: { type: string, required: true }
  destinationPlatformId: { type: string, required: true }
  failureReason: { type: string }
  followUpId: { type: string }
  payload: { type: string, required: true }
  queryId: { type: string }
  requestingGateId: { type: string, required: true }
  status: { type: string, default: "DELIVERED" }
*/
INSERT INTO follow_up_log (
  follow_up_id,
  requesting_gate_id,
  dataset_request_id,
  destination_gate_id,
  destination_platform_id,
  payload,
  status,
  failure_reason,
  query_id
) VALUES (
  COALESCE(:followUpId::uuid, uuid_generate_v4()),
  :requestingGateId,
  :datasetRequestId::uuid,
  :destinationGateId,
  :destinationPlatformId,
  :payload,
  COALESCE(:status, 'DELIVERED')::follow_up_status,
  :failureReason,
  :queryId::uuid
)
RETURNING
  row_id,
  follow_up_id,
  requesting_gate_id,
  dataset_request_id,
  destination_gate_id,
  destination_platform_id,
  status::text,
  received_at,
  created_at;
