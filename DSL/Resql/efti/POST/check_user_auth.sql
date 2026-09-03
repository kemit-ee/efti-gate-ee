/*
description: Validate that a user is active and their token has not been revoked by broadcast.
  Returns the user row (with the is_admin flag) if the user passes all checks;
  returns 0 rows if the user is inactive, unknown, or the token predates token_revoked_at.
params:
  tara_sub:         { type: string,  required: true }   # personalCode from JWT claims
  token_issued_at:  { type: string,  required: true }   # issued_at from TIM userinfo (ISO-8601)
*/
SELECT
  u.id,
  u.tara_sub,
  u.name,
  u.is_admin
FROM (
  SELECT DISTINCT ON (id)
    id, tara_sub, name, is_admin, token_revoked_at, created_at
  FROM users
  WHERE tara_sub     = :tara_sub
    AND is_active    = TRUE
  ORDER BY id, created_at DESC
) u
WHERE u.token_revoked_at IS NULL
   OR :token_issued_at::timestamptz > u.token_revoked_at;
