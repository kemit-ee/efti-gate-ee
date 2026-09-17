/*
description: insert audit log entry (best-effort, post-commit — see docs/specs/logging-spec.md §5.2)
params:
  action: { type: string, required: true }
  details: { type: string }
  ipAddress: { type: string }
  resource: { type: string, required: true }
  resourceId: { type: string }
  userId: { type: string }
*/
INSERT INTO audit_log (
  user_id,
  action,
  resource,
  resource_id,
  ip_address,
  details
) VALUES (
  :userId::uuid,
  :action,
  :resource,
  :resourceId,
  :ipAddress::inet,
  :details::jsonb
)
RETURNING
  row_id,
  user_id,
  action,
  resource,
  resource_id,
  ip_address,
  details,
  recorded_at;
