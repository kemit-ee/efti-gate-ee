/*
description: update platform ping
params:
  id: { type: string, required: true }
  status: { type: string, required: true }
*/
INSERT INTO platforms (id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, status, api_key_hash, api_key_hint, api_key_generated_at)
SELECT id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, :status::gate_status, api_key_hash, api_key_hint, api_key_generated_at
FROM (
  SELECT DISTINCT ON (id) id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, api_key_hash, api_key_hint, api_key_generated_at
  FROM platforms
  WHERE id = :id AND status != 'DELETED'
  ORDER BY id, created_at DESC
) latest
RETURNING
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
  created_at;
