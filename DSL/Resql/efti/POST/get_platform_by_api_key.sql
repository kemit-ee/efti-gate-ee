/*
description: resolve a platform from the X-Api-Key it presents (ADR-004). Hashes the
  key and matches it against the latest non-deleted row's api_key_hash. 0 rows =
  unknown key, 1 = valid, >1 = the same key on several active platforms (config error).
params:
  apiKey: { type: string, required: true }
*/
SELECT latest.row_id, latest.id, latest.status
FROM (
  SELECT DISTINCT ON (id)
    row_id, id, api_key_hash, status::text AS status
  FROM platforms
  ORDER BY id, created_at DESC
) latest
WHERE latest.status != 'DELETED'
  AND latest.api_key_hash = digest(:apiKey, 'sha256');
