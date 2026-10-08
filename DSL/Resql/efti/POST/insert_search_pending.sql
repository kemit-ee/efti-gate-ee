/*
description: register a cross-gate identifier search (K4). Inserts a pending row so a poll can tell
  "broadcast registered, result pending" from "unknown search id". A detached Ruuter task later
  appends the complete row. INSERT-only.
params:
  searchId: { type: string, required: true }
*/
INSERT INTO search_results (search_id, status)
VALUES (:searchId, 'pending')
RETURNING row_id::text;
