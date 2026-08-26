/*
description: soft delete platform
params:
  id:
    type: string
*/
INSERT INTO platforms (id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, status)
SELECT id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, 'DELETED'::gate_status
FROM (
  SELECT DISTINCT ON (id)
    id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial
  FROM platforms
  WHERE id = :id
  ORDER BY id, created_at DESC
) latest
RETURNING
  row_id,
  id,
  status::text,
  created_at;
