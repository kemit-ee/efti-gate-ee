/*
description: update platform ping
params:
  id:
    type: string
  status:
    type: string
*/
INSERT INTO platforms (id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, status)
SELECT id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, :status::gate_status
FROM (
  SELECT DISTINCT ON (id) id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial
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
  created_at;
