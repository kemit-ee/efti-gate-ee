/*
description: insert user
params:
  name:
    type: string
  taraSub:
    type: string
*/
INSERT INTO users (id, tara_sub, name)
VALUES (
  uuid_generate_v4(),
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
