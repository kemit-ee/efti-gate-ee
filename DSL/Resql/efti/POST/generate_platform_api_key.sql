/*
description: generate a new X-Api-Key for a platform (ADR-004). Appends a platforms
  row carrying every field forward from the latest non-deleted row and replacing the
  api_key_* columns. Returns the plaintext key EXACTLY ONCE — it is stored only as a
  SHA-256 hash and cannot be retrieved again.
params:
  id: { type: string, required: true }
*/
WITH newkey AS (
  SELECT encode(gen_random_bytes(24), 'hex') AS k
),
latest AS (
  SELECT DISTINCT ON (id)
    id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, status
  FROM platforms
  WHERE id = :id AND status != 'DELETED'
  ORDER BY id, created_at DESC
),
ins AS (
  INSERT INTO platforms (
    id, base_url, headers, e_delivery_cert, tls_cert, cert_subject, cert_serial, status,
    api_key_hash, api_key_hint, api_key_generated_at
  )
  SELECT
    l.id, l.base_url, l.headers, l.e_delivery_cert, l.tls_cert, l.cert_subject, l.cert_serial, l.status,
    digest(n.k, 'sha256'),
    substr(encode(digest(n.k, 'sha256'), 'hex'), 1, 8),
    NOW()
  FROM latest l CROSS JOIN newkey n
  RETURNING id, api_key_hint, api_key_generated_at
)
SELECT ins.id, ins.api_key_hint, ins.api_key_generated_at, newkey.k AS api_key
FROM ins CROSS JOIN newkey;
