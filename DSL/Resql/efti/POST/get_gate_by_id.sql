SELECT DISTINCT ON (id)
  row_id,
  id,
  country_code,
  e_delivery_url,
  e_delivery_cert,
  tls_cert,
  status,
  last_ping_at,
  is_active,
  created_at
FROM gates
WHERE id = :id
ORDER BY id, created_at DESC;
