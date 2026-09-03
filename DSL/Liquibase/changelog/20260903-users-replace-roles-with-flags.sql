--liquibase formatted sql

-- Replace the RBAC `roles TEXT[]` column with a single boolean flag. The gate only ever had two
-- roles (ADMIN, AUTHORITY); `is_admin` used to be computed in every user-facing ReSql SELECT as
-- `'ADMIN' = ANY(roles)`. `is_authority` was dropped entirely — a competent authority is an
-- organisation authenticated over X-Road (authorities.registry_code), never a users row.
--
-- No backfill: `roles` and its dev seed (20260825 / 20260826) were dev-only and never reached a
-- persistent environment. Recreate local volumes if needed (`docker compose down -v`).

--changeset efti:users-add-admin-flag
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_admin BOOLEAN NOT NULL DEFAULT FALSE;
--rollback ALTER TABLE users DROP COLUMN IF EXISTS is_admin;

--changeset efti:users-drop-roles
ALTER TABLE users DROP COLUMN IF EXISTS roles;
--rollback ALTER TABLE users ADD COLUMN IF NOT EXISTS roles TEXT[] NOT NULL DEFAULT '{}';
