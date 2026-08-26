/*
description: update authority
params:
  id:
    type: string
  name:
    type: string
  registryCode:
    type: string
  status:
    type: string
  subsets:
    type: array
    items:
      type: string
*/
INSERT INTO authorities (id, name, registry_code, subsets, status)
VALUES (
  :id,
  :name,
  :registryCode,
  :subsets::text[],
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
