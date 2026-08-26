/*
description: check tara sub exists
params:
  taraSub:
    type: string
*/
SELECT id
FROM users
WHERE tara_sub = :taraSub
  AND is_active = true
ORDER BY created_at DESC
LIMIT 1;
