/*
description: store the aggregated cross-gate search result (K4). Appends a complete row whose body
  is the ConsignmentRow[] produced by the detached parallel_http fan-out + xml-mapper conversion.
  The caller JSON-encodes the array; the column is JSONB so the poll reads it back as native JSON.
  The latest row per search_id wins. INSERT-only.
params:
  searchId: { type: string, required: true }
  body: { type: string, required: true }
*/
INSERT INTO search_results (search_id, status, body)
VALUES (:searchId, 'complete', :body::jsonb)
RETURNING row_id::text;
