INSERT INTO authorities (id, name, registry_code, subsets, status)
VALUES (
  :id,
  :name,
  :registryCode,
  :subsets,
  COALESCE(:status, 'ACTIVE')::authority_status
)
RETURNING
  row_id,
  id,
  name,
  registry_code,
  subsets,
  status::text,
  created_at;
