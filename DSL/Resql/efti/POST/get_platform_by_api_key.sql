/*
description: resolve a platform from the X-Api-Key it presents (ADR-004). Hashes the
  key and matches it against the latest non-deleted row's api_key_hash. 0 rows =
  unknown key, 1 = valid, >1 = the same key on several active platforms (config error).
params:
  apiKey: { type: string, required: true }
*/
-- C6 / ADR-009 shape: filter on the (indexed) api_key_hash first, then a
-- self-correlated NOT EXISTS "no newer row for this id" latest-check — instead of
-- materialising DISTINCT ON (id) over the whole platforms table before any filter
-- and hashing every row. digest() is evaluated once (bound param), and
-- idx_platforms_api_key_hash drives the scan; the anti-join is served by
-- idx_platforms_id_latest (id, created_at DESC).
--
-- Equivalence with the old "latest row, then check" form: requiring the matching
-- row to be BOTH non-deleted AND the newest for its id gives the same answer as
-- taking the newest row and checking it — a rotated key or a later DELETE row is a
-- newer sibling, so the stale match is excluded either way.
SELECT c.row_id, c.id, c.status::text AS status
FROM platforms c
WHERE c.api_key_hash = digest(:apiKey, 'sha256')
  AND c.status != 'DELETED'
  AND NOT EXISTS (
    SELECT 1
    FROM platforms c2
    WHERE c2.id = c.id
      AND c2.created_at > c.created_at
  );
