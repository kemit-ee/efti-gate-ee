/*
description: insert authority
params:
  id: { type: string, required: true }
  name: { type: string, required: true }
  registryCode: { type: string, required: true }
  status: { type: string, default: "ACTIVE" }
  subsets: { type: string, default: "[]" }
*/
-- subsets arrives as a JSON array string ('["EU01","EU05"]', or '[]' for an authority entitled
-- to nothing) rather than a native array param: ReSql cannot infer an element type for an empty
-- array and falls back to binding it as JSONB, which then fails the ::text[] cast. Unpacking the
-- JSON here keeps the empty case working. array_agg over an empty set is NULL, hence the COALESCE.
INSERT INTO authorities (id, name, registry_code, subsets, status)
VALUES (
  :id,
  :name,
  :registryCode,
  COALESCE(
    (SELECT array_agg(elem) FROM jsonb_array_elements_text(COALESCE(:subsets, '[]')::jsonb) AS elem),
    ARRAY[]::text[]
  ),
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
