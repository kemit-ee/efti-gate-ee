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
WHERE
  (:gateId IS NULL OR roles->'ADMIN' ? :gateId)
  AND (:authorityId IS NULL OR roles->'AUTHORITY' ? :authorityId)
ORDER BY id, created_at DESC
LIMIT COALESCE(:limit::int, 20) OFFSET COALESCE(:offset::int, 0);
