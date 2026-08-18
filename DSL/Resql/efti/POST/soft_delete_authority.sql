INSERT INTO authorities (id, name, registry_code, subsets, is_active)
SELECT id, name, registry_code, subsets, false
FROM (
  SELECT DISTINCT ON (id)
    id, name, registry_code, subsets
  FROM authorities
  WHERE id = :id
  ORDER BY id, created_at DESC
) latest
RETURNING
  row_id,
  id,
  is_active AS is_authority_active,
  created_at;
