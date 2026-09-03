/*
description: get user by tara sub
params:
  tara_sub: { type: string, required: true }
*/
SELECT DISTINCT ON (id)
  row_id,
  id,
  tara_sub,
  name,
  token_revoked_at,
  is_active AS is_user_active,
  is_admin,
  created_at
FROM users
WHERE tara_sub = :tara_sub
ORDER BY id, created_at DESC;