/*
description: insert platform
params:
  id: { type: string, required: true }
  baseUrl: { type: string, required: true }
  certSerial: { type: string }
  certSubject: { type: string }
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
  cert_subject,
  cert_serial,
  status
)
VALUES (
  :id,
  :baseUrl,
  COALESCE(:headers::jsonb, '{}'::jsonb),
  :eDeliveryCert,
  :tlsCert,
  :certSubject,
  :certSerial,
  COALESCE(:status, 'ONLINE')::gate_status
)
RETURNING
  row_id,
  id,
  base_url,
  headers,
  e_delivery_cert,
  tls_cert,
  cert_subject,
  cert_serial,
  status::text,
  created_at;
