/*
description: record the outcome of a scheduled job run (INSERT-only; no running-state row)
params:
  jobName: { type: string, required: true }
  startedAt: { type: string }
  status: { type: string, default: "COMPLETED" }
  details: { type: object }
*/
INSERT INTO jobs_execution_log (job_name, started_at, finished_at, status, details)
VALUES (
  :jobName,
  COALESCE(:startedAt::timestamptz, now()),
  now(),
  COALESCE(:status, 'COMPLETED')::job_status,
  :details
)
RETURNING row_id::text, job_name, status::text, finished_at;
