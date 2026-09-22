--liquibase formatted sql
--changeset efti:20260922-rds-role-grants
--comment Map RDS create_default_users roles onto schema grant targets (app / db_archiver).

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app')
     AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'efti_rw') THEN
    EXECUTE 'GRANT app TO efti_rw';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'db_archiver')
     AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'efti_ro') THEN
    EXECUTE 'GRANT db_archiver TO efti_ro';
  END IF;
END $$;
