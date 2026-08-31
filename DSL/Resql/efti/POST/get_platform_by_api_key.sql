/*
description: resolve a platform from the X-Api-Key it presents. Matches the key against
  the latest non-deleted row's headers->>'X-Api-Key'. 0 rows = unknown key, 1 = valid,
  >1 = the same key on several active platforms (registry misconfiguration).
params:
  apiKey: { type: string, required: true }
*/
SELECT latest.row_id, latest.id, latest.status
FROM (
  SELECT DISTINCT ON (id)
    row_id, id, headers, status::text AS status
  FROM platforms
  ORDER BY id, created_at DESC
) latest
WHERE latest.status != 'DELETED'
  AND latest.headers ->> 'X-Api-Key' = :apiKey;
