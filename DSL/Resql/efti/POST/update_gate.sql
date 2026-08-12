INSERT INTO gates (id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status, is_active)
VALUES (
  :id,
  :countryCode,
  :eDeliveryUrl,
  :eDeliveryCert,
  :tlsCert,
  :status::gate_status,
  COALESCE(:isActive, true)
)
RETURNING
  row_id,
  id,
  country_code,
  e_delivery_url,
  e_delivery_cert,
  tls_cert,
  status,
  last_ping_at,
  is_active,
  created_at;
