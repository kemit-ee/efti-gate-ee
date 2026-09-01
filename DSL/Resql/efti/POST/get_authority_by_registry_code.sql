/*
description: get the ACTIVE authority whose latest row carries registry_code, for X-Road access control
params:
  registry_code: { type: string, required: true }
*/
-- The DISTINCT ON resolves the latest row per logical id BEFORE any filtering; the outer WHERE
-- then applies registry_code and status. That order is load-bearing (permissions-matrix.md §8.1):
-- filtering inside the subquery makes DISTINCT ON pick the latest of the *filtered* set, so an
-- authority whose latest row is DELETED but which has an older ACTIVE row would still
-- authenticate, and a superseded registry_code would keep authenticating after a correction.
-- status = 'ACTIVE' is a positive allowlist rather than status != 'DELETED' so that a future
-- status (e.g. SUSPENDED) cannot authenticate by default.
--
-- No LIMIT 1: registry_code has no unique constraint (004-authorities.sql) and the admin create
-- route does not check for duplicates, so two ACTIVE authorities can share a code. Returning one
-- arbitrary row would silently hand one organisation the other's subset entitlement. The caller
-- (the X-Road guards) rejects a >1 row count outright — see guard_ambiguous.
SELECT
  a.row_id,
  a.id,
  a.name,
  a.registry_code,
  a.subsets,
  a.status,
  a.created_at
FROM (
  SELECT DISTINCT ON (id)
    row_id,
    id,
    name,
    registry_code,
    subsets,
    status::text AS status,
    created_at
  FROM authorities
  -- Narrows candidate ids through idx_authorities_registry_code WITHOUT filtering the rows the
  -- DISTINCT ON sees: an id qualifies if ANY of its rows ever carried this registry_code, and
  -- that id's LATEST row is then resolved unfiltered. So a DELETED latest row, or a latest row
  -- carrying a corrected registry_code, still wins and is then rejected by the outer WHERE.
  -- Without this the subquery scans and sorts the whole table on every X-Road request.
  WHERE id IN (SELECT id FROM authorities WHERE registry_code = :registry_code)
  ORDER BY id, created_at DESC
) a
WHERE a.registry_code = :registry_code
  AND a.status = 'ACTIVE'
ORDER BY a.id;
