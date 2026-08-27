/*
description: get active authority by registry_code for X-Road access control
params:
  registry_code: { type: string, required: true }
*/
SELECT DISTINCT ON (id)
  row_id,
  id,
  name,
  registry_code,
  subsets,
  status::text,
  created_at
FROM authorities
WHERE registry_code = :registry_code
  AND status != 'DELETED'
ORDER BY id, created_at DESC
LIMIT 1;
