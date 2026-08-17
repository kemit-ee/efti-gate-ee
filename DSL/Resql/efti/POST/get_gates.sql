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
    is_active AS is_gate_active,
    created_at
  FROM gates
  ORDER BY id, created_at DESC
) latest
WHERE status != 'DELETED'
LIMIT COALESCE(:limit::int, 20) OFFSET COALESCE(:offset::int, 0);
