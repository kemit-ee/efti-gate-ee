-- Migration: V3__create_indexes.sql
-- Description: Performance indexes for eFTI Gate v2.0
-- Author: Development Team
-- Date: 2026-04-22
-- Database: PostgreSQL 14+
--
-- Rollback:
--   DROP INDEX IF EXISTS idx_identifiers_id;
--   DROP INDEX IF EXISTS idx_identifiers_country;
--   DROP INDEX IF EXISTS idx_identifiers_id_country;
--   DROP INDEX IF EXISTS idx_identifiers_id_trgm;
--   DROP INDEX IF EXISTS idx_consignments_platform;
--   DROP INDEX IF EXISTS idx_consignments_delivered;
--   DROP INDEX IF EXISTS idx_consignments_created;
--   DROP INDEX IF EXISTS idx_consignments_mode;
--   DROP INDEX IF EXISTS idx_async_responses_created;
--   DROP INDEX IF EXISTS idx_change_history_table_row;
--   DROP INDEX IF EXISTS idx_gates_status;
--   DROP INDEX IF EXISTS idx_gates_country;
--   DROP EXTENSION IF EXISTS pg_trgm;

-- =====================================================
-- EXTENSIONS FOR FULL-TEXT / FUZZY SEARCH
-- =====================================================

-- pg_trgm: enables GIN trigram index for ILIKE / fuzzy plate search
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- =====================================================
-- IDENTIFIERS TABLE (most critical — identifier search hot path)
-- =====================================================

-- Exact identifier lookup (most common query: GET /identifiers/{plate})
-- Query: SELECT * FROM identifiers WHERE id = '123ABC'
CREATE INDEX IF NOT EXISTS idx_identifiers_id
  ON identifiers (id);

-- Country code filter (combined with id for two-parameter search)
-- Query: SELECT * FROM identifiers WHERE id = '123ABC' AND countryCode = 'EE'
CREATE INDEX IF NOT EXISTS idx_identifiers_country
  ON identifiers (countryCode)
  WHERE countryCode IS NOT NULL;

-- Composite index for plate + country (most selective, covers main authority search)
CREATE INDEX IF NOT EXISTS idx_identifiers_id_country
  ON identifiers (id, countryCode)
  WHERE countryCode IS NOT NULL;

-- GIN trigram index for fuzzy/partial plate search (ILIKE '%23AB%')
-- Requires pg_trgm extension
CREATE INDEX IF NOT EXISTS idx_identifiers_id_trgm
  ON identifiers USING GIN (id gin_trgm_ops);

-- DatasetId lookup (used in CASCADE DELETE and JOIN from consignments)
-- Already created in V1 as identifiers_dataset_idx — kept here for documentation
-- CREATE INDEX IF NOT EXISTS identifiers_dataset_idx ON identifiers (datasetId);

-- =====================================================
-- CONSIGNMENTS TABLE
-- =====================================================

-- Platform ownership (for platform-specific queries and row-level filtering)
-- Query: SELECT * FROM consignments WHERE platformId = 'demo'
CREATE INDEX IF NOT EXISTS idx_consignments_platform
  ON consignments (platformId);

-- Expiry job index: find consignments older than 90 days with no deliveredAt
-- Query: SELECT * FROM consignments WHERE deliveredAt IS NULL AND createdAt < NOW() - INTERVAL '90 days'
-- Partial index: only indexes rows not yet delivered (small subset of total)
CREATE INDEX IF NOT EXISTS idx_consignments_delivered
  ON consignments (createdAt)
  WHERE deliveredAt IS NULL;

-- Dataset creation time (for ordering / pagination)
CREATE INDEX IF NOT EXISTS idx_consignments_created
  ON consignments (createdAt DESC);

-- Transport mode filter
-- Query: SELECT * FROM consignments WHERE mode = 'ROAD'
CREATE INDEX IF NOT EXISTS idx_consignments_mode
  ON consignments (mode)
  WHERE mode IS NOT NULL;

-- Dangerous goods quick filter
-- Query: SELECT * FROM consignments WHERE dangerousGoods = TRUE
CREATE INDEX IF NOT EXISTS idx_consignments_dangerous
  ON consignments (dangerousGoods)
  WHERE dangerousGoods = TRUE;

-- =====================================================
-- ASYNC RESPONSES TABLE
-- =====================================================

-- Cleanup old async responses (maintenance queries)
CREATE INDEX IF NOT EXISTS idx_async_responses_created
  ON async_responses (createdAt);

-- =====================================================
-- CHANGE HISTORY TABLE
-- =====================================================

-- Already created in V1 as change_history_id_idx — kept here for documentation
-- CREATE INDEX IF NOT EXISTS change_history_id_idx ON change_history ("table", rowId);

-- Timestamp for audit queries (find changes in a time range)
CREATE INDEX IF NOT EXISTS idx_change_history_changed_at
  ON change_history (changedAt DESC);

-- User activity lookup (who changed what)
CREATE INDEX IF NOT EXISTS idx_change_history_user
  ON change_history (changedBy)
  WHERE changedBy IS NOT NULL;

-- =====================================================
-- GATES TABLE
-- =====================================================

-- Status filter (find ONLINE gates for broadcast)
-- Query: SELECT * FROM gates WHERE status = 'ONLINE' AND id != 'eu-ee31'
-- Partial index: covers the most common query (only ONLINE gates)
CREATE INDEX IF NOT EXISTS idx_gates_status
  ON gates (status)
  WHERE status = 'ONLINE';

-- Country code lookup (find gates for a specific country)
CREATE INDEX IF NOT EXISTS idx_gates_country
  ON gates (countryCode);

-- =====================================================
-- USERS TABLE
-- =====================================================

-- Email lookup (authentication hot path)
-- Already covered by UNIQUE constraint on email column (creates implicit B-tree index)

-- JSONB roles index (find users by role type)
-- Query: SELECT * FROM users WHERE roles ? 'AUTHORITY'
CREATE INDEX IF NOT EXISTS idx_users_roles
  ON users USING GIN (roles);

-- =====================================================
-- MIGRATION COMPLETE
-- =====================================================

DO $$
BEGIN
  RAISE NOTICE 'Migration V3 completed: Performance indexes created (identifiers, consignments, gates, users, async_responses, change_history)';
END $$;
