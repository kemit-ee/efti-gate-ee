INSERT INTO authorities (id, name, registry_code, description, subsets, is_active)
VALUES (
  :id,
  :name,
  :registryCode,
  :description,
  :subsets,
  true
)
RETURNING
  row_id,
  id,
  name,
  registry_code,
  description,
  subsets,
  is_active,
  created_at;
