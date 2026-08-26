/*
description: soft delete gate
params:
  id:
    type: string
*/
INSERT INTO gates (id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status)
SELECT id, country_code, e_delivery_url, e_delivery_cert, tls_cert, 'DELETED'::gate_status
FROM (
  SELECT DISTINCT ON (id)
    id, country_code, e_delivery_url, e_delivery_cert, tls_cert
  FROM gates
  WHERE id = :id
  ORDER BY id, created_at DESC
) latest
RETURNING
  row_id,
  id,
  status::text,
  created_at;
