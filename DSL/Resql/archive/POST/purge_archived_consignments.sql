/*
description: (archive DB) cold-storage retention — permanently delete archived rows older than
  keepDays. Separate from the copy flow so retention policy can be tuned on its own.
params:
  keepDays: { type: number, default: 2555 }
*/
WITH d AS (
  DELETE FROM consignments
  WHERE archived_at < now() - make_interval(days => COALESCE(:keepDays, 2555)::int)
  RETURNING row_id
)
SELECT count(*) AS purged_count FROM d;
