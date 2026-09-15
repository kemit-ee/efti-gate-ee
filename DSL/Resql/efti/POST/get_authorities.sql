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
  ORDER BY id, created_at DESC, revision DESC
) latest
WHERE status != 'DELETED'
ORDER BY id
LIMIT LEAST(GREATEST(COALESCE(:limit, 20), 0), 1000) OFFSET GREATEST(COALESCE(:offset, 0), 0);
