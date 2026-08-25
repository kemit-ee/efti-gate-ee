--liquibase formatted sql

--changeset efti:013-seed-mock-platform context:dev
INSERT INTO platforms (id, base_url, headers) VALUES
  ('mock', 'http://ruuter:8086/efti/mock-platform', '{"X-Api-Key": "mock-secret-key"}'::jsonb);

--rollback DELETE FROM platforms WHERE id = 'mock';
