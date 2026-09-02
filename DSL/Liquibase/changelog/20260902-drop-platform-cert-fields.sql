-- Drop cert_subject and cert_serial columns from platforms (no longer needed).
-- The idx_platforms_cert_lookup index is dropped automatically with the columns.

ALTER TABLE platforms DROP COLUMN IF EXISTS cert_subject;
ALTER TABLE platforms DROP COLUMN IF EXISTS cert_serial;

DROP INDEX IF EXISTS idx_platforms_cert_lookup;
