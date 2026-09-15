/*
description: get user by tara sub
params:
  tara_sub: { type: string, required: true }
*/
SELECT
  row_id,
  id,
  tara_sub,
  name,
  token_revoked_at,
  is_active AS is_user_active,
  created_at
FROM (
  SELECT DISTINCT ON (id) *
  FROM users
  WHERE id IN (SELECT id FROM users WHERE tara_sub = :tara_sub)
  ORDER BY id, created_at DESC, revision DESC
) latest
WHERE tara_sub = :tara_sub;
