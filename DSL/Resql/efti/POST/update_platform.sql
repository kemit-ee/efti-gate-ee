INSERT INTO platforms (
  id,
  base_url,
  headers,
  e_delivery_cert,
  tls_cert,
  cert_subject,
  cert_serial,
  supports_subsetting,
  is_active
)
VALUES (
  :id,
  :baseUrl,
  COALESCE(:headers::jsonb, '{}'::jsonb),
  :eDeliveryCert,
  :tlsCert,
  :certSubject,
  :certSerial,
  COALESCE(:supportsSubsetting::text, 'true')::boolean,
  true
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
  supports_subsetting,
  is_active AS is_platform_active,
  created_at;
