/*
description: get users
params:
  limit: { type: number, default: 20 }
  offset: { type: number, default: 0 }
*/
SELECT * FROM (
  SELECT DISTINCT ON (id)
    row_id,
    id,
    tara_sub,
    name,
    token_revoked_at,
    is_active AS is_user_active,
    created_at
  FROM users
  ORDER BY id, created_at DESC, row_id DESC
) latest
WHERE is_user_active = true
ORDER BY id
LIMIT LEAST(GREATEST(COALESCE(:limit, 20), 0), 1000) OFFSET GREATEST(COALESCE(:offset, 0), 0);
