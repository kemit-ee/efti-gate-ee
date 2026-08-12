INSERT INTO platforms (id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, supports_subsetting, is_active)
SELECT id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, supports_subsetting, false
FROM (
  SELECT DISTINCT ON (id)
    id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, supports_subsetting
  FROM platforms
  WHERE id = :id
  ORDER BY id, created_at DESC
) latest
RETURNING
  row_id,
  id,
  is_active AS is_platform_active,
  created_at;
