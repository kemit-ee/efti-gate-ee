/*
description: get platform by id
params:
  id: { type: string, required: true }
*/
SELECT DISTINCT ON (id)
  row_id,
  id,
  base_url,
  headers,
  e_delivery_cert,
  tls_cert,
  cert_subject,
  cert_serial,
  status::text,
  api_key_hint,
  api_key_generated_at,
  (api_key_hash IS NOT NULL) AS has_api_key,
  created_at
FROM platforms
WHERE id = :id
ORDER BY id, created_at DESC;
