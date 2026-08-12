INSERT INTO gates (id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status)
VALUES (
  :id,
  :countryCode,
  :eDeliveryUrl,
  :eDeliveryCert,
  :tlsCert,
  COALESCE(:status, 'OFFLINE')::gate_status
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
