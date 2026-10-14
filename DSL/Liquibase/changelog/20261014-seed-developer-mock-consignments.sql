--liquibase formatted sql

-- Dev-only identifier rows for the datasets the public X-Road developer mock serves
-- (docs/developer/x_road_developer_mock.md). With these the live gate answers transport-means and
-- search for MOCK-PLATE-1 / CONT-MOCK-01 and MOCK-PLATE-3 / MOCK-TRAILER-3 / MOCK-CONTAINER-3 exactly
-- as the mock does, and dataset fetches the same XML from the `mock` platform
-- (DSL/Ruuter/mock-platform/GET/v1/dataset.yml). Column values mirror the mock's canned rows in
-- DSL/Ruuter-xroad-mock/developer/POST/v1/transport-means.yml; tests/mock/dataset.py checks that.
-- The MOCK-PLATE-3 rows share created_at, so the lookup orders them by revision DESC: they are
-- inserted last-first to come back as ...0011, ...0012, ...0013, the mock's order.

--changeset efti:seed-developer-mock-consignments context:dev
INSERT INTO consignments (
  dataset_id, platform_id, gate_id, xml, status, transport_mode, dangerous_goods,
  main_transport_id, main_transport_type, transport_reg_country,
  loading_date, loading_country, unloading_date, unloading_country,
  used_equipment_ids, used_equipment_categories, used_equipment_countries, used_equipment_seq,
  carried_equipment_ids, carried_equipment_categories, carried_equipment_seq, created_at
) VALUES
  ('550e8400-e29b-41d4-a716-446655440001', 'mock', 'EU-EE', '<DeveloperMockSeed/>', 'ACTIVE', '3', NULL,
   'MOCK-PLATE-1', '1522', 'EE', '2026-04-23T06:00:00Z', 'EE', '2026-04-25T14:00:00Z', 'FI',
   ARRAY['CONT-MOCK-01'], ARRAY['CN'], ARRAY['EE'], ARRAY[1], ARRAY[]::TEXT[], ARRAY[]::TEXT[], ARRAY[]::INTEGER[],
   '2026-04-23T10:15:00Z'),
  ('550e8400-e29b-41d4-a716-446655440013', 'mock', 'EU-EE', '<DeveloperMockSeed/>', 'ACTIVE', '3', '1',
   'MOCK-PLATE-3', '1522', 'EE', '2026-09-14T12:00:00Z', 'EE', '2026-09-15T00:00:00Z', 'EE',
   ARRAY['MOCK-TRAILER-3'], ARRAY['TE'], ARRAY['EE'], ARRAY[1], ARRAY['MOCK-CONTAINER-3'], ARRAY['CN'], ARRAY[1],
   '2026-09-14T05:00:00Z'),
  ('550e8400-e29b-41d4-a716-446655440012', 'mock', 'EU-EE', '<DeveloperMockSeed/>', 'ACTIVE', '3', NULL,
   'MOCK-PLATE-3', '1522', 'EE', '2026-09-14T09:00:00Z', 'EE', '2026-09-15T00:00:00Z', 'EE',
   ARRAY['MOCK-TRAILER-3'], ARRAY['TE'], ARRAY['EE'], ARRAY[1], ARRAY['MOCK-CONTAINER-3'], ARRAY['CN'], ARRAY[1],
   '2026-09-14T05:00:00Z'),
  ('550e8400-e29b-41d4-a716-446655440011', 'mock', 'EU-EE', '<DeveloperMockSeed/>', 'ACTIVE', '3', NULL,
   'MOCK-PLATE-3', '1522', 'EE', '2026-09-14T06:00:00Z', 'EE', '2026-09-15T00:00:00Z', 'EE',
   ARRAY['MOCK-TRAILER-3'], ARRAY['TE'], ARRAY['EE'], ARRAY[1], ARRAY['MOCK-CONTAINER-3'], ARRAY['CN'], ARRAY[1],
   '2026-09-14T05:00:00Z');
--rollback INSERT INTO consignments (dataset_id, platform_id, gate_id, xml, status) SELECT dataset_id, platform_id, gate_id, xml, 'DELETED' FROM consignments WHERE xml = '<DeveloperMockSeed/>' AND status = 'ACTIVE';
