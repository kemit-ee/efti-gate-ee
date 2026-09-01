/*
description: insert authority
params:
  id: { type: string, required: true }
  name: { type: string, required: true }
  registryCode: { type: string, required: true }
  status: { type: string, default: "ACTIVE" }
  subsets: { type: array, items: { type: string } }
*/
-- subsets is optional and may be empty: an authority entitled to no subsets is a valid state
-- (GET /xroad/v1/subsets returns it an empty list). COALESCE maps a missing/empty value to the
-- empty text[] so it does not fall foul of the array param check.
INSERT INTO authorities (id, name, registry_code, subsets, status)
VALUES (
  :id,
  :name,
  :registryCode,
  COALESCE(:subsets, '{}')::text[],
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
