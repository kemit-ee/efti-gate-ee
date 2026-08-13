SELECT id
FROM users
WHERE tara_sub = :taraSub
  AND is_active = true
ORDER BY created_at DESC
LIMIT 1;
