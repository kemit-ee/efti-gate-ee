INSERT INTO authorities (id, name, registry_code, subsets)
VALUES (
  :id,
  :name,
  :registryCode,
  :subsets
)
RETURNING
  row_id,
  id,
  name,
  registry_code,
  subsets,
  is_active AS is_authority_active,
  created_at;
