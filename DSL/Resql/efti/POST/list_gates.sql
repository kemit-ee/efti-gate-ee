/*
description: ADR-012 read model - one page of gates for the admin list endpoint, from the latest published
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
  rm.country_code,
  rm.e_delivery_url,
  rm.e_delivery_cert,
  rm.tls_cert,
  rm.status,
  rm.last_ping_at,
  rm.created_at
FROM rm_gates rm
WHERE rm.generation = (
    SELECT p.generation FROM read_model_pointer p
    WHERE p.model = 'gates'
    ORDER BY p.revision DESC LIMIT 1
  )
  AND (:status IS NULL AND rm.status != 'DELETED' OR rm.status = :status)
ORDER BY rm.id
LIMIT LEAST(GREATEST(COALESCE(:limit, 20), 0), 1000) OFFSET GREATEST(COALESCE(:offset, 0), 0);
