/*
description: pick a batch of superseded consignments rows (live DB) for the archive flow to move.
  A candidate has a NEWER sibling row for the same (platform_id, dataset_id) AND that newer sibling
  is itself older than olderThanDays — so the current row of every dataset, including a DELETED
  tombstone, is never a candidate.
params:
  olderThanDays: { type: number, default: 2 }
  batchLimit: { type: number, default: 100 }
*/
WITH candidates AS (
  SELECT c.*
  FROM consignments c
  WHERE EXISTS (
    SELECT 1 FROM consignments n
    WHERE n.platform_id = c.platform_id
      AND n.dataset_id  = c.dataset_id
      AND n.created_at   > c.created_at
      AND n.created_at   < now() - make_interval(days => COALESCE(:olderThanDays, 2)::int)
  )
  -- An expression, not the bare column: ordered by idx_consignments_created_latest the planner walks
  -- every current row oldest-first probing for a newer sibling, and the last call of an archive run,
  -- which finds none, then reads the whole table row by row.
  ORDER BY c.created_at + interval '0'
  LIMIT COALESCE(:batchLimit, 100)
)
SELECT
  count(*)                                                  AS candidate_count,
  COALESCE(array_agg(row_id::text), '{}')                   AS candidate_row_ids,
  COALESCE(jsonb_agg(to_jsonb(candidates)), '[]'::jsonb)    AS rows
FROM candidates;
