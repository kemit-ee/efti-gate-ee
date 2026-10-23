/*
description: record an accepted inbound AS4 message with the receipt that was sent (INSERT-only, #336). The unique (sender, message id) index makes it atomic across nodes: the winner gets a row back, a repeat or a concurrent loser gets none.
params:
  senderId: { type: string, required: true }
  messageId: { type: string, required: true }
  payloadSha256: { type: string, required: true }
  receiptB64: { type: string, required: true }
*/
INSERT INTO inbound_messages (sender_id, message_id, payload_sha256, receipt_b64)
VALUES (:senderId, :messageId, :payloadSha256, :receiptB64)
ON CONFLICT (sender_id, message_id) DO NOTHING
RETURNING row_id::text;
