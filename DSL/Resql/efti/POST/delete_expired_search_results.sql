/*
description: physically removes cross-gate search state older than keepMinutes (useless once the
  polling window has passed)
params:
  keepMinutes: { type: number, default: 10 }
*/
WITH deleted AS (
  DELETE FROM search_results
  WHERE created_at < now() - make_interval(mins => COALESCE(:keepMinutes::int, 10))
  RETURNING row_id
)
SELECT count(*)::int AS purged_count FROM deleted;
