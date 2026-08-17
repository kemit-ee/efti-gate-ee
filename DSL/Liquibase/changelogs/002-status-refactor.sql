-- ============================================================================
-- 002-status-refactor
-- ADR-002 (Sten Viljus, Anton Keks, 17.08.2026):
--   * Lisa gate_status ENUMile väärtus DELETED (pehme kustutus)
--   * Eemalda is_active veerg gates ja platforms tabelitest
--   * Uuenda indeksid
--   * Lisa eestikeelsed kommentaarid status veergudele
-- ============================================================================

-- 1. Lisa DELETED gate_status ENUMile (DISABLED jääb alles)
ALTER TYPE gate_status ADD VALUE IF NOT EXISTS 'DELETED';

-- 2. Uuenda gates.status kommentaar (nüüd sisaldab DELETED)
COMMENT ON COLUMN gates.status IS
  'Värava tööseisund hetkel: ONLINE — aktiivne ja kättesaadav; OFFLINE — ping ebaõnnestus; DISABLED — halduslikult välja lülitatud (nähtav loendis); DELETED — pehme kustutus (operaatori poolt eemaldatud, rida säilib auditiks).';

-- 3. Uuenda platforms.status kommentaar
COMMENT ON COLUMN platforms.status IS
  'Platvormi tööseisund hetkel: ONLINE — aktiivne ja kättesaadav; OFFLINE — ping ebaõnnestus; DISABLED — halduslikult välja lülitatud (nähtav loendis); DELETED — pehme kustutus (operaatori poolt eemaldatud, rida säilib auditiks).';

-- 4. Eemalda is_active gates tabelist
ALTER TABLE gates DROP COLUMN IF EXISTS is_active;
DROP INDEX IF EXISTS idx_gates_active;

-- 5. Eemalda is_active platforms tabelist
ALTER TABLE platforms DROP COLUMN IF EXISTS is_active;
DROP INDEX IF EXISTS idx_platforms_active;

-- 6. Uuenda cert_lookup indeks (eemalda WHERE is_active = TRUE filter)
DROP INDEX IF EXISTS idx_platforms_cert_lookup;
CREATE INDEX IF NOT EXISTS idx_platforms_cert_lookup ON platforms (cert_subject, cert_serial);
