SELECT DISTINCT ON (id)
  row_id,
  id,
  tara_sub,
  email,
  name,
  is_admin AS is_user_admin,
  roles,
  subsets,
  token_revoked_at,
  is_active AS is_user_active,
  created_at
FROM users
WHERE id = :userId::uuid
ORDER BY id, created_at DESC;
