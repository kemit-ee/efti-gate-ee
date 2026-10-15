/*
description: atomically record a request id in the shared dedup cache (request_id_cache); returns
  the row only when it was newly inserted, so an empty result means the id was already seen
  (works identically across nodes since the uniqueness is enforced by the DB, not in-process state)
params:
  requestId: { type: string, required: true }
*/
INSERT INTO request_id_cache (request_id)
VALUES (:requestId)
ON CONFLICT (request_id) DO NOTHING
RETURNING request_id;
