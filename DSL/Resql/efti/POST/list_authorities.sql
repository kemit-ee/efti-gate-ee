/*
description: ADR-012 read model - one page of authorities for the admin list endpoint, from the latest published
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
  rm.name,
  rm.registry_code,
  rm.subsets,
  rm.status,
  rm.created_at
FROM rm_authorities rm
WHERE rm.generation = (
    SELECT p.generation FROM read_model_pointer p
    WHERE p.model = 'authorities'
    ORDER BY p.revision DESC LIMIT 1
  )
  AND (:status IS NULL AND rm.status != 'DELETED' OR rm.status = :status)
ORDER BY rm.id
LIMIT LEAST(GREATEST(COALESCE(:limit, 20), 0), 1000) OFFSET GREATEST(COALESCE(:offset, 0), 0);
