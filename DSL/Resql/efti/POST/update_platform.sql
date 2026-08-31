/*
description: update platform
params:
  baseUrl: { type: string, required: true }
  certSerial: { type: string }
  certSubject: { type: string }
  eDeliveryCert: { type: string }
  headers: { type: object, default: {} }
  id: { type: string, required: true }
  status: { type: string }
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
  status,
  api_key_hash,
  api_key_hint,
  api_key_generated_at
)
SELECT
  :id,
  COALESCE(:baseUrl, latest.base_url),
  COALESCE(:headers::jsonb, latest.headers),
  COALESCE(:eDeliveryCert, latest.e_delivery_cert),
  COALESCE(:tlsCert, latest.tls_cert),
  COALESCE(:certSubject, latest.cert_subject),
  COALESCE(:certSerial, latest.cert_serial),
  COALESCE(:status::gate_status, latest.status),
  latest.api_key_hash,
  latest.api_key_hint,
  latest.api_key_generated_at
FROM (
  SELECT DISTINCT ON (id)
    base_url, headers, e_delivery_cert, tls_cert,
    cert_subject, cert_serial, status,
    api_key_hash, api_key_hint, api_key_generated_at
  FROM platforms
  WHERE id = :id
  ORDER BY id, created_at DESC
) latest
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
  api_key_hint,
  api_key_generated_at,
  created_at;
