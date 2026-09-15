/*
description: update gate ping
params:
  id: { type: string, required: true }
  status: { type: string, required: true }
*/
INSERT INTO gates (id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status, last_ping_at)
SELECT id, country_code, e_delivery_url, e_delivery_cert, tls_cert, :status::gate_status, NOW()
FROM (
  SELECT DISTINCT ON (id) id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status
  FROM gates
  WHERE id = :id
  ORDER BY id, created_at DESC, revision DESC
) latest
WHERE latest.status IN ('ONLINE', 'OFFLINE')
RETURNING
  row_id,
  id,
  country_code,
  e_delivery_url,
  e_delivery_cert,
  tls_cert,
  status::text,
  last_ping_at,
  created_at;
