-- ---------------------------------------------------------------------------
-- Semantic fixtures for the consignments-search benchmarks
-- (docs/performance/askend_perf_verification/analysis.md §6.4, semantic-test.sql).
--
-- The findable baseline row (main_transport_id = 'VESSEL-001', gate EU-EE) is NOT seeded
-- here — it goes in the real way, by POSTing code/xml-mapper/xsd/FTI004/sample.xml through
-- POST /platforms/v1/consignments, so the xml-mapper + insert_consignment column mapping is
-- exercised too. See run.md.
--
-- This file only adds what has no XML route: an append-only re-upload and a single-version
-- dataset, to prove C6 returns current versions only.
--   dataset p1/1111...  uploaded twice: main_transport_id AAA (old) -> BBB (current)
--   dataset p1/2222...  uploaded once:  main_transport_id CCC
-- A search for 'AAA' must return nothing; 'BBB' and 'CCC' must both return.
--
-- Run:
--   docker compose -f compose.yml exec -T database psql -U efti -d efti < docs/performance/askend_perf_verification/seed-consignments.sql
-- For volume, follow with docs/performance/askend_perf_verification/bulk-insert-1m.sql.
-- ---------------------------------------------------------------------------
\timing on

DELETE FROM consignments WHERE platform_id = 'p1';

INSERT INTO consignments (dataset_id, platform_id, gate_id, xml, status, main_transport_id, created_at) VALUES
 ('11111111-1111-1111-1111-111111111111', 'p1', 'EU-EE', '<x/>', 'ACTIVE', 'AAA', now() - interval '1 hour'),
 ('11111111-1111-1111-1111-111111111111', 'p1', 'EU-EE', '<x/>', 'ACTIVE', 'BBB', now()),
 ('22222222-2222-2222-2222-222222222222', 'p1', 'EU-EE', '<x/>', 'ACTIVE', 'CCC', now());

ANALYZE consignments;

-- Sanity check: VESSEL-001 -> 1 (from the sample.xml POST), AAA -> 0, BBB -> 1, CCC -> 1.
SELECT
  probe.mt AS search_id,
  (SELECT count(*) FROM consignments c
     WHERE c.main_transport_id = probe.mt
       AND c.gate_id = 'EU-EE'
       AND c.status <> 'DELETED'
       AND NOT EXISTS (SELECT 1 FROM consignments c2
                       WHERE c2.platform_id = c.platform_id
                         AND c2.dataset_id  = c.dataset_id
                         AND c2.created_at   > c.created_at)) AS current_matches
FROM (VALUES ('VESSEL-001'), ('AAA'), ('BBB'), ('CCC')) AS probe(mt);
