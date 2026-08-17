INSERT INTO users (id, tara_sub, email, name, is_admin, roles, subsets, is_active)
VALUES (
  :userId::uuid,
  :taraSub,
  :email,
  :name,
  COALESCE(:isAdmin::text, 'false')::boolean,
  COALESCE(:roles::jsonb, '{}'::jsonb),
  COALESCE(:subsets, ARRAY[]::text[]),
  true
)
RETURNING
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
  created_at;
