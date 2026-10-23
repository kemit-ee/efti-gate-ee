/*
description: the stored receipt and payload hash of an already accepted inbound AS4 message (duplicate elimination, #336); no row when the (sender, message id) pair is new
params:
  senderId: { type: string, required: true }
  messageId: { type: string, required: true }
*/
SELECT receipt_b64, payload_sha256
FROM inbound_messages
WHERE sender_id = :senderId AND message_id = :messageId
LIMIT 1;
