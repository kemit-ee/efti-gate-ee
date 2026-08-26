/*
description: get gate by id
params:
  id: { type: string, required: true }
*/
SELECT DISTINCT ON (id)
  row_id,
  id,
  country_code,
  e_delivery_url,
  e_delivery_cert,
  tls_cert,
  status::text,
  last_ping_at,
  created_at
FROM gates
WHERE id = :id
ORDER BY id, created_at DESC;
