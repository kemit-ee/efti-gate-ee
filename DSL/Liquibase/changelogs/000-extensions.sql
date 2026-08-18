-- ============================================================================
-- 0. EXTENSIONS + ROLES
-- ============================================================================

CREATE EXTENSION "uuid-ossp";
CREATE EXTENSION "citext";
CREATE EXTENSION "pg_trgm";
CREATE EXTENSION "btree_gin";

CREATE USER app WITH PASSWORD 'app-secret';
CREATE USER db_archiver WITH PASSWORD 'archiver-secret';

GRANT USAGE ON SCHEMA public TO app;
GRANT USAGE ON SCHEMA public TO db_archiver;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT ON TABLES    TO app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT  ON SEQUENCES TO app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE        ON FUNCTIONS TO app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, DELETE ON TABLES    TO db_archiver;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT  ON SEQUENCES TO db_archiver;
