/*
description: get audit log
params:
  from: { type: string }
  limit: { type: number, default: 20 }
  offset: { type: number, default: 0 }
  resource: { type: string }
  resourceId: { type: string }
  to: { type: string }
  userId: { type: string }
*/
SELECT
  row_id,
  user_id,
  action,
  resource,
  resource_id,
  ip_address,
  details,
  recorded_at
FROM audit_log
WHERE (:resource::text IS NULL OR resource = :resource)
  AND (:resourceId::text IS NULL OR resource_id = :resourceId)
  AND (:userId::text IS NULL OR user_id = :userId::uuid)
  AND (:from::text IS NULL OR recorded_at >= :from::timestamptz)
  AND (:to::text IS NULL OR recorded_at <= :to::timestamptz)
ORDER BY recorded_at DESC
LIMIT COALESCE(:limit::int, 20) OFFSET COALESCE(:offset::int, 0);
