INSERT INTO authorities (id, name, registry_code, description, subsets)
VALUES (
  :id,
  :name,
  :registryCode,
  :description,
  :subsets
)
RETURNING
  row_id,
  id,
  name,
  registry_code,
  description,
  subsets,
  is_active AS is_authority_active,
  created_at;
