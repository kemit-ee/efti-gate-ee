/*
description: soft delete authority
params:
  id: { type: string, required: true }
*/
INSERT INTO authorities (id, name, registry_code, subsets, status)
SELECT id, name, registry_code, subsets, 'DELETED'::authority_status
FROM (
  SELECT DISTINCT ON (id)
    id, name, registry_code, subsets
  FROM authorities
  WHERE id = :id
  ORDER BY id, created_at DESC
) latest
RETURNING
  row_id,
  id,
  status::text,
  created_at;
