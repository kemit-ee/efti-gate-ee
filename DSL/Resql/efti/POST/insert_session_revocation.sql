/*
description: insert session revocation
params:
  jwt_payload: { type: string, required: true }
  reason: { type: string, required: true }
  user_id: { type: string, required: true }
*/
-- Append-only INSERT into the JWT denylist (docs/specs/db/schema.sql §4.2).
-- Duplicates on jti are deliberately tolerated: a second logout on the same token is a
-- no-op insert, not a conflict, because the read pattern is
-- EXISTS (… WHERE jti = $1 AND expires_at > NOW()).
--
-- jti and exp are decoded from the token's payload here rather than read from TIM: TIM's
-- /jwt/userinfo exposes neither claim (it returns loggedInDate / loginExpireDate and no
-- token id), and putting anything other than the real `jti` claim into a column documented
-- as "JWT ID claim (RFC 7519 §4.1.7)" would break the contract.
--
-- :jwt_payload is the token's MIDDLE segment only — the caller strips the header and
-- signature, so the database never receives a replayable credential. The segment is
-- base64url and unpadded, hence translate() for the URL-safe alphabet and repeat() to
-- restore '=' padding. Each parameter is referenced exactly once.
--
-- A malformed segment raises (invalid base64 / not UTF-8 / not JSON), which surfaces as a
-- 500 rather than a silent success. That is the intended failure mode and it cannot block
-- a logout: the caller blacklists the token in TIM *before* recording it here.
WITH seg AS (
  SELECT CAST(:jwt_payload AS TEXT) AS s
),
padded AS (
  SELECT translate(s, '-_', '+/') || repeat('=', (4 - length(s) % 4) % 4) AS b64
    FROM seg
),
payload AS (
  SELECT convert_from(decode(b64, 'base64'), 'UTF8')::jsonb AS claims
    FROM padded
)
INSERT INTO sessions (user_id, jti, expires_at, reason)
SELECT CAST(:user_id AS UUID),
       claims ->> 'jti',
       -- `exp` is a NumericDate: seconds since the epoch (RFC 7519 §2).
       to_timestamp((claims ->> 'exp')::DOUBLE PRECISION),
       CAST(:reason AS TEXT)
  FROM payload
 WHERE claims ->> 'jti' IS NOT NULL
   AND claims ->> 'exp' IS NOT NULL
RETURNING row_id, jti, expires_at;
