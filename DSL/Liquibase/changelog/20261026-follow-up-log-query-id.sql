--liquibase formatted sql

--changeset efti:follow-up-status-pending
ALTER TYPE follow_up_status ADD VALUE IF NOT EXISTS 'PENDING';
--rollback SELECT 1;

--changeset efti:follow-up-log-query-id splitStatements:false
ALTER TABLE follow_up_log ADD COLUMN query_id UUID;
ALTER TABLE follow_up_log ADD COLUMN content_hash TEXT;
ALTER TABLE follow_up_log ADD COLUMN revision BIGINT GENERATED ALWAYS AS IDENTITY;

COMMENT ON COLUMN follow_up_log.query_id IS 'FTI025 query id (the x-request-id the follow-up arrived with), NULL when the caller gave none or not a UUID. Together with the destination platform, the dataset request and content_hash it identifies one follow-up for duplicate elimination (#354).';
COMMENT ON COLUMN follow_up_log.content_hash IS 'SHA-256 (hex) computed by insert_follow_up_log over the JSON of message, referenceIds and files; a repeat with the same query id but other content is a different follow-up';
COMMENT ON COLUMN follow_up_log.revision IS 'Order of the rows of one follow-up, assigned under the advisory lock of follow_up_log_once() (increasing, not gap-free)';

CREATE INDEX idx_follow_up_log_query ON follow_up_log (query_id, destination_platform_id, dataset_request_id, revision DESC) WHERE query_id IS NOT NULL;
GRANT USAGE, SELECT ON SEQUENCE follow_up_log_revision_seq TO app;

CREATE FUNCTION follow_up_log_once() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  previous_status follow_up_status;
  previous_at TIMESTAMPTZ;
BEGIN
  IF NEW.query_id IS NOT NULL THEN
    PERFORM pg_advisory_xact_lock(hashtextextended('follow_up_log:' || NEW.query_id::text, 0));
    IF NEW.status = 'PENDING' THEN
      SELECT status, created_at INTO previous_status, previous_at FROM follow_up_log
       WHERE query_id = NEW.query_id
         AND destination_platform_id = NEW.destination_platform_id
         AND dataset_request_id = NEW.dataset_request_id
         AND content_hash IS NOT DISTINCT FROM NEW.content_hash
       ORDER BY revision DESC LIMIT 1;
      IF previous_status = 'DELIVERED'
         OR (previous_status = 'PENDING' AND previous_at > now() - interval '120 seconds') THEN
        RETURN NULL;
      END IF;
    END IF;
  END IF;
  NEW.revision := nextval(pg_get_serial_sequence('follow_up_log', 'revision'));
  RETURN NEW;
END $$;

CREATE TRIGGER follow_up_log_once BEFORE INSERT ON follow_up_log FOR EACH ROW EXECUTE FUNCTION follow_up_log_once();
--rollback DROP TRIGGER follow_up_log_once ON follow_up_log; DROP FUNCTION follow_up_log_once(); DROP INDEX idx_follow_up_log_query; ALTER TABLE follow_up_log DROP COLUMN revision; ALTER TABLE follow_up_log DROP COLUMN content_hash; ALTER TABLE follow_up_log DROP COLUMN query_id;
