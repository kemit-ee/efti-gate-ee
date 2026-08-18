INSERT INTO authorities (id, name, registry_code, subsets, is_active)
VALUES (
  :id,
  :name,
  :registryCode,
  :subsets,
  true
)
RETURNING
  row_id,
  id,
  name,
  registry_code,
  subsets,
  is_active,
  created_at;
