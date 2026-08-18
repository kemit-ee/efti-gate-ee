SELECT DISTINCT ON (id)
  row_id,
  id,
  name,
  registry_code,
  subsets,
  is_active AS is_authority_active,
  created_at
FROM authorities
ORDER BY id, created_at DESC
LIMIT COALESCE(:limit::int, 20) OFFSET COALESCE(:offset::int, 0);
