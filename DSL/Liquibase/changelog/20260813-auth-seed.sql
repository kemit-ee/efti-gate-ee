--liquibase formatted sql

-- Dev-only fixtures. Personal codes MUST match docker/tara-mock/identities.json.
-- The gate does not auto-provision on first login (docs/architecture/identity-and-access/
-- authentication.md:35), so a TARA identity with no users row here is rejected — that is
-- exactly what the third identity in identities.json (60001017727, deliberately absent
-- below) exercises.

--changeset efti:005-seed-dev-users context:dev
INSERT INTO users (id, tara_sub, name, is_admin) VALUES
  -- Super Admin — full admin access
  ('11111111-1111-1111-1111-111111111111', '60001019906', 'Super Admin', TRUE),
  -- Plain non-admin user — exercises the 401/403 path on admin and authority routes
  ('22222222-2222-2222-2222-222222222222', '60001017869', 'Mari Tamm',   FALSE);

-- Rollback is a soft-delete, not a DELETE: append-only forbids removing rows, so
-- "undo" means inserting a newer row whose is_active = FALSE (docs/specs/db/README.md).
--rollback INSERT INTO users (id, tara_sub, name, is_active) SELECT id, tara_sub, name, FALSE FROM users WHERE tara_sub IN ('60001019906', '60001017869');
