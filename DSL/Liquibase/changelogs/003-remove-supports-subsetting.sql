-- ============================================================================
-- 003-remove-supports-subsetting
-- ADR-003 (Sten Viljus, Anton Keks, 17.08.2026):
--   Regulatsiooni kohaselt on iga platvorm kohustatud teostama subsettimist
--   enda poolel — supports_subsetting lipp ei ole enam tähenduslik.
--   Eemaldatakse platforms tabelist.
-- ============================================================================

ALTER TABLE platforms DROP COLUMN IF EXISTS supports_subsetting;
DROP INDEX IF EXISTS idx_platforms_subsetting;
