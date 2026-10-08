/*
description: ADR-012 read-model retention - physically removes snapshot rows of generations older than the
  newest keepGenerations published ones (per read model) and the superseded pointer rows. The current
  generation is never removed. Called only by POST /ops/v1/refresh-read-models (CronManager path, same as
  the archive sweep).
params:
  keepGenerations: { type: number, default: 2 }
*/
WITH keep AS (
  SELECT GREATEST(COALESCE(:keepGenerations, 2), 1) AS n
),
cutoff AS (
  SELECT model, min(generation) AS oldest_kept FROM (
    SELECT model, generation, row_number() OVER (PARTITION BY model ORDER BY revision DESC) AS rn
    FROM read_model_pointer
  ) ranked, keep
  WHERE ranked.rn <= keep.n
  GROUP BY model
),
purged_counts AS (
  DELETE FROM rm_consignment_counts
  WHERE generation < (SELECT oldest_kept FROM cutoff WHERE model = 'consignment_counts')
  RETURNING 1
),
purged_summary AS (
  DELETE FROM rm_consignment_summary
  WHERE generation < (SELECT oldest_kept FROM cutoff WHERE model = 'consignment_summary')
  RETURNING 1
),
purged_pointers AS (
  DELETE FROM read_model_pointer p
  USING cutoff c
  WHERE p.model = c.model AND p.generation < c.oldest_kept
  RETURNING 1
)
SELECT
  (SELECT count(*) FROM purged_counts) + (SELECT count(*) FROM purged_summary) AS purged_rows,
  (SELECT count(*) FROM purged_pointers) AS purged_pointers;
