/*
description: atomically claim the stored AS4 response for a request key. Inserts a claim row; the
  partial unique index (request_key) WHERE claimed lets exactly one caller win. Returns the body
  for the winner and no row when the reply has not arrived yet or is already claimed.
params:
  requestKey: { type: string, required: true }
*/
INSERT INTO async_responses (request_key, body, claimed)
SELECT r.request_key, r.body, TRUE
FROM async_responses r
WHERE r.request_key = :requestKey AND NOT r.claimed
ORDER BY r.created_at DESC, r.row_id
LIMIT 1
ON CONFLICT (request_key) WHERE claimed DO NOTHING
RETURNING body;
