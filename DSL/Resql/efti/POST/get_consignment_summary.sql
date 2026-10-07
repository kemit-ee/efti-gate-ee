/*
description: ADR-012 read model - consignment counts for an authority, summed over a manual day range, from the
  latest published generation. Only the ungated total and the dimensions whose eFTI subset is in subsets are
  returned; the caller passes the authority's subsets read from the source authorities table (this read model is
  never the entitlement source). Staleness-tolerant reporting.
params:
  subsets: { type: array, items: { type: string } }
  from: { type: string }
  to: { type: string }
  dimension: { type: string }
  limit: { type: number, default: 100 }
  offset: { type: number, default: 0 }
*/
SELECT
  rm.subset,
  rm.dimension,
  rm.dim_value,
  sum(rm.consignment_count)::bigint AS consignment_count
FROM rm_consignment_summary rm
WHERE rm.generation = (
    SELECT p.generation FROM read_model_pointer p
    WHERE p.model = 'consignment_summary'
    ORDER BY p.revision DESC LIMIT 1
  )
  AND (rm.subset = '' OR rm.subset = ANY(COALESCE(:subsets::text[], ARRAY[]::text[])))
  AND (:from::text IS NULL OR rm.day >= :from::date)
  AND (:to::text IS NULL OR rm.day <= :to::date)
  AND (:dimension::text IS NULL OR rm.dimension = :dimension)
GROUP BY rm.subset, rm.dimension, rm.dim_value
ORDER BY rm.subset, rm.dimension, rm.dim_value
LIMIT LEAST(GREATEST(COALESCE(:limit, 100), 0), 1000) OFFSET GREATEST(COALESCE(:offset, 0), 0);
