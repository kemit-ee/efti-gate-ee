-- ----------------------------------------------------------------------------
-- 4.4 follow_up_log
-- ----------------------------------------------------------------------------

DO $$
BEGIN
  CREATE TYPE follow_up_status AS ENUM (
    'DELIVERED',
    'FAILED'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END$$;

COMMENT ON TYPE follow_up_status IS 'Outcome of a follow-up message forwarding attempt';

CREATE TABLE IF NOT EXISTS follow_up_log (
  row_id                  UUID             PRIMARY KEY DEFAULT uuid_generate_v4(),
  follow_up_id            UUID             NOT NULL,
  requesting_gate_id      CITEXT           NOT NULL,
  requesting_user_id      UUID,
  dataset_request_id      UUID             NOT NULL,
  destination_gate_id     CITEXT           NOT NULL,
  destination_platform_id CITEXT           NOT NULL,
  payload                 TEXT             NOT NULL,
  status                  follow_up_status NOT NULL,
  failure_reason          TEXT,
  received_at             TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
  created_at              TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  follow_up_log IS 'Log of follow-up messages received by the AAP and forwarded to the destination platform/gate. Mandatory fields per Art 6(2)(c) Reg 2024/1942. INSERT-only.';
COMMENT ON COLUMN follow_up_log.row_id                  IS 'Synthetic primary key';
COMMENT ON COLUMN follow_up_log.follow_up_id            IS 'Unique identifier of the follow-up message (UUIDv4). Mandatory per Art 6(2)(c).';
COMMENT ON COLUMN follow_up_log.requesting_gate_id      IS 'Gate that originated the follow-up (AAP). Mandatory per Art 6(2)(c).';
COMMENT ON COLUMN follow_up_log.requesting_user_id      IS 'Logical users.id of the authority user (NULL for G2G follow-ups).';
COMMENT ON COLUMN follow_up_log.dataset_request_id      IS 'Dataset request being followed up on';
COMMENT ON COLUMN follow_up_log.destination_gate_id     IS 'Gate that owns the target platform';
COMMENT ON COLUMN follow_up_log.destination_platform_id IS 'Platform the follow-up was delivered to';
COMMENT ON COLUMN follow_up_log.payload                 IS 'Follow-up message body, forwarded unchanged';
COMMENT ON COLUMN follow_up_log.status                  IS 'delivered or failed';
COMMENT ON COLUMN follow_up_log.failure_reason          IS 'Error description when status=failed';
COMMENT ON COLUMN follow_up_log.received_at             IS 'Mandatory per Art 6(2)(c): when the follow-up was received by the AAP';
COMMENT ON COLUMN follow_up_log.created_at              IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_follow_up_log_received   ON follow_up_log (received_at DESC);
CREATE INDEX IF NOT EXISTS idx_follow_up_log_dataset    ON follow_up_log (dataset_request_id);
CREATE INDEX IF NOT EXISTS idx_follow_up_log_requesting ON follow_up_log (requesting_gate_id, received_at DESC);

GRANT SELECT, INSERT ON follow_up_log TO app;
GRANT SELECT, DELETE ON follow_up_log TO db_archiver;
