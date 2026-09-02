/*
description: soft delete platform
params:
  id: { type: string, required: true }
*/
INSERT INTO platforms (id, base_url, headers, e_delivery_cert, tls_cert, status, api_key_hash, api_key_hint, api_key_generated_at)
SELECT id, base_url, headers, e_delivery_cert, tls_cert, 'DELETED'::gate_status, api_key_hash, api_key_hint, api_key_generated_at
FROM (
  SELECT DISTINCT ON (id)
    id, base_url, headers, e_delivery_cert, tls_cert, api_key_hash, api_key_hint, api_key_generated_at
  FROM platforms
  WHERE id = :id
  ORDER BY id, created_at DESC
) latest
RETURNING
  row_id,
  id,
  status::text,
  created_at;
