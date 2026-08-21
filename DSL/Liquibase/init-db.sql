-- Runs once, as superuser, at cluster initialisation. The database and role
-- themselves come from the compose environment variables; this file only installs
-- the extensions the schema depends on (see docs/specs/db/schema.sql §2).
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";   -- uuid_generate_v4()
CREATE EXTENSION IF NOT EXISTS "citext";      -- case-insensitive text (users.email)
CREATE EXTENSION IF NOT EXISTS "pg_trgm";     -- trigram fuzzy search (consignments, later)
CREATE EXTENSION IF NOT EXISTS "btree_gin";   -- GIN indexes on scalar types (consignments, later)
