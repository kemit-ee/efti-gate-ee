INSERT INTO follow_up_log (
  follow_up_id,
  requesting_gate_id,
  requesting_user_id,
  dataset_request_id,
  destination_gate_id,
  destination_platform_id,
  payload,
  status
) VALUES (
  COALESCE(:followUpId::uuid, uuid_generate_v4()),
  :requestingGateId,
  :requestingUserId::uuid,
  :datasetRequestId::uuid,
  :destinationGateId,
  :destinationPlatformId,
  :payload,
  COALESCE(:status, 'DELIVERED')::follow_up_status
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
