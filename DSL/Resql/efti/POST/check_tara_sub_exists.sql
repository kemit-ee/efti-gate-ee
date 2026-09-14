/*
description: check tara sub exists
params:
  taraSub: { type: string, required: true }
*/
SELECT id
FROM (
  SELECT DISTINCT ON (id) id, tara_sub, is_active, created_at, row_id
  FROM users
  WHERE id IN (SELECT id FROM users WHERE tara_sub = :taraSub)
  ORDER BY id, created_at DESC, row_id DESC
) latest
WHERE tara_sub = :taraSub
  AND is_active = true
ORDER BY created_at DESC, row_id DESC
LIMIT 1;
