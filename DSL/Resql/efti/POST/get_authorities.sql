/*
description: get authorities
params:
  limit: { type: number, default: 20 }
  offset: { type: number, default: 0 }
*/
SELECT * FROM (
  SELECT DISTINCT ON (id)
    row_id,
    id,
    name,
    registry_code,
    subsets,
    status::text,
    created_at
  FROM authorities
  ORDER BY id, created_at DESC
) latest
WHERE status != 'DELETED'
LIMIT COALESCE(:limit, 20) OFFSET COALESCE(:offset, 0);
