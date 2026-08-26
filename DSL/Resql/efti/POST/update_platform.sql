/*
description: update platform
params:
  baseUrl:
    type: string
  certSerial:
    type: string
  certSubject:
    type: string
  eDeliveryCert:
    type: string
  headers:
    type: string
  id:
    type: string
  status:
    type: string
  tlsCert:
    type: string
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
SELECT
  :id,
  COALESCE(:baseUrl, latest.base_url),
  COALESCE(:headers::jsonb, latest.headers),
  COALESCE(:eDeliveryCert, latest.e_delivery_cert),
  COALESCE(:tlsCert, latest.tls_cert),
  COALESCE(:certSubject, latest.cert_subject),
  COALESCE(:certSerial, latest.cert_serial),
  COALESCE(:status::gate_status, latest.status)
FROM (
  SELECT DISTINCT ON (id)
    base_url, headers, e_delivery_cert, tls_cert,
    cert_subject, cert_serial, status
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
  created_at;
