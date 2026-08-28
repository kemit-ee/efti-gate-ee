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
  roles,
  'ADMIN'     = ANY(roles) AS is_admin,
  'AUTHORITY' = ANY(roles) AS is_authority,
  created_at
FROM users
WHERE id = :userId::uuid
ORDER BY id, created_at DESC;
