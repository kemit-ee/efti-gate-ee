/*
description: ADR-012 read model - one page of platforms for the admin list endpoint, from the latest published
  generation by primary key. Staleness-tolerant; the admin write routes refresh the model right after each
  write. Never use for routing, authentication or entitlement decisions (those read the source tables).
params:
  status: { type: string }
  limit: { type: number, default: 20 }
  offset: { type: number, default: 0 }
*/
SELECT
  rm.id,
  rm.row_id,
  rm.base_url,
  rm.headers,
  rm.e_delivery_cert,
  rm.tls_cert,
  rm.status,
  rm.api_key_hint,
  rm.api_key_generated_at,
  rm.has_api_key,
  rm.created_at
FROM rm_platforms rm
WHERE rm.generation = (
    SELECT p.generation FROM read_model_pointer p
    WHERE p.model = 'platforms'
    ORDER BY p.revision DESC LIMIT 1
  )
  AND (:status IS NULL AND rm.status != 'DELETED' OR rm.status = :status)
ORDER BY rm.id
LIMIT LEAST(GREATEST(COALESCE(:limit, 20), 0), 1000) OFFSET GREATEST(COALESCE(:offset, 0), 0);
