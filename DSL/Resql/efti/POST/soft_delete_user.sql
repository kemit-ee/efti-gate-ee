/*
description: soft delete user
params:
  userId:
    type: string
*/
INSERT INTO users (id, tara_sub, name, secret_hash, token_revoked_at, is_active)
SELECT id, tara_sub, name, secret_hash, token_revoked_at, false
FROM (
  SELECT DISTINCT ON (id)
    id, tara_sub, name, secret_hash, token_revoked_at
  FROM users
  WHERE id = :userId::uuid
  ORDER BY id, created_at DESC
) latest
RETURNING
  row_id,
  id,
  is_active AS is_user_active,
  created_at;
