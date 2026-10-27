/*
description: insert follow up log (a PENDING row is skipped by the follow_up_log_once trigger when the latest row of the same queryId, platform, dataset request and content is DELIVERED or a PENDING younger than 120 seconds; the statement then returns no row)
params:
  contentHash: { type: string }
  contentJson: { type: string }
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
  query_id,
  content_hash
) VALUES (
  COALESCE(:followUpId::uuid, uuid_generate_v4()),
  :requestingGateId,
  :datasetRequestId::uuid,
  :destinationGateId,
  :destinationPlatformId,
  :payload,
  COALESCE(:status, 'DELIVERED')::follow_up_status,
  :failureReason,
  :queryId::uuid,
  COALESCE(:contentHash, encode(sha256(convert_to(:contentJson, 'UTF8')), 'hex'))
)
RETURNING
  row_id,
  follow_up_id,
  requesting_gate_id,
  dataset_request_id,
  destination_gate_id,
  destination_platform_id,
  status::text,
  content_hash,
  received_at,
  created_at;
