/*
description: ADR-012 read-model retention — physically removes snapshot rows of generations older than the
  newest keepGenerations published ones, and the superseded pointer rows. The current generation is never
  removed. Called only by POST /ops/v1/refresh-read-models (CronManager path, same as the archive sweep).
params:
  keepGenerations: { type: number, default: 2 }
*/
WITH cutoff AS (
  SELECT min(generation) AS oldest_kept FROM (
    SELECT generation FROM read_model_pointer
    WHERE model = 'consignment_counts'
    ORDER BY revision DESC
    LIMIT GREATEST(COALESCE(:keepGenerations, 2), 1)
  ) kept
),
purged_rows AS (
  DELETE FROM rm_consignment_counts
  WHERE generation < (SELECT oldest_kept FROM cutoff)
  RETURNING 1
),
purged_pointers AS (
  DELETE FROM read_model_pointer
  WHERE model = 'consignment_counts'
    AND generation < (SELECT oldest_kept FROM cutoff)
  RETURNING 1
)
SELECT (SELECT count(*) FROM purged_rows) AS purged_rows,
       (SELECT count(*) FROM purged_pointers) AS purged_pointers;
