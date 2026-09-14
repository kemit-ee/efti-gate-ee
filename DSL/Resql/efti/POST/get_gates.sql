/*
description: get gates
params:
  status: { type: string }
  limit: { type: number, default: 20 }
  offset: { type: number, default: 0 }
*/
SELECT * FROM (
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
  ORDER BY id, created_at DESC, row_id DESC
) latest
WHERE :status IS NULL AND status != 'DELETED' OR status = :status
ORDER BY id
LIMIT LEAST(GREATEST(COALESCE(:limit, 20), 0), 1000) OFFSET GREATEST(COALESCE(:offset, 0), 0);
