/*
description: ADR-014 registry sync — reconcile the gates table with registry/gates/*.json.
  Declarative, idempotent and append-only. A gate whose declared content differs from its latest
  row gets a new revision, a declared gate that has no row yet is inserted, and a live gate the
  registry no longer declares gets a DELETED tombstone. An unchanged gate writes nothing, so a
  restart does not grow the table. Re-adding a removed file revives the gate (the
  protect_registry_append() reactivation guard covers users only since 20261008).
  Called by the one-shot registry-sync container at startup; the admin API no longer writes here.
  `status` is fully registry-owned — there is no ping job flipping it at runtime.
params:
  entities: { type: string, required: true }
*/
WITH desired AS (
  SELECT
    e.id,
    e."countryCode"   AS country_code,
    e."eDeliveryUrl"  AS e_delivery_url,
    e."eDeliveryCert" AS e_delivery_cert,
    e."tlsCert"       AS tls_cert,
    e.status
  FROM jsonb_to_recordset(COALESCE(:entities, '[]')::jsonb) AS e(
    id              text,
    "countryCode"   text,
    "eDeliveryUrl"  text,
    "eDeliveryCert" text,
    "tlsCert"       text,
    status          text
  )
),
latest AS (
  SELECT DISTINCT ON (id)
    id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status
  FROM gates
  ORDER BY id, created_at DESC, revision DESC
),
changed AS (
  SELECT d.id, d.country_code, d.e_delivery_url, d.e_delivery_cert, d.tls_cert, d.status
  FROM desired d
  LEFT JOIN latest l ON l.id = d.id
  WHERE l.id IS NULL
     OR l.country_code    IS DISTINCT FROM d.country_code
     OR l.e_delivery_url  IS DISTINCT FROM d.e_delivery_url
     OR l.e_delivery_cert IS DISTINCT FROM d.e_delivery_cert
     OR l.tls_cert        IS DISTINCT FROM d.tls_cert
     OR l.status::text    IS DISTINCT FROM d.status
),
upserted AS (
  INSERT INTO gates (id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status)
  SELECT id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status::gate_status
  FROM changed
  RETURNING id
),
deleted AS (
  INSERT INTO gates (id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status)
  SELECT l.id, l.country_code, l.e_delivery_url, l.e_delivery_cert, l.tls_cert, 'DELETED'::gate_status
  FROM latest l
  WHERE l.status != 'DELETED'
    AND NOT EXISTS (SELECT 1 FROM desired d WHERE d.id = l.id)
  RETURNING id
)
SELECT
  (SELECT count(*) FROM desired)  AS declared,
  (SELECT count(*) FROM upserted) AS upserted,
  (SELECT count(*) FROM deleted)  AS deleted;
