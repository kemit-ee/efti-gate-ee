/*
description: get platforms
params:
  limit: { type: number, default: 20 }
  offset: { type: number, default: 0 }
*/
SELECT * FROM (
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
  ORDER BY id, created_at DESC
) latest
WHERE status != 'DELETED'
LIMIT COALESCE(:limit::int, 20) OFFSET COALESCE(:offset::int, 0);
