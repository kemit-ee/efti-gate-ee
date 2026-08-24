INSERT INTO users (id, tara_sub, email, name)
VALUES (
  uuid_generate_v4(),
  :taraSub,
  :email,
  :name
)
RETURNING
  row_id,
  id,
  tara_sub,
  email,
  name,
  is_admin,
  token_revoked_at,
  is_active,
  created_at;
