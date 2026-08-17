INSERT INTO users (id, tara_sub, email, name, is_admin, roles, subsets, token_revoked_at, is_active)
SELECT id, tara_sub, email, name, is_admin, roles, subsets, NOW(), is_active
FROM (
  SELECT DISTINCT ON (id)
    id, tara_sub, email, name, is_admin, roles, subsets, is_active
  FROM users
  WHERE id = :userId::uuid
  ORDER BY id, created_at DESC
) latest
RETURNING
  row_id,
  id,
  token_revoked_at,
  is_active AS is_user_active,
  created_at;
