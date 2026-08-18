SELECT DISTINCT ON (id)
  row_id,
  id,
  name,
  registry_code,
  subsets,
  status::text,
  created_at
FROM authorities
WHERE id = :id
ORDER BY id, created_at DESC;
