-- ============================================================================
-- 001-add-platform-status
-- Add `status` column (gate_status ENUM) and matching index to the platforms
-- table, mirroring the existing status column on gates.
-- ============================================================================

-- Add status column; existing rows get ONLINE as a sensible default.
ALTER TABLE platforms
  ADD COLUMN IF NOT EXISTS status gate_status NOT NULL DEFAULT 'ONLINE';

COMMENT ON COLUMN platforms.status IS 'Operational status snapshot at row time: ONLINE / OFFLINE / DISABLED';

-- Mirror the index pattern from gates (idx_gates_status)
CREATE INDEX IF NOT EXISTS idx_platforms_status ON platforms (status);
