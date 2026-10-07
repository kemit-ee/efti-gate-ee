/*
description: store an incoming AS4 response for the node that is waiting for it (INSERT-only)
params:
  requestKey: { type: string, required: true }
  body: { type: string, required: true }
*/
INSERT INTO async_responses (request_key, body)
VALUES (:requestKey, :body)
RETURNING row_id::text;
