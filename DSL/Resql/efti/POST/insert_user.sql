INSERT INTO users (id, tara_sub, email, name, is_admin, roles, subsets)
VALUES (
  uuid_generate_v4(),
  :taraSub,
  :email,
  :name,
  COALESCE(:isAdmin::text, 'false')::boolean,
  COALESCE(:roles::jsonb, '{}'::jsonb),
  COALESCE(ARRAY(SELECT jsonb_array_elements_text(:subsets::jsonb)), ARRAY[]::text[])
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
