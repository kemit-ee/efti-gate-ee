INSERT INTO authorities (id, name, registry_code, description, subsets, is_active)
SELECT id, name, registry_code, description, subsets, false
FROM (
  SELECT DISTINCT ON (id)
    id, name, registry_code, description, subsets
  FROM authorities
  WHERE id = :id
  ORDER BY id, created_at DESC
) latest
RETURNING
  row_id,
  id,
  is_active AS is_authority_active,
  created_at;
