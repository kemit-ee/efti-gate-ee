/*
description: insert authority
params:
  id: { type: string, required: true }
  name: { type: string, required: true }
  registryCode: { type: string, required: true }
  status: { type: string, default: "ACTIVE" }
  subsets: { type: array, items: { type: string }, required: true }
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
