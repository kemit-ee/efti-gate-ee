/*
description: "update user (append-only: inserts a new row with the same logical id)"
params:
  id:      { type: string, required: true }
  name:    { type: string, required: true }
  taraSub: { type: string, required: true }
*/
INSERT INTO users (id, tara_sub, name, roles)
VALUES (
  :id::uuid,
  :taraSub,
  :name,
  '{ADMIN}'
)
RETURNING
  row_id,
  id,
  tara_sub,
  name,
  roles,
  'ADMIN'     = ANY(roles) AS is_admin,
  'AUTHORITY' = ANY(roles) AS is_authority,
  token_revoked_at,
  is_active,
  created_at;
