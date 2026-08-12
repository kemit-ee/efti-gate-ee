SELECT
  version()                      AS pg_version,
  pg_postmaster_start_time()     AS started_at,
  NOW() - pg_postmaster_start_time() AS uptime;
