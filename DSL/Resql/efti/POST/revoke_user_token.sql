/*
description: revoke user token
params:
  userId: { type: string, required: true }
*/
INSERT INTO users (id, tara_sub, name, secret_hash, token_revoked_at, is_active)
SELECT id, tara_sub, name, secret_hash, GREATEST(token_revoked_at, NOW()), is_active
FROM (
  SELECT DISTINCT ON (id)
    id, tara_sub, name, secret_hash, token_revoked_at, is_active
  FROM users
  WHERE id = :userId::uuid
  ORDER BY id, created_at DESC, revision DESC
) latest
RETURNING
  row_id,
  id,
  token_revoked_at,
  is_active AS is_user_active,
  created_at;
