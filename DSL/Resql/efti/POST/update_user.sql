/*
description: "update user (append-only: inserts a new row with the same logical id)"
params:
  id:          { type: string,  required: true }
  name:        { type: string,  required: true }
  taraSub:     { type: string,  required: true }
*/
INSERT INTO users (id, tara_sub, name, secret_hash, token_revoked_at, is_active)
SELECT id, :taraSub, :name, secret_hash,
  CASE WHEN tara_sub IS DISTINCT FROM :taraSub THEN GREATEST(token_revoked_at, clock_timestamp()) ELSE token_revoked_at END,
  is_active
FROM (
  SELECT DISTINCT ON (id) id, tara_sub, secret_hash, token_revoked_at, is_active, created_at, row_id
  FROM users
  WHERE id = :id::uuid
  ORDER BY id, created_at DESC, row_id DESC
) latest
-- Column list matches get_user_by_id so the update handler can return this row directly.
RETURNING
  row_id,
  id,
  tara_sub,
  name,
  token_revoked_at,
  is_active   AS is_user_active,
  created_at;
