/*
description: insert platform
params:
  id: { type: string, required: true }
  baseUrl: { type: string, required: true }
  eDeliveryCert: { type: string }
  headers: { type: object, default: {} }
  status: { type: string, default: "ONLINE" }
  tlsCert: { type: string }
*/
INSERT INTO platforms (
  id,
  base_url,
  headers,
  e_delivery_cert,
  tls_cert,
  status
)
VALUES (
  :id,
  :baseUrl,
  COALESCE(:headers::jsonb, '{}'::jsonb),
  :eDeliveryCert,
  :tlsCert,
  COALESCE(:status, 'ONLINE')::gate_status
)
RETURNING
  row_id,
  id,
  base_url,
  headers,
  e_delivery_cert,
  tls_cert,
  status::text,
  api_key_hint,
  api_key_generated_at,
  (api_key_hash IS NOT NULL) AS has_api_key,
  created_at;
