/*
description: insert gate
params:
  countryCode: { type: string, required: true }
  eDeliveryCert: { type: string }
  eDeliveryUrl: { type: string, required: true }
  id: { type: string, required: true }
  status: { type: string, default: "OFFLINE" }
  tlsCert: { type: string }
*/
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
  created_at;
