--liquibase formatted sql

--changeset efti:consignments-created-index runInTransaction:false
-- Newest-first order for get_consignments.sql: a broad search walks this index and stops at LIMIT
-- instead of anti-joining and sorting the whole table.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_consignments_created_latest ON consignments (created_at DESC, revision DESC);
--rollback DROP INDEX CONCURRENTLY IF EXISTS idx_consignments_created_latest;
