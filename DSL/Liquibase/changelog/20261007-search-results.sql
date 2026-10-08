--liquibase formatted sql

--changeset efti:search-results
CREATE TABLE search_results (
  row_id     UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
  search_id  TEXT        NOT NULL,
  status     TEXT        NOT NULL,
  body       JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  search_results IS 'Ruuter-owned in-flight state for cross-gate identifier search (K4). The first authority/search call inserts a pending row; a detached Ruuter task writes the aggregated ConsignmentRow[] as a complete row once every peer gate has answered. Polls read the latest row per search_id. Ephemeral; purged by ops/v1/purge-search-results.';
COMMENT ON COLUMN search_results.search_id IS 'Caller-supplied search key (X-Request-Id / X-Road-Id).';
COMMENT ON COLUMN search_results.status    IS 'pending = broadcast registered, no result yet; complete = body holds the aggregated ConsignmentRow[] JSON.';
COMMENT ON COLUMN search_results.body      IS 'Aggregated ConsignmentRow[] JSON when status = complete, NULL while pending.';

CREATE INDEX idx_search_results_id      ON search_results (search_id, created_at DESC, row_id DESC);
CREATE INDEX idx_search_results_created ON search_results (created_at);

GRANT SELECT, INSERT ON search_results TO app;
GRANT SELECT, DELETE ON search_results TO db_archiver;
--rollback DROP TABLE search_results;
