/*
description: resolve a platform from its X-Api-Key credential (m2m platform guard).
  Hashes the presented key and matches it against the latest non-deleted row per
  platform id. Returns 0 rows if the key is unknown, exactly 1 for a valid key,
  or >1 only if the same key is registered to several active platforms (config error).
params:
  apiKey: { type: string, required: true }
*/
SELECT
  latest.row_id,
  latest.id,
  latest.status
FROM (
  SELECT DISTINCT ON (id)
    row_id, id, api_key_hash, status::text AS status
  FROM platforms
  ORDER BY id, created_at DESC
) latest
WHERE latest.status != 'DELETED'
  AND latest.api_key_hash = digest(:apiKey, 'sha256');
