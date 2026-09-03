/*
description: insert user
params:
  name:        { type: string,  required: true }
  taraSub:     { type: string,  required: true }
  isAdmin:     { type: boolean, required: false }
*/
INSERT INTO users (id, tara_sub, name, is_admin)
VALUES (
  uuid_generate_v4(),
  :taraSub,
  :name,
  COALESCE(:isAdmin::boolean, FALSE)
)
-- Column list matches get_user_by_id so the create handler can return this row directly.
RETURNING
  row_id,
  id,
  tara_sub,
  name,
  token_revoked_at,
  is_active   AS is_user_active,
  is_admin,
  created_at;
