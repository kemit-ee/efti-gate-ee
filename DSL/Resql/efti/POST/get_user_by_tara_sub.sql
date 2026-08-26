/*
description: get user by tara sub
params:
  tara_sub:
    type: string
*/
SELECT DISTINCT ON (id)
  row_id,
  id,
  tara_sub,
  name,
  token_revoked_at,
  is_active AS is_user_active,
  created_at
FROM users
WHERE tara_sub = :tara_sub
ORDER BY id, created_at DESC;