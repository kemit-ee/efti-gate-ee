--liquibase formatted sql

--changeset efti:inbound-messages
CREATE TABLE inbound_messages (
  row_id          UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  sender_id       TEXT         NOT NULL,
  message_id      TEXT         NOT NULL,
  payload_sha256  TEXT         NOT NULL,
  receipt_b64     TEXT         NOT NULL,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  inbound_messages IS 'Duplicate elimination for inbound AS4 messages in turvis mode (#336). One row per accepted (verified sender, MessageId); a repeat of the same pair is answered with the stored receipt and not dispatched again. Shared by all edelivery nodes; purged by ops/v1/purge-inbound-messages.';
COMMENT ON COLUMN inbound_messages.sender_id      IS 'Upper-cased signer.verified_identity from Turvis verify (never the unsigned envelope From), so one peer cannot suppress another peer''s message ids';
COMMENT ON COLUMN inbound_messages.message_id     IS 'ebMS MessageId from Turvis verify ebms_routing';
COMMENT ON COLUMN inbound_messages.payload_sha256 IS 'SHA-256 (hex) of the decrypted payload; a repeat with the same id but another hash is counted as a content mismatch and not dispatched';
COMMENT ON COLUMN inbound_messages.receipt_b64    IS 'Base64 of the signed receipt exactly as it was sent, repeated byte for byte to the peer on a duplicate';

CREATE UNIQUE INDEX idx_inbound_messages_key     ON inbound_messages (sender_id, message_id);
CREATE INDEX        idx_inbound_messages_created ON inbound_messages (created_at);

GRANT SELECT, INSERT ON inbound_messages TO app;
GRANT SELECT, DELETE ON inbound_messages TO db_archiver;
--rollback DROP TABLE inbound_messages;
