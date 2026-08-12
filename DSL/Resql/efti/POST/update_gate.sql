INSERT INTO gates (id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status, is_active)
VALUES (
  :id,
  :countryCode,
  :eDeliveryUrl,
  :eDeliveryCert,
  :tlsCert,
  COALESCE(:status, 'OFFLINE')::gate_status,
  true
)
RETURNING
  row_id,
  id,
  country_code,
  e_delivery_url,
  e_delivery_cert,
  tls_cert,
  status::text,
  last_ping_at,
  is_active,
  created_at;
