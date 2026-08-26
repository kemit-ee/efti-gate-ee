/*
description: get platform by id
params:
  id:
    type: string
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
  created_at
FROM platforms
WHERE id = :id
ORDER BY id, created_at DESC;
