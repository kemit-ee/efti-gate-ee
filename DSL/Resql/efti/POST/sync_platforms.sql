/*
description: ADR-014 registry sync — reconcile the platforms table with registry/platforms/*.json.
  Same declarative, append-only contract as sync_gates and sync_authorities — new or changed platforms
  get a new revision, unchanged ones write nothing, and a live platform the registry no longer
  declares gets a DELETED tombstone. A re-added file revives it.
  `apiKeyHash` is the SHA-256 (64 lower-case hex chars) of the platform's inbound X-Api-Key; only the
  hash is ever stored. A file that omits it leaves the existing credential alone (the
  protect_registry_append() trigger carries api_key_hash forward), so deployments that manage keys
  out of band are not churned by the sync. A changed hash is treated as a rotation and stamped with
  the current time, so api_key_generated_at keeps meaning "when the current key was issued".
  Called by the one-shot registry-sync container at startup.
params:
  entities: { type: string, required: true }
*/
WITH desired AS (
  SELECT
    e.id,
    e."baseUrl"       AS base_url,
    e."headers"       AS headers,
    e."eDeliveryCert" AS e_delivery_cert,
    e."tlsCert"       AS tls_cert,
    e.status,
    lower(e."apiKeyHash") AS api_key_hash
  FROM jsonb_to_recordset(COALESCE(:entities, '[]')::jsonb) AS e(
    id              text,
    "baseUrl"       text,
    "headers"       jsonb,
    "eDeliveryCert" text,
    "tlsCert"       text,
    status          text,
    "apiKeyHash"    text
  )
),
latest AS (
  SELECT DISTINCT ON (id)
    id, base_url, headers, e_delivery_cert, tls_cert, status,
    api_key_hash, api_key_hint, api_key_generated_at
  FROM platforms
  ORDER BY id, created_at DESC, revision DESC
),
changed AS (
  SELECT d.id, d.base_url, d.headers, d.e_delivery_cert, d.tls_cert, d.status, d.api_key_hash
  FROM desired d
  LEFT JOIN latest l ON l.id = d.id
  WHERE l.id IS NULL
     OR l.base_url        IS DISTINCT FROM d.base_url
     OR l.headers         IS DISTINCT FROM COALESCE(d.headers, '{}'::jsonb)
     OR l.e_delivery_cert IS DISTINCT FROM d.e_delivery_cert
     OR l.tls_cert        IS DISTINCT FROM d.tls_cert
     OR l.status::text    IS DISTINCT FROM d.status
     OR (d.api_key_hash IS NOT NULL
         AND l.api_key_hash IS DISTINCT FROM decode(d.api_key_hash, 'hex'))
),
upserted AS (
  INSERT INTO platforms (id, base_url, headers, e_delivery_cert, tls_cert, status,
                         api_key_hash, api_key_hint, api_key_generated_at)
  SELECT
    c.id,
    c.base_url,
    COALESCE(c.headers, '{}'::jsonb),
    c.e_delivery_cert,
    c.tls_cert,
    c.status::gate_status,
    decode(c.api_key_hash, 'hex'),
    CASE WHEN c.api_key_hash IS NULL THEN NULL ELSE substr(c.api_key_hash, 1, 8) END,
    CASE
      WHEN c.api_key_hash IS NULL THEN NULL
      WHEN l.api_key_hash IS NOT DISTINCT FROM decode(c.api_key_hash, 'hex') THEN l.api_key_generated_at
      ELSE NOW()
    END
  FROM changed c
  LEFT JOIN latest l ON l.id = c.id
  RETURNING id
),
deleted AS (
  INSERT INTO platforms (id, base_url, headers, e_delivery_cert, tls_cert, status,
                         api_key_hash, api_key_hint, api_key_generated_at)
  SELECT l.id, l.base_url, l.headers, l.e_delivery_cert, l.tls_cert, 'DELETED'::gate_status,
         l.api_key_hash, l.api_key_hint, l.api_key_generated_at
  FROM latest l
  WHERE l.status != 'DELETED'
    AND NOT EXISTS (SELECT 1 FROM desired d WHERE d.id = l.id)
  RETURNING id
)
SELECT
  (SELECT count(*) FROM desired)  AS declared,
  (SELECT count(*) FROM upserted) AS upserted,
  (SELECT count(*) FROM deleted)  AS deleted;
