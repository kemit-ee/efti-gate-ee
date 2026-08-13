INSERT INTO gates (id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status, is_active)
SELECT id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status, false
FROM (
  SELECT DISTINCT ON (id)
    id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status
  FROM gates
  WHERE id = :id
  ORDER BY id, created_at DESC
) latest
RETURNING
  row_id,
  id,
  is_active AS is_gate_active,
  created_at;
