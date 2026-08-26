/*
description: update user
params:
  id: { type: string, required: true }
  name: { type: string, required: true }
  taraSub: { type: string, required: true }
*/
INSERT INTO users (id, tara_sub, name)
VALUES (
  :id::uuid,
  :taraSub,
  :name
)
RETURNING
  row_id,
  id,
  tara_sub,
  name,
  token_revoked_at,
  is_active,
  created_at;
