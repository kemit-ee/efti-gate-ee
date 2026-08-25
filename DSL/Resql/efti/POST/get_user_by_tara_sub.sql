-- Resolve a JWT to its users row: the single DB lookup on the authentication path
-- (docs/specs/permissions-matrix.md:247). Returns 0 or 1 rows; 0 rows means "reject",
-- and the caller turns that into a denial. The gate never auto-provisions.
--
-- Rejects on three grounds, all of which must be evaluated against the user's CURRENT
-- state (the latest row per logical id), never against the row that happens to match the
-- inbound value:
--   1. no active user carries this tara_sub any more
--   2. the latest row is soft-deleted (is_active = FALSE)
--   3. the token predates a per-user broadcast revocation (users.token_revoked_at)
--
-- Each named parameter is referenced exactly once, via the `q` CTE, so ReSQL binds a
-- single placeholder per parameter.
WITH q AS (
  SELECT CAST(:tara_sub AS TEXT) AS tara_sub,
         -- Token issuance time. Fail closed: callers pass 0 when it cannot be
         -- determined, which makes any non-null token_revoked_at win.
         -- DOUBLE PRECISION, not BIGINT: Ruuter renders the epoch-millis number in
         -- E-notation ("1.786615581E12"), which BIGINT will not parse.
         to_timestamp(CAST(:issued_at_ms AS DOUBLE PRECISION) / 1000.0) AS issued_at
),
-- Which logical users have EVER carried this tara_sub? Index-backed by
-- idx_users_tara_sub, and it bounds the latest-row resolution below to a handful of ids.
candidate AS (
  SELECT DISTINCT u.id
    FROM users u, q
   WHERE u.tara_sub = q.tara_sub
),
-- Current state of each candidate. The tara_sub filter MUST NOT appear here: filtering
-- inside DISTINCT ON resolves "the newest row still carrying the old value" instead of
-- "the newest row", so a superseded identifier would keep authenticating after an admin
-- corrected it. This is the latest-row rule in CLAUDE.md — resolve first, filter after.
latest AS (
  SELECT DISTINCT ON (u.id) u.*
    FROM users u
   WHERE u.id IN (SELECT id FROM candidate)
   ORDER BY u.id, u.created_at DESC, u.row_id ASC
)
SELECT l.id,
       l.tara_sub,
       l.name,
       l.token_revoked_at,
       l.is_active
  FROM latest l, q
 WHERE l.is_active = TRUE
   AND l.tara_sub = q.tara_sub
   AND (l.token_revoked_at IS NULL OR l.token_revoked_at <= q.issued_at)
 LIMIT 1;
