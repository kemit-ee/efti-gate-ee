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
  ORDER BY id, created_at DESC
) latest
WHERE is_user_active = true
LIMIT COALESCE(:limit, 20) OFFSET COALESCE(:offset, 0);
