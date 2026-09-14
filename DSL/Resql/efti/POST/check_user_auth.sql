/*
description: Validate that a user is active and their token has not been revoked by broadcast.
  Returns the user row if the user passes all checks;
  returns 0 rows if the user is inactive, unknown, or the token predates token_revoked_at.
params:
  tara_sub:         { type: string,  required: true }   # personalCode from JWT claims
  token_issued_at:  { type: string,  required: true }   # issued_at from TIM userinfo (ISO-8601)
*/
SELECT
  u.id,
  u.tara_sub,
  u.name
FROM (
  SELECT DISTINCT ON (id)
    id, tara_sub, name, token_revoked_at, is_active, created_at, row_id
  FROM users
  WHERE id IN (SELECT id FROM users WHERE tara_sub = :tara_sub)
  ORDER BY id, created_at DESC, row_id DESC
) u
WHERE u.tara_sub = :tara_sub
  AND u.is_active = TRUE
  AND (u.token_revoked_at IS NULL
       OR :token_issued_at::timestamptz > u.token_revoked_at);
