-- ============================================================================
-- 0. EXTENSIONS + ROLES
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "citext";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "btree_gin";

CREATE USER app WITH PASSWORD 'app-secret';
CREATE USER db_archiver WITH PASSWORD 'archiver-secret';

GRANT USAGE ON SCHEMA public TO app;
GRANT USAGE ON SCHEMA public TO db_archiver;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT ON TABLES    TO app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT  ON SEQUENCES TO app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE        ON FUNCTIONS TO app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, DELETE ON TABLES    TO db_archiver;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT  ON SEQUENCES TO db_archiver;

-- RDS create_default_users roles inherit the schema grants above (resql = efti_rw).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'efti_rw') THEN
    EXECUTE 'GRANT app TO efti_rw';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'efti_ro') THEN
    EXECUTE 'GRANT db_archiver TO efti_ro';
  END IF;
END $$;
