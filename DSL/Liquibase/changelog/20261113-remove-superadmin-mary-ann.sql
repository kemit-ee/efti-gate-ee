--liquibase formatted sql

-- Removes the super administrator added by 20261112-superadmin-mary-ann.sql. users is append-only,
-- so "remove" is a newer row with is_active = FALSE for the latest row of this personal code; the
-- protect_users_append trigger keeps a deactivated id deactivated, and a login for the code is then
-- refused.
--
-- The dev and CI databases must keep the account: the E2E suite signs in as 60001019906. A context
-- filter cannot guarantee that (CI runs Liquibase with the dev seed present and this changeset ran
-- anyway), so the changeset is marked run, not executed, whenever the dev seed row exists. The add
-- add step reuses that row's id, so on a database with the dev seed it is the same account.

--changeset efti:remove-superadmin-mary-ann
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM users WHERE id = '11111111-1111-1111-1111-111111111111'
INSERT INTO users (id, tara_sub, name, is_active)
SELECT id, tara_sub, name, FALSE
FROM (
  SELECT DISTINCT ON (id) id, tara_sub, name
  FROM users
  WHERE tara_sub = '60001019906'
  ORDER BY id, created_at DESC, revision DESC
) latest;

-- The removal itself cannot be undone: a deactivated id cannot be reactivated. Re-adding the person
-- means a changeset that inserts a new id for the code.
--rollback SELECT 1;
