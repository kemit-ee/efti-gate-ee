\pset pager off
\timing on
SET work_mem = '4MB';

\echo '######## C0 CURRENT — DISTINCT ON whole table, filter after (no JOIN, insert-only) ########'
EXPLAIN (ANALYZE, BUFFERS) SELECT row_id, main_transport_id FROM (
  SELECT DISTINCT ON (platform_id, dataset_id) * FROM consignments
  ORDER BY platform_id, dataset_id, created_at DESC
) latest WHERE status != 'DELETED' AND gate_id = 'EU-EE' AND main_transport_id = 'BULK-500000' LIMIT 20;

\echo '######## C1 FILTER-FIRST — criteria in inner WHERE, dedupe the matches (no JOIN, insert-only) ########'
\echo '   SEMANTIC CAVEAT: can return a row whose dataset was later re-uploaded WITHOUT this main_transport_id'
EXPLAIN (ANALYZE, BUFFERS) SELECT DISTINCT ON (platform_id, dataset_id) row_id, main_transport_id
FROM consignments
WHERE status != 'DELETED' AND gate_id = 'EU-EE' AND main_transport_id = 'BULK-500000'
ORDER BY platform_id, dataset_id, created_at DESC LIMIT 20;

\echo '######## C6 NOT EXISTS — filter first, then "no newer row for this dataset" (no JOIN keyword; self-correlated; insert-only; exact semantics) ########'
EXPLAIN (ANALYZE, BUFFERS) SELECT row_id, main_transport_id
FROM consignments c
WHERE status != 'DELETED' AND gate_id = 'EU-EE' AND main_transport_id = 'BULK-500000'
  AND NOT EXISTS (SELECT 1 FROM consignments c2
                  WHERE c2.platform_id = c.platform_id AND c2.dataset_id = c.dataset_id
                    AND c2.created_at > c.created_at)
LIMIT 20;

\echo '######## C3 is_latest flag — WHERE is_latest AND criteria (no JOIN; breaks strict insert-only: 1 UPDATE per insert of a cache column; exact semantics) ########'
ALTER TABLE consignments ADD COLUMN is_latest boolean NOT NULL DEFAULT true;
CREATE UNIQUE INDEX uq_c_latest ON consignments (platform_id, dataset_id) WHERE is_latest;
ANALYZE consignments;
EXPLAIN (ANALYZE, BUFFERS) SELECT row_id, main_transport_id FROM consignments
WHERE is_latest AND status != 'DELETED' AND gate_id = 'EU-EE' AND main_transport_id = 'BULK-500000' LIMIT 20;
ALTER TABLE consignments DROP COLUMN is_latest;
