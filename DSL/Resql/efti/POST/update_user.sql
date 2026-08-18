INSERT INTO users (id, tara_sub, name, is_active)
VALUES (
  :userId::uuid,
  :taraSub,
  :name,
  :isActive
)
RETURNING
  row_id,
  id,
  tara_sub,
  name,
  token_revoked_at,
  is_active,
  created_at;
