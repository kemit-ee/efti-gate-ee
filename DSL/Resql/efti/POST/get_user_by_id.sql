/*
description: get user by id
params:
  userId: { type: string, required: true }
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
WHERE id = :userId::uuid
ORDER BY id, created_at DESC;
