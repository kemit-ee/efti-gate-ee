-- ----------------------------------------------------------------------------
-- 4.6 async_responses
-- ----------------------------------------------------------------------------

CREATE TABLE async_responses (
  row_id       UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  receiver_id  CITEXT       NOT NULL,
  request_id   TEXT         NOT NULL,
  body         TEXT         NOT NULL,
  consumed_at  TIMESTAMPTZ,
  consumed_by  UUID,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  async_responses IS 'Multi-node coordination for incoming eDelivery AS4 async responses. Append-only on storage (consumed_at=NULL rows). Consumption is a NEW row with consumed_at populated.';
COMMENT ON COLUMN async_responses.row_id      IS 'Synthetic primary key';
COMMENT ON COLUMN async_responses.receiver_id IS 'Gate or platform that should receive this response';
COMMENT ON COLUMN async_responses.request_id  IS 'Correlation ID matching the original outgoing request';
COMMENT ON COLUMN async_responses.body        IS 'Raw response payload (XML)';
COMMENT ON COLUMN async_responses.consumed_at IS 'When the response was consumed. NULL = pending pickup.';
COMMENT ON COLUMN async_responses.consumed_by IS 'Gate node identity that claimed this response. NULL on storage rows.';
COMMENT ON COLUMN async_responses.created_at  IS 'When this row was inserted';

CREATE INDEX idx_async_responses_pending ON async_responses (receiver_id, request_id, created_at DESC) WHERE consumed_at IS NULL;
CREATE INDEX idx_async_responses_created ON async_responses (created_at);
CREATE UNIQUE INDEX idx_async_responses_claim ON async_responses (receiver_id, request_id) WHERE consumed_at IS NOT NULL;

GRANT SELECT, INSERT ON async_responses TO app;
GRANT SELECT, DELETE ON async_responses TO db_archiver;
