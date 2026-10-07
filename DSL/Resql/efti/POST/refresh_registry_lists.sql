/*
description: ADR-012 read-model refresh for the admin registry lists - writes one new generation holding the
  latest row of every gate, platform and authority id (including DELETED, so status filters keep working) and
  publishes it with one pointer row per model. One statement, so readers see the previous generation or the
  complete new one. An advisory transaction lock serialises overlapping refreshes. Called by the admin write
  routes right after a registry write, and by POST /ops/v1/refresh-read-models as the safety net.
params: {}
*/
WITH serialised AS (
  SELECT pg_advisory_xact_lock(hashtextextended('read_model:registries', 0))
),
new_generation AS (
  SELECT nextval('read_model_generation_seq') AS generation FROM serialised
),
snapshot_gates AS (
  INSERT INTO rm_gates (generation, id, row_id, country_code, e_delivery_url, e_delivery_cert, tls_cert, status, last_ping_at, created_at)
  SELECT g.generation, latest.id, latest.row_id, latest.country_code, latest.e_delivery_url, latest.e_delivery_cert,
         latest.tls_cert, latest.status::text, latest.last_ping_at, latest.created_at
  FROM new_generation g, (SELECT DISTINCT id FROM gates) ids
  CROSS JOIN LATERAL (
    SELECT t.id, t.row_id, t.country_code, t.e_delivery_url, t.e_delivery_cert, t.tls_cert, t.status, t.last_ping_at, t.created_at
    FROM gates t WHERE t.id = ids.id ORDER BY t.created_at DESC, t.revision DESC LIMIT 1
  ) latest
  RETURNING 1
),
snapshot_platforms AS (
  INSERT INTO rm_platforms (generation, id, row_id, base_url, headers, e_delivery_cert, tls_cert, status, api_key_hint, api_key_generated_at, has_api_key, created_at)
  SELECT g.generation, latest.id, latest.row_id, latest.base_url, latest.headers, latest.e_delivery_cert, latest.tls_cert,
         latest.status::text, latest.api_key_hint, latest.api_key_generated_at, latest.api_key_hash IS NOT NULL, latest.created_at
  FROM new_generation g, (SELECT DISTINCT id FROM platforms) ids
  CROSS JOIN LATERAL (
    SELECT t.id, t.row_id, t.base_url, t.headers, t.e_delivery_cert, t.tls_cert, t.status, t.api_key_hint, t.api_key_generated_at, t.api_key_hash, t.created_at
    FROM platforms t WHERE t.id = ids.id ORDER BY t.created_at DESC, t.revision DESC LIMIT 1
  ) latest
  RETURNING 1
),
snapshot_authorities AS (
  INSERT INTO rm_authorities (generation, id, row_id, name, registry_code, subsets, status, created_at)
  SELECT g.generation, latest.id, latest.row_id, latest.name, latest.registry_code, latest.subsets, latest.status::text, latest.created_at
  FROM new_generation g, (SELECT DISTINCT id FROM authorities) ids
  CROSS JOIN LATERAL (
    SELECT t.id, t.row_id, t.name, t.registry_code, t.subsets, t.status, t.created_at
    FROM authorities t WHERE t.id = ids.id ORDER BY t.created_at DESC, t.revision DESC LIMIT 1
  ) latest
  RETURNING 1
),
published AS (
  INSERT INTO read_model_pointer (model, generation)
  SELECT m.model, g.generation FROM new_generation g, (VALUES ('gates'), ('platforms'), ('authorities')) AS m(model)
  RETURNING generation
)
SELECT
  (SELECT max(generation) FROM published) AS generation,
  (SELECT count(*) FROM snapshot_gates) AS gates,
  (SELECT count(*) FROM snapshot_platforms) AS platforms,
  (SELECT count(*) FROM snapshot_authorities) AS authorities;
