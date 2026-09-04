--liquibase formatted sql

-- Drop the `is_admin` flag from `users`. The system UI has exactly one user who is already admin,
-- so role-related flags are unnecessary. Authorisation for competent authorities is handled via
-- `authorities.registry_code` over X-Road, not via user rows.

--changeset efti:users-drop-admin-flag
ALTER TABLE users DROP COLUMN IF EXISTS is_admin;
--rollback ALTER TABLE users ADD COLUMN IF NOT EXISTS is_admin BOOLEAN NOT NULL DEFAULT FALSE;
