/*
description: physically removes AS4 hand-off rows older than keepMinutes (they are useless once the waiting call has ended)
params:
  keepMinutes: { type: number, default: 10 }
*/
WITH deleted AS (
  DELETE FROM async_responses
  WHERE created_at < now() - make_interval(mins => COALESCE(:keepMinutes::int, 10))
  RETURNING row_id
)
SELECT count(*)::int AS purged_count FROM deleted;
