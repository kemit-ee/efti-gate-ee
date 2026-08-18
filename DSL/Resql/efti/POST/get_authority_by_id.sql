SELECT DISTINCT ON (id)
  row_id,
  id,
  name,
  registry_code,
  description,
  subsets,
  is_active AS is_authority_active,
  created_at
FROM authorities
WHERE id = :id
ORDER BY id, created_at DESC;
