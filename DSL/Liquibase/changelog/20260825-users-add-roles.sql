--liquibase formatted sql

--changeset efti:rbac-users-roles
-- Add roles column for RBAC. Two roles exist: ADMIN (full admin API access) and AUTHORITY
-- (authority API: dataset search, follow-up). Stored as a TEXT array directly on the users
-- row — the system has two roles and no need for a separate roles table.
ALTER TABLE users ADD COLUMN IF NOT EXISTS roles TEXT[] NOT NULL DEFAULT '{}';

--rollback ALTER TABLE users DROP COLUMN IF EXISTS roles;
