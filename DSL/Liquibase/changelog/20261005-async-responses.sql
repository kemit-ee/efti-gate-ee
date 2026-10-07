--liquibase formatted sql

--changeset efti:async-responses
CREATE TABLE async_responses (
  row_id       UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  request_key  TEXT         NOT NULL,
  body         TEXT         NOT NULL,
  claimed      BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  async_responses IS 'Cross-node hand-off of incoming eDelivery AS4 responses. The node that receives a reply with no local waiter inserts it; the node holding the open request claims it atomically by request_key. Rows are short-lived and purged by ops/v1/purge-async-responses.';
COMMENT ON COLUMN async_responses.request_key IS 'RequestKey.toString() of the original outgoing request (receiverId:requestId:senderId)';
COMMENT ON COLUMN async_responses.body        IS 'Raw response payload (XML)';
COMMENT ON COLUMN async_responses.claimed     IS 'FALSE = stored response. TRUE = claim row inserted by the waiting node; the partial unique index makes the claim atomic (exactly one claimer per request_key).';

CREATE INDEX idx_async_responses_key     ON async_responses (request_key, created_at DESC);
CREATE UNIQUE INDEX idx_async_responses_claim ON async_responses (request_key) WHERE claimed;
CREATE INDEX idx_async_responses_created ON async_responses (created_at);

GRANT SELECT, INSERT ON async_responses TO app;
GRANT SELECT, DELETE ON async_responses TO db_archiver;
--rollback DROP TABLE async_responses;
