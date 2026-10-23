/*
description: physically removes inbound AS4 duplicate-elimination rows older than keepHours (#336); a peer's retries stop long before that
params:
  keepHours: { type: number, default: 48 }
*/
WITH deleted AS (
  DELETE FROM inbound_messages
  WHERE created_at < now() - make_interval(hours => COALESCE(:keepHours::int, 48))
  RETURNING row_id
)
SELECT count(*)::int AS purged_count FROM deleted;
