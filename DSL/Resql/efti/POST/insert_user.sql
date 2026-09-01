/*
description: insert user
params:
  name:    { type: string, required: true }
  taraSub: { type: string, required: true }
*/
INSERT INTO users (id, tara_sub, name, roles)
VALUES (
  uuid_generate_v4(),
  :taraSub,
  :name,
  '{ADMIN}'
)
-- Column list matches get_user_by_id so the create handler can return this row directly.
RETURNING
  row_id,
  id,
  tara_sub,
  name,
  token_revoked_at,
  is_active   AS is_user_active,
  roles,
  'ADMIN'     = ANY(roles) AS is_admin,
  'AUTHORITY' = ANY(roles) AS is_authority,
  created_at;
