/*
description: ADR-012 read-model refresh — writes one full snapshot of the current (latest-row) consignment
  counts per gate, platform and status as a new generation, then publishes it by inserting the pointer row.
  Everything is one statement, so a reader sees either the previous generation or the complete new one,
  never a partial snapshot. An advisory transaction lock serialises overlapping CronManager runs.
  Called only by POST /ops/v1/refresh-read-models.
params: {}
*/
WITH serialised AS (
  SELECT pg_advisory_xact_lock(hashtextextended('read_model:consignment_counts', 0))
),
new_generation AS (
  SELECT nextval('read_model_generation_seq') AS generation FROM serialised
),
snapshot AS (
  INSERT INTO rm_consignment_counts (generation, gate_id, platform_id, status, consignment_count)
  SELECT g.generation, c.gate_id, c.platform_id, c.status, count(*)
  FROM new_generation g, consignments c
  WHERE c.status != 'DELETED'
    AND NOT EXISTS (
      SELECT 1 FROM consignments c2
      WHERE c2.platform_id = c.platform_id
        AND c2.dataset_id  = c.dataset_id
        AND (c2.created_at, c2.revision) > (c.created_at, c.revision)
    )
  GROUP BY g.generation, c.gate_id, c.platform_id, c.status
  RETURNING consignment_count
),
published AS (
  INSERT INTO read_model_pointer (model, generation)
  SELECT 'consignment_counts', generation FROM new_generation
  RETURNING generation
)
SELECT
  (SELECT generation FROM published) AS generation,
  (SELECT count(*) FROM snapshot)    AS row_count;
