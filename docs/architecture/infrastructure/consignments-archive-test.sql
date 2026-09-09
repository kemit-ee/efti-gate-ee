-- Smoke test for archive.sweep_consignments (DSL/Liquibase/changelog/20260910-consignments-archive.sql).
-- Run against a dev DB:
--   docker compose -f compose.yml exec -T database psql -U efti -d efti -v ON_ERROR_STOP=1 \
--     < docs/architecture/infrastructure/consignments-archive-test.sql
--
-- Expected: 2 rows moved (D1/V1, D2/W1); a re-run moves 0; the current row of every dataset —
-- including D2's DELETED tombstone — stays in public.consignments; D3 (superseded only recently)
-- is untouched.

TRUNCATE public.consignments;
TRUNCATE archive.consignments;

-- D1: three versions; V1 superseded by V2 (60d old) -> V1 moves. V2 superseded by V3 (1d old) -> V2 stays.
INSERT INTO public.consignments (dataset_id, platform_id, gate_id, xml, status, main_transport_id, created_at) VALUES
 ('d1111111-1111-1111-1111-111111111111','p1','EU-EE','<x/>','ACTIVE','V1',         now() - interval '90 days'),
 ('d1111111-1111-1111-1111-111111111111','p1','EU-EE','<x/>','ACTIVE','V2',         now() - interval '60 days'),
 ('d1111111-1111-1111-1111-111111111111','p1','EU-EE','<x/>','ACTIVE','V3-current', now() - interval '1 day');

-- D2: deleted dataset. W1 superseded by the DELETED tombstone (100d old) -> W1 moves, tombstone stays.
INSERT INTO public.consignments (dataset_id, platform_id, gate_id, xml, status, main_transport_id, created_at) VALUES
 ('d2222222-2222-2222-2222-222222222222','p1','EU-EE','<x/>','ACTIVE', 'W1',           now() - interval '120 days'),
 ('d2222222-2222-2222-2222-222222222222','p1','EU-EE','<x/>','DELETED','W2-tombstone', now() - interval '100 days');

-- D3: superseding row is only 5 days old -> retention window keeps both rows live.
INSERT INTO public.consignments (dataset_id, platform_id, gate_id, xml, status, main_transport_id, created_at) VALUES
 ('d3333333-3333-3333-3333-333333333333','p1','EU-EE','<x/>','ACTIVE','U1',         now() - interval '20 days'),
 ('d3333333-3333-3333-3333-333333333333','p1','EU-EE','<x/>','ACTIVE','U2-current', now() - interval '5 days');

SET ROLE db_archiver;
SELECT archive.sweep_consignments()            AS moved_expect_2;
SELECT archive.sweep_consignments()            AS moved_again_expect_0;
RESET ROLE;

\echo 'public.consignments — expect V2, V3-current, W2-tombstone, U1, U2-current:'
SELECT dataset_id, main_transport_id, status::text
FROM public.consignments ORDER BY dataset_id, created_at;

\echo 'archive.consignments — expect V1, W1:'
SELECT dataset_id, main_transport_id, status::text, archived_at::date
FROM archive.consignments ORDER BY dataset_id, created_at;

\echo 'latest-row read for D2 still returns the DELETED tombstone (audit):'
SELECT DISTINCT ON (dataset_id) main_transport_id, status::text
FROM public.consignments WHERE dataset_id = 'd2222222-2222-2222-2222-222222222222'
ORDER BY dataset_id, created_at DESC;
