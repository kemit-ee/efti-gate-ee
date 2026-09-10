INSERT INTO consignments (dataset_id, platform_id, gate_id, xml, status, main_transport_id, created_at) VALUES
 ('11111111-1111-1111-1111-111111111111','p1','EU-EE','<x/>','ACTIVE','AAA', now() - interval '1 hour'),
 ('11111111-1111-1111-1111-111111111111','p1','EU-EE','<x/>','ACTIVE','BBB', now()),
 ('22222222-2222-2222-2222-222222222222','p1','EU-EE','<x/>','ACTIVE','CCC', now());
\echo 'search AAA (stale id of a re-uploaded dataset):'
SELECT 'C0 current' AS q, coalesce(string_agg(main_transport_id,','),'(empty)') AS result FROM (SELECT DISTINCT ON (platform_id,dataset_id) * FROM consignments ORDER BY platform_id,dataset_id,created_at DESC) l WHERE main_transport_id='AAA'
UNION ALL SELECT 'C1 filter-first', coalesce(string_agg(x.mt,','),'(empty)') FROM (SELECT DISTINCT ON (platform_id,dataset_id) main_transport_id mt FROM consignments WHERE main_transport_id='AAA' ORDER BY platform_id,dataset_id,created_at DESC) x
UNION ALL SELECT 'C6 filter+NOT EXISTS', coalesce(string_agg(main_transport_id,','),'(empty)') FROM consignments c WHERE main_transport_id='AAA' AND NOT EXISTS (SELECT 1 FROM consignments c2 WHERE c2.platform_id=c.platform_id AND c2.dataset_id=c.dataset_id AND c2.created_at>c.created_at);
\echo 'search BBB (current id) + CCC (single-version) -- C6 must return both:'
SELECT string_agg(main_transport_id,',' ORDER BY main_transport_id) AS c6_result FROM consignments c WHERE main_transport_id IN ('BBB','CCC') AND NOT EXISTS (SELECT 1 FROM consignments c2 WHERE c2.platform_id=c.platform_id AND c2.dataset_id=c.dataset_id AND c2.created_at>c.created_at);
