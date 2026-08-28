--liquibase formatted sql

-- Dev-only: assign roles to seeded users (append-only pattern — inserts new rows with the
-- same logical id so the latest row by created_at wins).

--changeset efti:rbac-dev-seed-roles context:dev
INSERT INTO users (id, tara_sub, name, roles, is_active) VALUES
  -- Super Admin gets ADMIN role
  ('11111111-1111-1111-1111-111111111111', '60001019906', 'Super Admin', ARRAY['ADMIN'],     TRUE),
  -- Authority officer gets AUTHORITY role
  ('22222222-2222-2222-2222-222222222222', '60001017869', 'Mari Tamm',   ARRAY['AUTHORITY'], TRUE);

--rollback INSERT INTO users (id, tara_sub, name, roles, is_active) VALUES ('11111111-1111-1111-1111-111111111111', '60001019906', 'Super Admin', ARRAY['ADMIN'], FALSE), ('22222222-2222-2222-2222-222222222222', '60001017869', 'Mari Tamm', ARRAY['AUTHORITY'], FALSE);
