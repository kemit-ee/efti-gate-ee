/*
description: latest in-flight state for a cross-gate identifier search (K4). Returns at most one
  row carrying status and body. No row means the search was never registered (or was already
  purged) — the poll path answers x-poll-more:false for both.
params:
  searchId: { type: string, required: true }
*/
SELECT DISTINCT ON (search_id)
  status::text,
  body
FROM search_results
WHERE search_id = :searchId
ORDER BY search_id, created_at DESC, row_id DESC;
