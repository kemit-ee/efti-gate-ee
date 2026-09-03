--liquibase formatted sql

--changeset efit-gate:drop-follow-up-log-requesting-user-id
ALTER TABLE follow_up_log DROP COLUMN requesting_user_id;
