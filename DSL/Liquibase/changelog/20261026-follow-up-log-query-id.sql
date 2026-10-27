--liquibase formatted sql

--changeset efti:follow-up-log-query-id splitStatements:false
ALTER TABLE follow_up_log ADD COLUMN query_id UUID;
ALTER TABLE follow_up_log ADD COLUMN revision BIGINT GENERATED ALWAYS AS IDENTITY;

COMMENT ON COLUMN follow_up_log.query_id IS 'FTI025 query id (the x-request-id the follow-up arrived with), NULL when the caller gave none or not a UUID. A DELIVERED row for the same (query_id, destination platform, dataset request) makes a repeat a no-op (#354).';
COMMENT ON COLUMN follow_up_log.revision IS 'Insertion order of rows that share a query_id, assigned under the advisory lock of follow_up_log_once()';

CREATE INDEX idx_follow_up_log_query ON follow_up_log (query_id, destination_platform_id, dataset_request_id, revision DESC) WHERE query_id IS NOT NULL;
GRANT USAGE, SELECT ON SEQUENCE follow_up_log_revision_seq TO app;

CREATE FUNCTION follow_up_log_once() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  previous follow_up_status;
BEGIN
  IF NEW.query_id IS NOT NULL THEN
    PERFORM pg_advisory_xact_lock(hashtextextended('follow_up_log:' || NEW.query_id::text, 0));
    SELECT status INTO previous FROM follow_up_log
     WHERE query_id = NEW.query_id
       AND destination_platform_id = NEW.destination_platform_id
       AND dataset_request_id = NEW.dataset_request_id
     ORDER BY revision DESC LIMIT 1;
    IF NEW.status = 'DELIVERED' AND previous = 'DELIVERED' THEN
      RETURN NULL;
    END IF;
  END IF;
  NEW.revision := nextval(pg_get_serial_sequence('follow_up_log', 'revision'));
  RETURN NEW;
END $$;

CREATE TRIGGER follow_up_log_once BEFORE INSERT ON follow_up_log FOR EACH ROW EXECUTE FUNCTION follow_up_log_once();
--rollback DROP TRIGGER follow_up_log_once ON follow_up_log; DROP FUNCTION follow_up_log_once(); DROP INDEX idx_follow_up_log_query; ALTER TABLE follow_up_log DROP COLUMN revision; ALTER TABLE follow_up_log DROP COLUMN query_id;
