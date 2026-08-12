SELECT DISTINCT ON (id)
  row_id,
  id,
  base_url,
  headers,
  e_delivery_cert,
  tls_cert,
  cert_subject,
  cert_serial,
  supports_subsetting,
  is_active AS is_platform_active,
  created_at
FROM platforms
ORDER BY id, created_at DESC
LIMIT COALESCE(:limit::int, 20) OFFSET COALESCE(:offset::int, 0);
