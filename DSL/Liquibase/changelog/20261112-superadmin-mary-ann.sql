--liquibase formatted sql

-- Super administrator MARY ÄNN,O’CONNEŽ-ŠUSLIK, personal code 60001019906, in every environment
-- (no context). The name is written with Unicode escapes (Ä = 00C4, ’ = 2019, Ž = 017D, Š = 0160) so
-- it does not depend on the encoding Liquibase reads this file with.
--
-- users is append-only and the latest row per id wins. Where a row with this personal code already
-- exists (the dev seed "Super Admin" of 20260813-auth-seed.sql) the new row reuses its id, so it is a
-- rename and the personal code never has two active ids; elsewhere a new id is generated.

--changeset efti:superadmin-mary-ann
INSERT INTO users (id, tara_sub, name)
SELECT COALESCE(
         (SELECT id FROM users WHERE tara_sub = '60001019906' ORDER BY created_at DESC, revision DESC LIMIT 1),
         uuid_generate_v4()),
       '60001019906',
       U&'MARY \00C4NN,O\2019CONNE\017D-\0160USLIK';

-- Rollback is a soft-delete: append-only forbids removing rows, so "undo" inserts a newer row with is_active = FALSE.
--rollback INSERT INTO users (id, tara_sub, name, is_active) SELECT id, tara_sub, name, FALSE FROM (SELECT DISTINCT ON (id) id, tara_sub, name FROM users WHERE tara_sub = '60001019906' ORDER BY id, created_at DESC, revision DESC) latest;
