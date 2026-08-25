--liquibase formatted sql

-- Dev-only fixtures. Personal codes MUST match docker/tara-mock/identities.json.
-- The gate does not auto-provision on first login (docs/architecture/identity-and-access/
-- authentication.md:35), so a TARA identity with no users row here is rejected — that is
-- exactly what the third identity in identities.json (60001017727, deliberately absent
-- below) exercises.

--changeset efti:005-seed-dev-users context:dev
INSERT INTO users (id, tara_sub, email, name, is_admin, roles, subsets) VALUES
  -- Super Admin: is_admin = TRUE AND roles = {} — unrestricted
  -- (docs/specs/permissions-matrix.md:70). Reaches GET /api/v1/audit.
  ('11111111-1111-1111-1111-111111111111', '60001019906', 'super.admin@efti.test', 'Super Admin',
   TRUE,  '{}'::jsonb, ARRAY[]::TEXT[]),
  -- Authority officer: no ADMIN role, so GET /api/v1/audit must return 403 FORBIDDEN
  -- (docs/specs/permissions-matrix.md:177) while GET /api/v1/user still returns 200 (:163).
  ('22222222-2222-2222-2222-222222222222', '60001017869', 'mari.tamm@efti.test', 'Mari Tamm',
   FALSE, '{"AUTHORITY":["auth-mta"]}'::jsonb, ARRAY['EU07']::TEXT[]);

-- Rollback is a soft-delete, not a DELETE: append-only forbids removing rows, so
-- "undo" means inserting a newer row whose is_active = FALSE (docs/specs/db/README.md).
--rollback INSERT INTO users (id, tara_sub, email, name, is_admin, roles, subsets, is_active) SELECT id, tara_sub, email, name, is_admin, roles, subsets, FALSE FROM users WHERE tara_sub IN ('60001019906', '60001017869');
