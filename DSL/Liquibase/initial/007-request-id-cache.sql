-- ----------------------------------------------------------------------------
-- 4.1 request_id_cache
-- ----------------------------------------------------------------------------

CREATE TABLE request_id_cache (
  request_id  VARCHAR(100) PRIMARY KEY,
  seen_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  expires_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW() + INTERVAL '10 minutes'
);

COMMENT ON TABLE  request_id_cache IS 'Short-lived cache of seen X-Request-ID values for duplicate-request detection. Cron-archived by CronManager when expires_at < NOW().';
COMMENT ON COLUMN request_id_cache.request_id IS 'X-Request-ID value (UUID string). Primary key.';
COMMENT ON COLUMN request_id_cache.seen_at    IS 'When the request was first seen';
COMMENT ON COLUMN request_id_cache.expires_at IS 'After this timestamp the row is eligible for archival (default seen_at + 10 min)';

CREATE INDEX idx_request_id_cache_expires ON request_id_cache (expires_at);

GRANT SELECT, INSERT ON request_id_cache TO app;
GRANT SELECT, DELETE ON request_id_cache TO db_archiver;
