INSERT INTO authorities (id, country_code, name, subsets, is_active)
SELECT id, country_code, name, subsets, false
FROM (
  SELECT DISTINCT ON (id)
    id, country_code, name, subsets
  FROM authorities
  WHERE id = :id
  ORDER BY id, created_at DESC
) latest
RETURNING
  row_id,
  id,
  is_active AS is_authority_active,
  created_at;
