/*
description: ADR-012 read model — current consignment counts per gate, platform and status, served from the
  latest published generation by primary-key range. Staleness-tolerant (reporting); never use for
  entitlement or verify-after-write decisions.
params:
  gateId: { type: string }
  platformId: { type: string }
  status: { type: string }
  limit: { type: number, default: 100 }
  offset: { type: number, default: 0 }
*/
SELECT
  rm.generation,
  rm.gate_id,
  rm.platform_id,
  rm.status::text,
  rm.consignment_count,
  rm.created_at
FROM rm_consignment_counts rm
WHERE rm.generation = (
    SELECT p.generation FROM read_model_pointer p
    WHERE p.model = 'consignment_counts'
    ORDER BY p.revision DESC LIMIT 1
  )
  AND (:gateId IS NULL OR rm.gate_id = :gateId)
  AND (:platformId IS NULL OR rm.platform_id = :platformId)
  AND (:status IS NULL OR rm.status = :status::consignment_status)
ORDER BY rm.gate_id, rm.platform_id, rm.status
LIMIT LEAST(GREATEST(COALESCE(:limit, 100), 0), 1000) OFFSET GREATEST(COALESCE(:offset, 0), 0);
