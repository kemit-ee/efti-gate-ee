--liquibase formatted sql

-- Replace the RBAC `roles TEXT[]` column with two boolean flags. The gate only ever had two
-- roles (ADMIN, AUTHORITY); `is_admin` / `is_authority` used to be computed in every user-facing
-- ReSql SELECT as `'ADMIN' = ANY(roles)` / `'AUTHORITY' = ANY(roles)` — they are now real
-- columns. The authority guards still allow is_admin OR is_authority.
--
-- No backfill: `roles` and its dev seed (20260825 / 20260826) were dev-only and never reached a
-- persistent environment. Recreate local volumes if needed (`docker compose down -v`).

--changeset efti:users-add-admin-authority-flags
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_admin     BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_authority BOOLEAN NOT NULL DEFAULT FALSE;
--rollback ALTER TABLE users DROP COLUMN IF EXISTS is_admin; ALTER TABLE users DROP COLUMN IF EXISTS is_authority;

--changeset efti:users-drop-roles
ALTER TABLE users DROP COLUMN IF EXISTS roles;
--rollback ALTER TABLE users ADD COLUMN IF NOT EXISTS roles TEXT[] NOT NULL DEFAULT '{}';
