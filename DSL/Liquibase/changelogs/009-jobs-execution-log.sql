-- ----------------------------------------------------------------------------
-- 4.3 jobs_execution_log
-- ----------------------------------------------------------------------------

DO $$
BEGIN
  CREATE TYPE job_status AS ENUM (
    'COMPLETED',
    'FAILED'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END$$;

COMMENT ON TYPE job_status IS 'Final outcome of a scheduled job execution. jobs_execution_log is INSERT-only at job completion — there is no "running" record.';

CREATE TABLE IF NOT EXISTS jobs_execution_log (
  row_id       UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
  job_name     VARCHAR(100) NOT NULL,
  started_at   TIMESTAMPTZ  NOT NULL,
  finished_at  TIMESTAMPTZ  NOT NULL,
  status       job_status   NOT NULL,
  details      JSONB,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  jobs_execution_log IS 'Final report of each scheduled job execution. INSERT-only at completion (or failure). No running-state row.';
COMMENT ON COLUMN jobs_execution_log.row_id      IS 'Synthetic primary key';
COMMENT ON COLUMN jobs_execution_log.job_name    IS 'Logical job name (e.g. "ping-job", "expiry-job")';
COMMENT ON COLUMN jobs_execution_log.started_at  IS 'When the job execution actually began';
COMMENT ON COLUMN jobs_execution_log.finished_at IS 'When the job execution completed or failed';
COMMENT ON COLUMN jobs_execution_log.status      IS 'completed or failed';
COMMENT ON COLUMN jobs_execution_log.details     IS 'JSON details about the run (e.g. {"expired_count":14,"errors":[]})';
COMMENT ON COLUMN jobs_execution_log.created_at  IS 'When this row was inserted';

CREATE INDEX IF NOT EXISTS idx_jobs_log_job_name   ON jobs_execution_log (job_name, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_jobs_log_started_at ON jobs_execution_log (started_at DESC);

GRANT SELECT, INSERT ON jobs_execution_log TO app;
GRANT SELECT, DELETE ON jobs_execution_log TO db_archiver;
